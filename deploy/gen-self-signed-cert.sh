#!/bin/sh
# 生成自签名 HTTPS 证书，只用于本机或内网试运行集群（浏览器会提示“不安全”，手动继续即可）。
# 正式上线请用 ./deploy/issue-cert.sh 申请 Let's Encrypt 免费证书。
# 用法：./deploy/gen-self-signed-cert.sh [域名，默认 localhost]
set -eu

cd "$(dirname "$0")/.."
domain=${1:-localhost}
# 预发布环境用另一个目录：CERTS_PATH=./deploy/certs-staging ./deploy/gen-self-signed-cert.sh
certs=${CERTS_PATH:-deploy/certs}
mkdir -p "$certs"
if [ -e "$certs/fullchain.pem" ] || [ -e "$certs/privkey.pem" ]; then
    echo "$certs 里已经有证书，为避免覆盖已停止。如需重新生成，请先自行移走旧证书。" >&2
    exit 1
fi
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
    -subj "/CN=$domain" \
    -addext "subjectAltName=DNS:$domain,DNS:localhost,IP:127.0.0.1" \
    -keyout "$certs/privkey.pem" -out "$certs/fullchain.pem"
chmod 644 "$certs/fullchain.pem"
chmod 600 "$certs/privkey.pem"
echo "已生成自签名证书：$certs/fullchain.pem、$certs/privkey.pem（$domain，有效期 365 天）"
