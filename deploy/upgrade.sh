#!/bin/sh
# 升级或回滚集群到指定版本，不停业：
#   1. 先备份一次（万一新版本出问题，可以连数据一起退回）
#   2. 拉取该版本的镜像（GitHub Release 自动发布到 ghcr.io）
#   3. 先换 shop-1，等它健康后再换 shop-2；换其中一个时另一个照常接单
#   4. 跑冒烟测试；记录当前和上一个版本
# 用法：./deploy/upgrade.sh v1.2.3          升级到 v1.2.3
#       ./deploy/upgrade.sh --rollback      回到上一个版本（读 .deployed-versions）
# 镜像是私有的话，先在服务器上登录一次：docker login ghcr.io（用户名 + 有 read:packages 权限的 GitHub 令牌）
set -eu

cd "$(dirname "$0")/.."
compose=${COMPOSE:-docker compose -f docker-compose.cluster.yml}
repo=${SHOP_IMAGE_REPO:-ghcr.io/stormyu324/storm-shop}
env_file=${ENV_FILE:-.env}
history=${DEPLOY_HISTORY:-.deployed-versions}

if [ "${1:-}" = "--rollback" ]; then
    if [ ! -f "$history" ] || [ "$(wc -l < "$history")" -lt 2 ]; then
        echo "没有上一个版本的记录（$history），请直接指定版本：$0 v1.2.3" >&2
        exit 1
    fi
    tag=$(tail -n 2 "$history" | head -n 1)
    echo "回滚到上一个版本：$tag"
    echo "注意：如果新版本升级过数据库表结构，旧版本通常仍能运行（迁移只增不删，见 docs/release.md）；"
    echo "      数据出了问题需要连数据一起退回时，用升级前自动生成的备份：./deploy/restore-cluster.sh"
else
    tag=${1:-}
fi
case "$tag" in
    v[0-9]*.[0-9]*.[0-9]*) ;;
    *) echo "用法：$0 v1.2.3 或 $0 --rollback" >&2; exit 1 ;;
esac
image="$repo:$tag"

echo "== 1/5 备份当前数据"
$compose run --rm backup --once

echo "== 2/5 拉取镜像 $image"
docker pull "$image"

# 把版本写进 .env，以后 docker compose up 时也用这个版本
touch "$env_file"
if grep -q '^SHOP_IMAGE=' "$env_file"; then
    sed -i.bak "s#^SHOP_IMAGE=.*#SHOP_IMAGE=$image#" "$env_file" && rm -f "$env_file.bak"
else
    echo "SHOP_IMAGE=$image" >> "$env_file"
fi

wait_healthy() {
    node=$1
    id=$($compose ps -q "$node")
    i=0
    while [ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" != "healthy" ]; do
        i=$((i + 1))
        if [ "$i" -gt 60 ]; then
            echo "$node 5 分钟内没有变成 healthy。另一个节点仍在运行旧版本，商城没有中断。" >&2
            echo "查看原因：$compose logs --tail 100 $node；回滚：$0 --rollback" >&2
            exit 1
        fi
        sleep 5
    done
}

for node in shop-1 shop-2; do
    n=$([ "$node" = shop-1 ] && echo 3 || echo 4)
    echo "== $n/5 更新 $node（另一个节点继续接单）"
    SHOP_IMAGE="$image" $compose up -d --no-deps --no-build "$node"
    wait_healthy "$node"
done

echo "== 5/5 冒烟测试"
./deploy/smoke-test.sh "${SMOKE_URL:-https://localhost}"

if [ "${1:-}" != "--rollback" ]; then
    echo "$tag" >> "$history"
else
    # 回滚后把最后一行（出问题的版本）去掉，“上一个版本”指向更早的那个
    sed -i.bak '$d' "$history" && rm -f "$history.bak"
fi
echo "已更新到 $tag"
