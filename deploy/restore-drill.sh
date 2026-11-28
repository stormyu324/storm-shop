#!/bin/sh
# 恢复演练：把最近一次（或指定的）集群备份恢复到一个临时 MySQL 容器里，核对表、数据和图片，
# 演练完自动删除临时容器。不碰正在运行的商城，营业时间也可以做。建议每月做一次。
# 用法：./deploy/restore-drill.sh [backups/storm-YYYYmmdd-HHMMSS]
set -eu

cd "$(dirname "$0")/.."
backup=${1:-$(ls -d backups/storm-* 2>/dev/null | sort | tail -n 1 || true)}
if [ -z "$backup" ] || [ ! -d "$backup" ]; then
    echo "找不到备份目录（backups/storm-*）。先运行：docker compose -f docker-compose.cluster.yml run --rm backup --once" >&2
    exit 1
fi
echo "== 演练备份：$backup"
started=$(date +%s)

echo "== 1/4 校验文件完整性"
(cd "$backup" && sha256sum -c SHA256SUMS)

name="storm-restore-drill-$$"
pw=$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)
cleanup() {
    docker rm -f "$name" > /dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== 2/4 启动临时数据库"
docker run -d --name "$name" -e MYSQL_ROOT_PASSWORD="$pw" -e MYSQL_DATABASE=shop mysql:8.0 \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_bin > /dev/null
# 镜像初始化时会先起一个不监听端口的临时进程，等正式进程（port: 3306）就绪再导入
i=0
until docker logs "$name" 2>&1 | grep -q 'ready for connections.*port: 3306'; do
    i=$((i + 1))
    if [ "$i" -gt 120 ]; then
        echo "临时数据库 4 分钟内没有就绪" >&2
        docker logs "$name" 2>&1 | tail -20 >&2
        exit 1
    fi
    sleep 2
done

sql() {
    docker exec -i -e MYSQL_PWD="$pw" "$name" mysql -uroot -N -B "$@"
}

echo "== 3/4 导入数据库"
gzip -dc "$backup/database.sql.gz" | sql shop

echo "== 4/4 核对"
failed=0
expected_tables=$(sed -n 's/^tables=//p' "$backup/manifest.txt")
for t in $expected_tables; do
    if ! sql -e "select 1 from information_schema.tables where table_schema='shop' and table_name='$t'" | grep -q 1; then
        echo "  缺少表：$t"
        failed=1
    fi
done
echo "  表数量：$(echo "$expected_tables" | wc -w | tr -d ' ')"
for t in customer_order order_item product member_account after_sale; do
    if echo " $expected_tables " | grep -q " $t "; then
        echo "  $t：$(sql -e "select count(*) from \`$t\`" shop) 行"
    fi
done
schema=$(sql -e "select max(version) from flyway_schema_history where success = 1" shop 2>/dev/null || echo "?")
echo "  表结构版本（Flyway）：$schema"

expected_files=$(sed -n 's/^upload_files=//p' "$backup/manifest.txt")
actual_files=$(tar -tzf "$backup/uploads.tar.gz" | grep -vc '/$' || true)
echo "  商品图片：$actual_files 个（备份时 $expected_files 个）"
if [ "$actual_files" != "$expected_files" ]; then
    echo "  图片数量不一致"
    failed=1
fi

elapsed=$(( $(date +%s) - started ))
if [ "$failed" -ne 0 ]; then
    echo "== 演练失败：备份不能完整恢复，请检查备份任务（docker compose -f docker-compose.cluster.yml logs backup）" >&2
    exit 1
fi
echo "== 演练通过：备份可以完整恢复，用时 ${elapsed} 秒（真正恢复时还要加上停止和启动商城的时间）"
