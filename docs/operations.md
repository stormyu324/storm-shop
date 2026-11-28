# 运维手册（集群部署）

部署步骤见 [deploy-cluster.md](deploy-cluster.md)。本文说明上线以后的日常运维：备份与恢复、监控告警、查日志，以及每条告警收到后怎么处理。

以下命令都在项目目录执行，`dc` 代表 `docker compose -f docker-compose.cluster.yml`（可以在服务器上设置别名：`alias dc='docker compose -f docker-compose.cluster.yml'`）。

## 备份

`backup` 服务随集群一起启动，每天 03:30（北京时间）自动备份一次，保存到项目目录的 `backups/`：

```
backups/storm-20261128-033000/
  database.sql.gz   数据库（营业中也能拿到一致的快照）
  uploads.tar.gz    商品图片
  manifest.txt      备份时间、表清单、图片数量
  SHA256SUMS        校验文件是否损坏
```

- **时间和保留天数**：在 `.env` 里设置 `BACKUP_AT=03:30`、`BACKUP_KEEP_DAYS=14`、`TZ=Asia/Shanghai`，然后 `dc up -d backup`
- **立即备份一次**（例如升级前）：`dc run --rm backup --once`
- **备份失败会告警**：超过 26 小时没有成功备份时会发出 `BackupTooOld` 告警
- 备份文件只有 root 能读（里面有顾客的姓名、电话、地址），查看要用 `sudo`

### 异地备份（强烈建议）

备份和商城在同一台服务器上，服务器坏了会一起丢。请把 `backups/` 每天同步到别处，例如用 [rclone](https://rclone.org) 同步到阿里云 OSS、腾讯云 COS、Cloudflare R2 或 AWS S3：

```bash
# 先 rclone config 配置一个名为 offsite 的存储，然后在 crontab 里加一行（备份完成后同步）：
30 4 * * * sudo rclone sync /path/to/storm-shop/backups offsite:storm-shop-backups --exclude '.storm-*.partial/**'
```

`secrets/` 目录不在备份里，请另外保存（例如密码管理器）。

### 恢复演练（每月一次）

```bash
./deploy/restore-drill.sh
```

把最近一次备份恢复到一个临时数据库里，核对表、订单数量和图片数量，结束后自动删除临时数据库。**不影响正在运行的商城**，营业时间也可以做。输出最后一行是「演练通过」才说明备份真的能用；同时会显示恢复用时，可以据此估计真出事时要停业多久。

### 真正恢复

```bash
./deploy/restore-cluster.sh backups/storm-20261128-033000
```

会**覆盖当前的数据库和商品图片**，需要输入 `RESTORE` 确认。脚本会先自动备份一次当前状态，万一恢复错了，可以用那份备份再恢复回来。恢复期间商城停止服务（通常几分钟）。

## 监控和告警

启动监控（Prometheus 指标 + Alertmanager 告警 + Loki 日志 + Grafana 面板）：

```bash
dc --profile monitoring up -d
```

### 设置告警邮箱

编辑 `deploy/monitoring/alertmanager.yml`，把 `smtp.example.com`、`shop@example.com`、`owner@example.com` 改成你的 SMTP 服务器、发件邮箱和接收告警的邮箱；SMTP 密码用 `secrets/SPRING_MAIL_PASSWORD`（和商城发通知邮件用同一个邮箱）。改完 `dc --profile monitoring restart alertmanager`。

想推送到企业微信、钉钉、飞书或 Slack，在同一个文件的 `webhook_configs` 里配置机器人的 Webhook 地址。

### 打开监控面板

Grafana、Prometheus 只在服务器本机监听，不对公网开放。在自己电脑上通过 SSH 隧道访问：

```bash
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 你的用户名@服务器地址
```

然后浏览器打开 http://localhost:3000 ，用户名 `admin`，密码在服务器的 `secrets/GRAFANA_ADMIN_PASSWORD`。「Storm 商城 / Storm 商城概览」面板包括：今日订单、待付款、对账异常、退款待处理、积压邮件、距上次备份；请求量、错误、响应时间、最慢的接口；内存、数据库连接池、外部服务熔断；以及所有节点的错误日志。

### 查日志

Grafana 左侧 Explore → 数据源选 Loki：

| 想查什么 | 查询语句 |
| --- | --- |
| 所有应用错误 | `{service=~"shop-.*", level="ERROR"}` |
| 某个订单号相关的日志 | `{service=~"shop-.*"} \|= "202611281223355146"` |
| 一次请求的完整日志（报错页面或日志里有 traceId） | `{service=~"shop-.*"} \| json \| traceId="这里填 traceId"` |
| 支付相关 | `{service=~"shop-.*"} \|~ "(?i)stripe\|paypal"` |
| 备份任务 | `{service="backup"}` |

日志保留 30 天。

## 告警处理手册

| 告警 | 级别 | 含义 | 怎么处理 |
| --- | --- | --- | --- |
| ShopAllNodesDown | 紧急 | 两个应用节点都连不上，商城打不开 | `dc ps` 看状态，`dc logs --tail 200 shop-1` 看错误；常见原因：数据库没起来、磁盘满（`df -h`）、内存不足 |
| ShopNodeDown | 紧急 | 一个节点连不上，另一个在扛 | 同上；`dc up -d shop-1` 重新拉起 |
| HighServerErrorRate | 紧急 | 超过 5% 的请求报错 | Grafana 错误日志面板看最近的 ERROR，按 traceId 追踪 |
| SlowResponses | 一般 | 大多数请求超过 2 秒 | 面板「最慢的接口」看是哪个页面慢；同时看数据库连接池是否排队 |
| PaymentProviderCircuitOpen | 紧急 | Stripe / PayPal 连续出错，已暂停调用，顾客付不了款 | 先看支付平台的状态页（status.stripe.com、www.paypal-status.com）；平台正常的话检查密钥是否过期或被吊销。熔断 30 秒后会自动重试 |
| CheckoutFailures | 一般 | 多位顾客没能打开付款页，订单已自动取消 | 查支付相关日志；必要时联系这些顾客 |
| ReconciliationIssues | 紧急 | 每日对账发现钱和订单对不上（如已取消但已收款） | 后台「支付对账」页逐条核对，在支付平台退款或补发货后标记已处理 |
| RefundsNeedAttention | 一般 | 有退款失败或需要人工线下退款 | 后台订单「物流与售后」处理 |
| MailBacklog | 一般 | 邮件积压，顾客收不到通知和验证码 | 检查 SMTP 配置；邮箱服务商可能限制了每天的发信量 |
| MailDeliveryFailed | 一般 | 有邮件重试 8 次仍失败，已放弃 | 查邮件相关日志，必要时人工联系顾客 |
| BackupTooOld / BackupNeverSucceeded | 紧急 | 超过 26 小时没有成功备份 | `dc logs --tail 100 backup` 看原因（常见：磁盘满、数据库密码改了没更新）；修好后 `dc run --rm backup --once` 立即补一次 |
| JvmHeapNearlyFull | 一般 | 节点内存快用完 | 观察是否持续增长；短期可重启该节点，长期给服务器加内存 |
| DatabaseConnectionsExhausted | 一般 | 请求在排队等数据库连接 | 在 `.env` 调大 `DB_POOL_MAX`（默认 20）；或检查 MySQL 慢查询 |
