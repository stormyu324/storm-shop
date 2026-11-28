#!/bin/sh
# 生成集群部署用的密钥文件（secrets/ 目录，已加入 .gitignore，不会提交到 GitHub）。
# - 数据库、Redis、后台密码自动生成随机值；
# - Stripe / PayPal / Claude / S3 / 邮箱等第三方密钥建成空文件，用到哪个就把密钥粘贴进对应文件（不要带换行）。
# 已存在的文件不会被覆盖，可以重复运行。
set -eu

cd "$(dirname "$0")/.."
umask 077
mkdir -p secrets
chmod 700 secrets

random() {
    # 32 位字母数字，不含容易在 URL / 命令行里出问题的符号
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32
}

create() {
    name=$1
    value=$2
    file="secrets/$name"
    if [ -e "$file" ]; then
        echo "已存在，跳过：$file"
        return
    fi
    printf '%s' "$value" > "$file"
    # 容器里的应用以非 root 用户运行，需要能读这个文件；secrets/ 目录本身仍是 700，本机其他用户进不去
    chmod 644 "$file"
    if [ -n "$value" ]; then
        echo "已生成：$file"
    else
        echo "已建空文件（用到时填入）：$file"
    fi
}

create ADMIN_PASSWORD "$(random)"
create DB_PASSWORD "$(random)"
create MYSQL_ROOT_PASSWORD "$(random)"
create REDIS_PASSWORD "$(random)"
create METRICS_TOKEN "$(random)"
create GRAFANA_ADMIN_PASSWORD "$(random)"
create ADMIN_TOTP_SECRET ""
create STRIPE_SECRET_KEY ""
create STRIPE_WEBHOOK_SECRET ""
create PAYPAL_CLIENT_SECRET ""
create ANTHROPIC_API_KEY ""
create S3_SECRET_KEY ""
create SPRING_MAIL_PASSWORD ""

echo
echo "后台登录用户名默认是 admin，密码在 secrets/ADMIN_PASSWORD 文件里。"
echo "监控面板 Grafana 的用户名是 admin，密码在 secrets/GRAFANA_ADMIN_PASSWORD 文件里。"
echo "请把整个 secrets/ 目录另外备份一份（它不在数据库备份里），丢失后需要重新设置所有密码。"
