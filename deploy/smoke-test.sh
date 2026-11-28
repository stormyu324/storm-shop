#!/bin/sh
# 集群冒烟测试：部署或升级后运行，几十秒内检查整套集群是否正常（不产生订单，不改数据）。
#   - HTTPS、HTTP 跳转、安全响应头；监控指标不对公网开放
#   - 后台登录后，会话在两个应用节点上都有效（Redis 会话共享）
#   - 两个节点都能读到商品图片目录（共享存储卷）
#   - Prometheus 抓到了两个节点、Alertmanager 就绪、Loki 收到了日志、Grafana 正常（启动了监控时）
# 用法：./deploy/smoke-test.sh [网址，默认 https://localhost]
# 预发布环境：COMPOSE="docker compose -f docker-compose.cluster.yml --env-file .env.staging -p storm-staging" ./deploy/smoke-test.sh https://localhost:8443
set -eu

cd "$(dirname "$0")/.."
base=${1:-https://localhost}
compose=${COMPOSE:-docker compose -f docker-compose.cluster.yml}
secrets=${SECRETS_PATH:-./secrets}
# 自签名证书（试运行 / CI）时跳过证书校验；正式证书时设 SMOKE_STRICT_TLS=1
curl_opts="-s --max-time 15"
[ "${SMOKE_STRICT_TLS:-0}" = "1" ] || curl_opts="$curl_opts -k"
jar=$(mktemp)
trap 'rm -f "$jar"' EXIT
failures=0

pass() { echo "  通过  $1"; }
fail() { echo "  失败  $1"; failures=$((failures + 1)); }
check() { if eval "$2"; then pass "$1"; else fail "$1"; fi; }

# 在某个容器里请求（应用节点和监控服务不对外开放端口）
inside() {
    service=$1
    shift
    $compose exec -T "$service" wget -q -T 10 -O - "$@" 2>/dev/null
}

echo "== HTTPS 和安全头（$base）"
http_base=$(echo "$base" | sed -e 's#^https://#http://#' -e 's#:[0-9]*$##')
check "HTTP 跳转到 HTTPS" "curl $curl_opts -o /dev/null -w '%{http_code} %{redirect_url}' '$http_base/' | grep -q '^301 https://'"
# headers 在下面 check 的命令字符串里使用（eval 执行），shellcheck 看不出来
# shellcheck disable=SC2034
headers=$(curl $curl_opts -D - -o /dev/null "$base/")
check "首页 200" "echo \"\$headers\" | head -1 | grep -q ' 200'"
check "HSTS" "echo \"\$headers\" | grep -qi '^strict-transport-security: max-age='"
check "内容安全策略" "echo \"\$headers\" | grep -qi \"^content-security-policy: .*script-src 'self'\""
check "健康检查 /actuator/health" "curl $curl_opts '$base/actuator/health' | grep -q '\"UP\"'"
check "监控指标不对公网开放" "[ \"\$(curl $curl_opts -o /dev/null -w '%{http_code}' '$base/actuator/prometheus')\" = 404 ]"

echo "== 后台登录与会话共享"
csrf=$(curl $curl_opts -c "$jar" -b "$jar" "$base/api/admin/session" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
password=$(cat "$secrets/ADMIN_PASSWORD")
user=${ADMIN_USERNAME:-admin}
login=$(curl $curl_opts -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $csrf" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$password\"}" "$base/api/admin/session")
check "后台登录" "echo '$login' | grep -q '\"authenticated\":true'"
session=$(awk '$6 == "SESSION" || $6 == "JSESSIONID" {print $6 "=" $7}' "$jar" | tail -n 1)
for node in shop-1 shop-2; do
    check "$node 认得这个登录会话" "inside $node --header 'Cookie: $session' http://localhost:8080/api/admin/session | grep -q '\"authenticated\":true'"
done

echo "== 共享图片目录"
marker="smoke-$(date +%s).txt"
$compose exec -T shop-1 sh -c "echo ok > /app/uploads/$marker" 2>/dev/null || true
check "shop-2 能读到 shop-1 写入的文件" "$compose exec -T shop-2 cat /app/uploads/$marker 2>/dev/null | grep -q ok"
$compose exec -T shop-1 rm -f "/app/uploads/$marker" 2>/dev/null || true

echo "== 监控（未启动 --profile monitoring 时跳过）"
if $compose ps --status running --services 2>/dev/null | grep -qx prometheus; then
    up=0
    for _ in $(seq 1 12); do
        # PromQL：count(up{job="storm-shop"} == 1)
        up=$(inside prometheus 'http://localhost:9090/api/v1/query?query=count(up%7Bjob%3D%22storm-shop%22%7D%3D%3D1)' \
            | sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)"\].*/\1/p')
        up=${up:-0}
        [ "$up" -ge 2 ] && break
        sleep 5
    done
    check "Prometheus 抓到两个节点（$up/2）" "[ '$up' -ge 2 ]"
    check "告警规则已加载" "inside prometheus http://localhost:9090/api/v1/rules | grep -q 'BackupTooOld'"
    check "Alertmanager 就绪" "inside alertmanager http://localhost:9093/-/ready | grep -qi ready"
    logs=0
    for _ in $(seq 1 12); do
        logs=$(inside prometheus 'http://loki:3100/loki/api/v1/query_range?query=%7Bservice%3D%22shop-1%22%7D&limit=5' | grep -o '"values":\[\[' | wc -l | tr -d ' ')
        [ "$logs" -ge 1 ] && break
        sleep 5
    done
    check "Loki 收到了应用日志" "[ '$logs' -ge 1 ]"
    check "Grafana 正常" "curl -s --max-time 10 http://127.0.0.1:${GRAFANA_PORT:-3000}/api/health | grep -q '\"database\": *\"ok\"'"
else
    echo "  跳过  监控服务没有运行"
fi

echo
if [ "$failures" -ne 0 ]; then
    echo "冒烟测试失败：$failures 项未通过。查看日志：$compose logs --tail 100" >&2
    exit 1
fi
echo "冒烟测试通过"
