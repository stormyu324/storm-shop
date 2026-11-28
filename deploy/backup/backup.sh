#!/bin/sh
# 集群自动备份（在 backup 容器里运行，见 docker-compose.cluster.yml）：
# 每天 BACKUP_AT（默认 03:30，按 TZ 时区）备份一次 MySQL 和商品图片到 /backups，保留 BACKUP_KEEP_DAYS 天。
#   /backups/storm-YYYYmmdd-HHMMSS/
#     database.sql.gz   数据库（--single-transaction：商城运行中也能拿到一致的快照）
#     uploads.tar.gz    商品图片
#     manifest.txt      备份时间、表清单、图片数量，恢复演练时用来核对
#     SHA256SUMS        校验文件是否损坏
# 成功后更新 /backups/.last-success，应用把它的时间作为监控指标，太久没更新会告警。
# 用法：storm-backup          常驻，每天定时备份
#       storm-backup --once   立即备份一次后退出（手动备份、CI 演练）
set -eu

BACKUP_DIR=${BACKUP_DIR:-/backups}
BACKUP_AT=${BACKUP_AT:-03:30}
BACKUP_KEEP_DAYS=${BACKUP_KEEP_DAYS:-14}
DB_HOST=${DB_HOST:-mysql}
DB_NAME=${DB_NAME:-shop}
DB_USER=${DB_USER:-shop}
DB_PASSWORD_FILE=${DB_PASSWORD_FILE:-/run/secrets/DB_PASSWORD}
UPLOADS_DIR=${UPLOADS_DIR:-/uploads}

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [backup] $*"
}

# 密码写进只有自己能读的临时选项文件，不出现在命令行和进程列表里
mysql_options() {
    opts=$(mktemp)
    chmod 600 "$opts"
    {
        echo "[client]"
        echo "host=$DB_HOST"
        echo "user=$DB_USER"
        printf 'password=%s\n' "$(cat "$DB_PASSWORD_FILE")"
    } > "$opts"
    echo "$opts"
}

backup_once() {
    stamp=$(date '+%Y%m%d-%H%M%S')
    target="$BACKUP_DIR/storm-$stamp"
    work="$BACKUP_DIR/.storm-$stamp.partial"
    mkdir -p "$work"
    chmod 700 "$work"
    opts=$(mysql_options)
    trap 'rm -f "$opts"; rm -rf "$work"' EXIT

    log "开始备份数据库 $DB_NAME"
    mysqldump --defaults-extra-file="$opts" --single-transaction --quick --no-tablespaces \
        --set-gtid-purged=OFF --default-character-set=utf8mb4 --routines --triggers \
        "$DB_NAME" | gzip -c > "$work/database.sql.gz"
    # 管道里 mysqldump 失败时 gzip 仍会成功：检查导出的末尾标记，确认导出完整
    if ! gzip -dc "$work/database.sql.gz" | tail -n 1 | grep -q 'Dump completed'; then
        log "数据库导出不完整，本次备份作废"
        return 1
    fi

    log "开始备份商品图片 $UPLOADS_DIR"
    if [ -d "$UPLOADS_DIR" ]; then
        tar -C "$UPLOADS_DIR" -czf "$work/uploads.tar.gz" .
        upload_files=$(find "$UPLOADS_DIR" -type f | wc -l | tr -d ' ')
    else
        tar -czf "$work/uploads.tar.gz" -T /dev/null
        upload_files=0
    fi

    tables=$(mysql --defaults-extra-file="$opts" -N -B -e \
        "select table_name from information_schema.tables where table_schema='$DB_NAME' order by table_name" | tr '\n' ' ')
    {
        echo "created_at=$(date '+%Y-%m-%dT%H:%M:%S%z')"
        echo "database=$DB_NAME"
        echo "tables=$tables"
        echo "upload_files=$upload_files"
    } > "$work/manifest.txt"
    (cd "$work" && sha256sum database.sql.gz uploads.tar.gz manifest.txt > SHA256SUMS)
    chmod 600 "$work"/*

    mv "$work" "$target"
    trap 'rm -f "$opts"' EXIT
    date '+%Y-%m-%dT%H:%M:%S%z' > "$BACKUP_DIR/.last-success"
    log "备份完成：$target（$(du -sh "$target" | cut -f1)，图片 $upload_files 个）"

    if [ "$BACKUP_KEEP_DAYS" -gt 0 ] 2>/dev/null; then
        find "$BACKUP_DIR" -maxdepth 1 -type d -name 'storm-*' -mtime +"$BACKUP_KEEP_DAYS" -print | while read -r old; do
            rm -rf "$old"
            log "已删除超过 $BACKUP_KEEP_DAYS 天的旧备份：$old"
        done
    fi
    rm -f "$opts"
}

seconds_until_next_run() {
    now=$(date +%s)
    next=$(date -d "today $BACKUP_AT" +%s)
    if [ "$next" -le "$now" ]; then
        next=$(date -d "tomorrow $BACKUP_AT" +%s)
    fi
    echo $((next - now))
}

mkdir -p "$BACKUP_DIR"
if [ "${1:-}" = "--once" ]; then
    backup_once
    exit 0
fi

log "定时备份已启动：每天 $BACKUP_AT，保留 $BACKUP_KEEP_DAYS 天，保存到 $BACKUP_DIR"
while true; do
    wait_s=$(seconds_until_next_run)
    log "下次备份在 $((wait_s / 60)) 分钟后"
    sleep "$wait_s"
    # 每次在单独的进程里执行：set -e 才会生效，任何一步失败都不会写入“成功”标记。
    # 单次失败不退出容器，第二天继续；连续失败会被“备份太久没成功”告警发现
    "$0" --once || log "本次备份失败，请检查上面的错误"
done
