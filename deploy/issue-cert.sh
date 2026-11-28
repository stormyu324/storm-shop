#!/bin/sh
# 申请或续期 Let's Encrypt 免费 HTTPS 证书，并让 nginx 加载新证书。
# 前提：域名已解析到这台服务器，80 端口能从公网访问，集群已经在运行（nginx 负责响应验证请求）。
#   首次运行前 nginx 需要一张证书才能启动：先用 ./deploy/gen-self-signed-cert.sh 生成临时证书启动集群，再运行本脚本。
# 用法：./deploy/issue-cert.sh 你的域名 你的邮箱
# 续期：证书 90 天有效，建议用 crontab 每周运行一次本脚本（未到期时 certbot 不会重复申请）。
set -eu

if [ $# -lt 2 ]; then
    echo "用法：$0 域名 邮箱" >&2
    exit 1
fi
domain=$1
email=$2
cd "$(dirname "$0")/.."
compose="docker compose -f docker-compose.cluster.yml"

$compose run --rm certbot certonly --webroot -w /var/www/certbot \
    -d "$domain" --email "$email" --agree-tos --non-interactive --keep-until-expiring

# certbot 把证书放在 live/域名/ 下（是指向 archive/ 的链接），复制成 nginx 读取的固定文件名
live="deploy/letsencrypt/live/$domain"
cp -L "$live/fullchain.pem" deploy/certs/fullchain.pem
cp -L "$live/privkey.pem" deploy/certs/privkey.pem
chmod 644 deploy/certs/fullchain.pem
chmod 600 deploy/certs/privkey.pem

$compose exec nginx nginx -s reload
echo "证书已更新并重新加载：$domain"
