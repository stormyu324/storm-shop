# 集群部署（Docker Compose）

适用于日均一两千单、放在一台服务器上长期营业的场景：Nginx（HTTPS）+ 2 个应用节点 + MySQL 8 + Redis 7。
在自己电脑上用的话，看 README 的「一键启动」就够了，不需要这套。

## 准备

- 一台 Linux 服务器，装好 Docker 和 Docker Compose v2（`docker compose version` 能输出版本号）
- 一个域名，解析到这台服务器；防火墙放行 80 和 443 端口
- 把代码放到服务器上：`git clone https://github.com/stormyu324/storm-shop.git && cd storm-shop`

## 第一次部署

### 1. 生成密码和密钥文件

```bash
./deploy/init-secrets.sh
```

会在 `secrets/` 目录生成数据库、Redis、后台的随机密码，以及第三方密钥的空文件。用到哪个功能，就把密钥粘贴进对应文件（文件里只放密钥本身，不要带引号）：

| 文件 | 内容 |
| --- | --- |
| `secrets/ADMIN_PASSWORD` | 后台 admin 账号的密码（已自动生成） |
| `secrets/DB_PASSWORD`、`MYSQL_ROOT_PASSWORD` | 数据库密码（已自动生成） |
| `secrets/REDIS_PASSWORD` | Redis 密码（已自动生成） |
| `secrets/STRIPE_SECRET_KEY`、`STRIPE_WEBHOOK_SECRET` | Stripe 在线支付 |
| `secrets/PAYPAL_CLIENT_SECRET` | PayPal（同时在 `.env` 里设置 `PAYPAL_CLIENT_ID`） |
| `secrets/ANTHROPIC_API_KEY` | AI 生成英文资料、AI 导购 |
| `secrets/S3_SECRET_KEY` | 图片存对象存储时 |
| `secrets/SPRING_MAIL_PASSWORD` | 发邮件的 SMTP 密码 |
| `secrets/ADMIN_TOTP_SECRET` | 后台双因素验证的密钥（Base32，可选） |

`secrets/` 已加入 `.gitignore`，不会提交到 GitHub，也不在数据库备份里。**请另外把整个目录备份到安全的地方**（例如密码管理器），丢了要重新设置所有密码。

### 2. 设置网址等普通配置

在项目目录建一个 `.env` 文件（同样不会提交）：

```
PUBLIC_BASE_URL=https://shop.example.com
# 用到 PayPal / 邮件时：
# PAYPAL_CLIENT_ID=...
# PAYPAL_SANDBOX=false
# SPRING_MAIL_HOST=smtp.example.com
# SPRING_MAIL_USERNAME=shop@example.com
# SHOP_MAIL_FROM=shop@example.com
```

### 3. HTTPS 证书

nginx 需要证书才能启动，先生成一张临时的自签名证书：

```bash
./deploy/gen-self-signed-cert.sh shop.example.com
```

### 4. 启动

```bash
docker compose -f docker-compose.cluster.yml up -d --build
```

第一次要构建镜像、下载依赖，需要几分钟。`docker compose -f docker-compose.cluster.yml ps` 里所有服务都是 `healthy` 就启动好了。

### 5. 换成正式证书（Let's Encrypt，免费）

集群跑起来后：

```bash
./deploy/issue-cert.sh shop.example.com 你的邮箱
```

证书 90 天有效。在服务器上加一个每周执行的定时任务自动续期（未到期时不会重复申请）：

```bash
crontab -e
# 加一行（路径换成你的项目目录）：
0 4 * * 1 cd /path/to/storm-shop && ./deploy/issue-cert.sh shop.example.com 你的邮箱 >> /var/log/storm-cert.log 2>&1
```

## 安全设置说明

- **HTTP 自动跳转 HTTPS**，应用会发送 HSTS 头：浏览器一年内只用 HTTPS 访问本站。
- **会话 Cookie** 只通过 HTTPS 发送、JavaScript 读不到、跨站请求不带。正式环境如果临时只能用 HTTP 访问，登录会失败，可以设 `SESSION_COOKIE_SECURE=false` 临时放开（不建议长期这样）。
- **内容安全策略（CSP）**：页面只允许运行本站的脚本文件，被注入的恶意脚本不会执行。以后接入第三方脚本（如客服、统计）需要放行它的域名时，用 `SECURITY_CSP` 覆盖整条策略，默认值见 `SecurityConfig.DEFAULT_CSP`。
- **监控指标** `/actuator/prometheus` 不通过 nginx 对公网开放；健康检查 `/actuator/health` 可以访问。

## 更换密码和密钥

怀疑泄露时立即更换；没有泄露也建议每 3～6 个月换一次后台密码和数据库密码。

| 要换的 | 做法 |
| --- | --- |
| 后台密码 `ADMIN_PASSWORD` | 改文件内容 → `docker compose -f docker-compose.cluster.yml up -d --force-recreate shop-1 shop-2` |
| Stripe / PayPal / Claude / 邮箱密钥 | 先在对应平台后台生成新密钥，改文件，按上一行重启两个应用节点，确认正常后再到平台后台作废旧密钥 |
| 数据库密码 `DB_PASSWORD` | 先改 MySQL 里的密码：`docker compose -f docker-compose.cluster.yml exec mysql mysql -uroot -p"$(cat secrets/MYSQL_ROOT_PASSWORD)" -e "ALTER USER 'shop'@'%' IDENTIFIED BY '新密码';"`，再把新密码写进文件，重启两个应用节点 |
| Redis 密码 `REDIS_PASSWORD` | 改文件后 `up -d --force-recreate redis shop-1 shop-2`（会话会失效，顾客需要重新登录） |

重启时两个节点最好一个一个来（先 `shop-1`，健康后再 `shop-2`），nginx 会把请求转给还在运行的那个，顾客基本感觉不到。

## 常见问题

- **`secret file not found`**：没运行 `./deploy/init-secrets.sh`，或者在别的目录执行了 docker compose。
- **nginx 起不来，提示找不到证书**：`deploy/certs/` 里没有 `fullchain.pem` 和 `privkey.pem`，先运行第 3 步。
- **两个节点都是 unhealthy**：`docker compose -f docker-compose.cluster.yml logs shop-1` 看错误，最常见的是数据库或 Redis 密码文件内容不对。
