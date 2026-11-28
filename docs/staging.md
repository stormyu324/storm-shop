# 预发布环境（Staging）

预发布环境是正式环境的“彩排”：同样的代码、同样的部署方式，但用测试数据和支付平台的测试模式。
每次升级先部署到这里，跑冒烟测试和压测，确认没问题再升级正式环境；压测也只在这里做，不要压正式商城。

## 放在哪里

- **推荐：单独一台小服务器**（2 核 4G 即可），和正式环境完全隔离。
- **也可以和正式环境放在同一台服务器**：用不同的端口、目录和 Docker 项目名，互不影响。但压测时会和正式环境抢 CPU，最好在夜里没什么顾客时压。

下面按“同一台服务器”写；单独服务器时可以直接用默认端口和目录，省掉 `-p` 和 `--env-file`。

## 第一次搭建

```bash
cp .env.staging.example .env.staging       # 按需修改网址和端口
SECRETS_PATH=./secrets-staging ./deploy/init-secrets.sh
CERTS_PATH=./deploy/certs-staging ./deploy/gen-self-signed-cert.sh localhost
```

预发布用自签名证书就够了（浏览器提示不安全时手动继续）；要用域名访问时，也可以按 deploy-cluster.md 申请 Let's Encrypt 证书（加上 `CERTS_PATH` 和 `COMPOSE`）。

在 `secrets-staging/` 里填**测试用**的第三方密钥：

- Stripe：后台切到「测试模式」拿 `sk_test_...`，测试卡号 `4242 4242 4242 4242`
- PayPal：用 developer.paypal.com 的沙箱应用，`.env.staging` 里保持 `PAYPAL_SANDBOX=true`
- 邮件：建议用 Mailtrap 等测试邮箱，避免误发给真实顾客

后面的命令都用这个前缀（可以设成别名）：

```bash
alias stg='docker compose -f docker-compose.cluster.yml --env-file .env.staging -p storm-staging'
```

启动：

```bash
stg up -d --build
stg --profile monitoring up -d      # 可选
```

## 每次升级的流程

1. **部署到预发布**：`git pull && stg up -d --build`
2. **冒烟测试**：
   ```bash
   COMPOSE="docker compose -f docker-compose.cluster.yml --env-file .env.staging -p storm-staging" \
     SECRETS_PATH=./secrets-staging ./deploy/smoke-test.sh https://localhost:8443
   ```
3. **压测**（约 2 分钟，确认响应时间和错误率没有变差）：
   ```bash
   java tools/LoadTest.java --base https://localhost:8443 --insecure --users 50 --duration 120 \
     --admin-password-file secrets-staging/ADMIN_PASSWORD --report load-test.md
   ```
   报告里有每类请求的吞吐量和 95 分位响应时间，最后会核对库存和订单是否一致。和上一次的报告比较，明显变慢就先查原因。
4. **手动点一遍**：前台下单（用 Stripe 测试卡付款）、后台发货、退款。
5. 都没问题再升级正式环境：`git pull && dc up -d --build`，然后对正式环境只跑冒烟测试（**不压测**）：
   `SMOKE_STRICT_TLS=1 ./deploy/smoke-test.sh https://你的域名`

## 用正式数据彩排（可选）

想在升级前用真实数据量试一下（例如数据库表结构有变化时），可以把正式环境最近的备份恢复到预发布：

```bash
COMPOSE="docker compose -f docker-compose.cluster.yml --env-file .env.staging -p storm-staging" \
  ./deploy/restore-cluster.sh backups/storm-最近的备份
```

注意：恢复后预发布里有真实顾客的姓名、电话和地址，**必须关掉发信**（`secrets-staging/SPRING_MAIL_PASSWORD` 留空并重启），并且只给需要的人访问。用完建议重新初始化。

## 压测结果参考

CI 每次合并前都会在 GitHub 的服务器上启动整套集群压测一次（结果在 Actions 的 Summary 页）。下面是本机单节点开发模式（H2 数据库、模板不缓存）的结果，只能作为下限参考：

| 并发顾客 | 每秒请求 | 95 分位响应 | 每秒下单 | 错误 | 库存核对 |
| --- | ---: | ---: | ---: | ---: | --- |
| 50 | 约 740 | 227 毫秒 | 约 112 | 0 | 通过 |

日均 2,000 单平均每分钟不到 2 单，促销高峰按 50 倍算也只有每秒约 1.2 单，离上面的数字还有很大余量。真正的瓶颈要在预发布环境用接近正式的服务器配置压出来。
