#!/bin/sh
# 从备份恢复集群的数据库和商品图片（会覆盖当前数据！）。
# 恢复前会先自动备份一次当前状态，万一恢复错了还能回到恢复前。
# 用法：./deploy/restore-cluster.sh backups/storm-YYYYmmdd-HHMMSS
set -eu

cd "$(dirname "$0")/.."
compose="docker compose -f docker-compose.cluster.yml"
backup=${1:-}
if [ -z "$backup" ] || [ ! -d "$backup" ]; then
    echo "用法：$0 backups/storm-YYYYmmdd-HHMMSS" >&2
    echo "可用的备份：" >&2
    ls -d backups/storm-* 2>/dev/null | sort >&2 || true
    exit 1
fi
backup=$(cd "$backup" && pwd)

echo "将用这份备份覆盖当前的数据库和商品图片："
sed 's/^/  /' "$backup/manifest.txt"
(cd "$backup" && sha256sum -c --quiet SHA256SUMS)
echo
echo "恢复期间商城会停止服务几分钟。确认恢复请输入 RESTORE："
read -r answer
if [ "$answer" != "RESTORE" ]; then
    echo "已取消"
    exit 1
fi

echo "== 1/5 先备份当前状态"
$compose run --rm backup --once

echo "== 2/5 停止两个应用节点"
$compose stop shop-1 shop-2

echo "== 3/5 恢复数据库"
$compose exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" mysql -uroot -e "
    DROP DATABASE IF EXISTS shop;
    CREATE DATABASE shop CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    GRANT ALL PRIVILEGES ON shop.* TO '"'"'shop'"'"'@'"'"'%'"'"';"'
gzip -dc "$backup/database.sql.gz" | $compose exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/MYSQL_ROOT_PASSWORD)" mysql -uroot shop'

echo "== 4/5 恢复商品图片"
$compose run --rm --no-deps -v "$backup:/restore:ro" --entrypoint sh backup -c \
    'find /uploads -mindepth 1 -delete && tar -C /uploads -xzf /restore/uploads.tar.gz'

echo "== 5/5 启动应用节点"
$compose up -d shop-1 shop-2
echo "恢复完成。等两个节点变成 healthy（$compose ps）后，打开后台核对订单和商品。"
echo "如果恢复错了：用第 1 步刚生成的那份备份（backups/ 下最新的目录）再运行一次本脚本。"
