# 订单服务功能

顾客入口为 /orders/lookup，后台入口为 /admin/orders 的“物流与售后”。

## 邮件配置

通过部署环境配置 SPRING_MAIL_HOST、SPRING_MAIL_PORT、SPRING_MAIL_USERNAME、SPRING_MAIL_PASSWORD、
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH、SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE 和 SHOP_MAIL_FROM。
可配置 SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT、SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT、
SPRING_MAIL_PROPERTIES_MAIL_SMTP_WRITETIMEOUT 为 5000，单位毫秒。
邮件账号和发件人需由邮件供应商授权，域名需按其要求设置 SPF/DKIM。

队列与订单更新在同一数据库事务保存，每 30 秒发送，失败指数退避，最多尝试 8 次。
发送成功但数据库提交失败时，邮件可能重复投递。超过重试次数的邮件保留在 order_mail 表，需运营核查。
SMTP 尚未配置时，订单通知仍排队，邮箱验证码请求返回服务不可用。
开发和自动测试使用模拟邮件发送器，不向真实邮箱发送。

## 顾客查询

结算页新增可选邮箱字段，API /api/orders 新增可选 email 字段。
旧订单和未填写邮箱的订单由后台客服处理。填写邮箱后，顾客使用订单号和邮箱申请验证码。
验证码 10 分钟有效，最多验证 5 次，成功后单次失效，订单查看权限保持 30 分钟。
请求发送验证码时回复统一文案，避免暴露订单是否存在。验证码接口按来源 IP 限制。
顾客可查询金额、商品、订单状态、物流、售后记录并发起申请。
所有修改接口要求 CSRF 令牌，查询权限限定到验证过的订单。
通知和验证码使用中英文内容，前台查询页面支持中英文。

## 发货与售后

后台订单“物流与售后”支持填写承运商、运单号和 HTTPS 查询链接。
批准退款以整单总额（含运费）处理。在线支付进入 PROCESSING，后台定时使用 Stripe Refund API 创建退款或查询进度。
每个申请使用稳定的 Stripe 幂等标识，退款状态为 succeeded 才将订单改为 REFUNDED。
FAILED 申请需核查 Stripe 后由后台重试，待处理申请阻止发货和重复售后。
线下支付进入 MANUAL_REQUIRED，运营填写已完成退款的凭据后确认完成。
未发货订单退款成功自动退库存。已发货订单由运营验收退货后调整库存。
审核、驳回、退款处理状态及说明保存在售后历史表。

生产使用前应使用测试 SMTP 与 Stripe 测试模式进行联调。
当前物流由商家填写查询链接，承运商实时轨迹需要后续接入对应查询服务。
退款与邮件工作器需要在持续运行的应用实例中执行。
