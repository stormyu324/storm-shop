# Storm 商城

基于 Spring Boot 的电商网站，包含**商城前台**和**商品管理后台**，重点是让商品上架尽量省事。

## 快速开始

只需要安装 **JDK 21 或更高版本**（不用装 Maven 和数据库）。没有的话到 https://adoptium.net 下载 Temurin 21 安装；macOS 也可以 `brew install openjdk@21`。

下载代码（或在 GitHub 页面点 Code → Download ZIP 解压）：

```bash
git clone https://github.com/stormyu324/storm-shop.git
```

### 一键启动

| 系统 | 操作 |
| --- | --- |
| **Windows** | 双击 `start.bat` |
| **macOS** | 双击 `start.command`（第一次如果提示“无法验证开发者”，右键 → 打开） |
| Linux / 终端 | `./start.sh` |

启动脚本会检查 Java 版本、启动商城，并在启动完成后自动打开浏览器。第一次运行要下载依赖，需要几分钟。

- 商城首页：http://localhost:8080
- 管理后台：http://localhost:8080/admin （默认账号 `admin` / `admin123`）

停止：关掉命令行窗口，或按 `Ctrl + C`。首次启动会自动写入几条示例商品（`DEMO_DATA=false` 可关闭）。

### 数据保存与备份

默认使用自带的数据库（H2），数据都在项目文件夹里，不会上传到 GitHub，拉取新代码也不会覆盖
（换成 MySQL 后数据在 MySQL 里，见下一节）：

| 内容 | 位置 |
| --- | --- |
| 商品、订单、分类、运费、优惠券、会员、员工账号、操作记录 | `data/shop.mv.db` |
| 后台上传的商品图片 | `uploads/` |

**一键备份**：Windows 双击 `backup.bat`，macOS 双击 `backup.command`（Linux：`./backup.sh`）。
商城运行中也可以备份，不用先关掉。每次生成一个 `backups/storm-backup-日期-时间.zip`，
里面有数据库、图片和恢复说明，默认只保留最近 30 份。在 `config.env` 里可以改：

- `BACKUP_DIR`：备份保存位置，设成 OneDrive / iCloud / 百度网盘的同步文件夹，就自动有了异地备份
- `BACKUP_KEEP`：保留几份，`0` 表示全部保留

`config.env` 里有密码和密钥，不会放进备份，请另外保存一份。
换成 MySQL 后同一个脚本会改用 MySQL 自带的 `mysqldump` 导出（找不到时在 `config.env` 里用 `MYSQLDUMP` 指定路径）。
用正式环境配置（`SPRING_PROFILES_ACTIVE=prod`）运行时，数据库不允许其他程序同时打开，需要先关掉商城再备份。

**恢复**：关掉商城 → 把现有的 `data` 文件夹改名留底 → 新建 `data`，把备份里 `database.zip` 中的
`shop.mv.db` 解压进去 → 用备份里的 `uploads` 覆盖原来的 → 重新启动。备份包里的“恢复说明.txt”有同样的步骤
（MySQL 备份的恢复步骤也写在里面）。

### 换成 MySQL（可选）

在自己电脑上用，自带数据库加定期备份就够了；放到服务器上长期营业时建议换成 MySQL（8.0 或更高版本）。

1. **安装 MySQL**：Windows 用官方安装程序（dev.mysql.com/downloads/installer）；macOS 用 `brew install mysql`，
   再 `brew services start mysql`。
2. **建数据库和账号**：用 MySQL 命令行或 Workbench 以 root 登录，执行（密码换成你自己的）：

   ```sql
   CREATE DATABASE shop CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
   CREATE USER 'shop'@'localhost' IDENTIFIED BY '换成你的密码';
   GRANT ALL PRIVILEGES ON shop.* TO 'shop'@'localhost';
   ```

   `utf8mb4` 才能正常保存中文和表情；`utf8mb4_bin` 和自带数据库一样区分大小写（SKU `abc-1` 和 `ABC-1` 是两个商品）。
3. **在 `config.env` 里填三行**：

   ```
   DB_URL=jdbc:mysql://localhost:3306/shop
   DB_USERNAME=shop
   DB_PASSWORD=换成你的密码
   ```

4. **搬原来的数据**：关掉商城，双击 `migrate-to-mysql.bat`（macOS：`migrate-to-mysql.command`，Linux：`./migrate-to-mysql.sh`），
   输入 `y` 回车。程序会建好 MySQL 的表、复制全部数据并核对行数，最后列出每张表的行数后自动停止。
   - 只能迁移到**空**数据库，不会覆盖已有数据；出错时 MySQL 里不会留下数据。
   - 原来的 `data` 文件夹只读取不修改，可以留作备份。
   - 新开店、不需要原来的数据时跳过这一步，直接启动即可。
5. **正常启动**：以后双击启动脚本就会使用 MySQL。

想换回自带数据库：在 `config.env` 里把这三行前面加 `#` 即可（换回后看到的是迁移前的数据，在 MySQL 期间产生的新订单不在里面）。

### 配置文件

把 `config.example.env` 复制一份，命名为 **`config.env`**，去掉需要的行前面的 `#` 填上值（后台密码、端口、AI 和 Stripe 的密钥等），启动脚本会自动读取。`config.env` 已加入 `.gitignore`，不会被提交。

也可以不用脚本，直接运行 `./mvnw spring-boot:run`（Windows：`mvnw.cmd spring-boot:run`），配置通过环境变量传入。

## 上架商品的几种方式

| 方式 | 适合场景 | 入口 |
| --- | --- | --- |
| 单个添加 | 日常上新 | 后台 → 商品 → 添加商品 |
| 保存并继续添加 | 连续录入多个商品，自动沿用上一个的分类 | 添加商品页底部按钮 |
| 复制商品 | 同款不同颜色/尺码，复制后改几项即可 | 商品列表 → 复制 |
| 表格批量导入 | 一次上架几十上百个商品，或批量改价、改库存 | 后台 → 批量导入 |
| 批量上架/下架 | 勾选多个商品一键上下架或删除 | 商品列表顶部 |

### 批量导入说明

1. 在「批量导入」页下载 CSV 模板，用 Excel / WPS 填写后另存为 CSV。
2. 列：`名称, SKU, 分类, 售价, 原价, 库存, 状态, 图片, 描述`，其中只有**名称**和**售价**必填。
3. **SKU 相同则更新、不同则新建**，所以同一张表可以反复导入用来改价、补库存。
4. 分类不存在会自动创建；多张图片网址用 `|` 分隔；UTF-8 和 GBK 编码都能识别。
5. 出错的行会列出行号和原因，其他行照常导入。

### 商品图片

- 添加/编辑商品时可一次选择或拖入多张图片，保存到 `./uploads`（`UPLOAD_DIR` 可修改）。
- 也可以直接粘贴网络图片地址；调整地址顺序即可更换主图。

## 跨境独立站 & 亚马逊 / SHEIN

### 英文前台（美元结算）

- 商城右上角点 **EN / 中文** 切换语言，选择会被记住；也可以直接访问 `/?lang=en`。
- 英文模式显示商品的英文标题、英文描述和英文卖点，价格和订单都用美元。英文资料没填时显示中文。
- 美元价格优先用商品里填写的“美元售价”；没填时按汇率 `CNY_PER_USD` 自动换算（默认 7.10）。
- 分类也可以在“分类”页填写英文名。

### 商品的跨境 / 平台资料

编辑商品时展开“跨境 / 平台资料”，可以填写英文标题、美元售价、品牌、款号（父 SKU）、条码（UPC/EAN）、颜色、尺码、材质、重量、包装尺寸、英文卖点和英文描述。都可以选填，也能通过批量导入一次性填写。

**款号**：同款不同颜色或尺码的商品（每个都是独立 SKU）填同一个款号，导出亚马逊表格时会自动生成父商品和变体关系。

商城前台也会把同一款号的商品合并成一个：列表里只显示一张卡片（价格不同时显示“¥xx 起”和规格数量），详情页可以点选颜色、尺码切换，售罄的组合会划线显示。

### AI 生成英文资料

设置环境变量 `ANTHROPIC_API_KEY`（在 https://console.anthropic.com 创建）后启用，使用 Claude 根据中文资料生成英文标题、5 条卖点和英文描述：

- **单个商品**：编辑商品时，在“跨境 / 平台资料”里点 **AI 生成英文标题、卖点和描述**，结果直接填进表单，检查修改后再保存。
- **批量补全**：后台 → 平台导出，点 **用 AI 补全缺少的英文资料**，为缺英文标题的商品生成内容（每次最多 20 个，只填空着的字段，不覆盖已有内容）。

提示词要求只使用商品资料里已有的事实，但 AI 仍可能出错，上架前请抽查，尤其是尺寸、材质等信息。每次生成按 Claude API 用量计费。默认模型为 `claude-opus-5-5`，可用环境变量 `AI_MODEL` 修改。

### AI 导购（推荐商品）

同样只要设置了 `ANTHROPIC_API_KEY`，商城前台会出现：

- **首页「AI 导购」**：顾客输入需求（例如“送妈妈的生日礼物，200 元以内”），AI 从在售商品里挑出最多 4 件，并说明推荐理由。
- **商品页「AI 推荐搭配」**：点了按钮才调用，推荐和当前商品搭配的其他商品（不会推荐同款的其他颜色尺码）。

AI 只能推荐店里真实存在、正在销售的商品；同样的问题 10 分钟内直接用缓存。为了控制费用，每个访客 10 分钟内最多问 10 次，全站每天最多 300 次，可以用下面的环境变量调整；`AI_RECOMMEND=false` 可以单独关闭导购而保留后台的英文资料生成。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AI_RECOMMEND` | `true` | 是否开启 AI 导购 |
| `AI_RECOMMEND_PER_CLIENT` | `10` | 每个访客 10 分钟内最多次数 |
| `AI_RECOMMEND_DAILY_LIMIT` | `300` | 全站每天最多次数 |

### 导出到平台

后台 → **平台导出**：

1. 先看“资料完整度”，把缺英文标题、品牌、条码、图片的商品补全。
2. 下载 **亚马逊表格**（列名按亚马逊库存文件字段名，价格为美元），或 **通用资料表**（SHEIN 等平台复制用；也能在 Excel 里改完后从“批量导入”导回本站）。
3. 在平台卖家后台下载对应类目的官方模板，把列复制过去，补上类目特有的必填项后上传。

> 导出的图片链接需要平台能访问到。网站部署上线后请设置 `PUBLIC_BASE_URL`（例如 `https://www.myshop.com`），否则图片链接会是 `localhost`。
> 平台的模板和规则会更新，请以卖家后台下载的最新模板为准。

## 其他功能

- 前台：分类浏览、搜索、价格排序、商品详情多图、购物车、下单（自动扣库存，库存不足或已下架时不能下单）
- 后台：数据概览（在售/草稿/库存紧张/待处理订单）、分类管理、订单管理
  - 订单状态：待处理 → 已发货 → 已完成；待处理或已发货（退货）可取消，取消时自动退回库存；已完成、已取消不能再改
- 删除商品或从商品中移除图片时，不再被任何商品使用的上传图片会自动从 `./uploads` 删除

> 英文前台的订单按美元记录，后台订单列表会显示币种。配置 Stripe 后美元订单可以在线付款（见下文）；没配置时，订单提交后由商家线下确认。

## 运费

后台 → **运费**，人民币（中文前台）和美元（英文前台）分别设置。顾客结账时选择寄往的国家 / 地区，运费会立即重新计算；购物车里先按上次选择的国家（默认英文前台美国、中文前台中国）估算。

- **国家分组**：例如「美国」US、「欧洲」DE, FR, GB…，每组单独设置运费。国家用两位代码填写，一个国家只能属于一个分组。
- **默认运费**：没有列进分组的国家用它。勾选“只配送到上面列出的国家”后，其他国家在结账页会提示暂不配送，无法下单。
- 每组运费都包括：**首重运费**（包含“首重重量”以内，默认 1000 克）、**续重**（超出部分每公斤加价，不足 1 公斤按 1 公斤，可留空）、**包邮门槛**（商品金额满多少免运费，购物车会提示“再买 xx 即可包邮”）。

重量取商品资料里的“重量（克）”，没填的按 0 克算。什么都不设置时全部包邮（和以前一样）。运费计入订单总额，Stripe 付款页里单独显示一行“Shipping”；订单会记录寄往的国家，后台订单列表可以看到。

## 在线支付（Stripe）

配置后，英文前台（美元）下单会跳转到 Stripe 的安全付款页，支持银行卡、Apple Pay / Google Pay 等方式（以 Stripe 账户开通的为准）：

1. 注册 https://stripe.com ，在 Developers → API keys 复制 **Secret key**，设置为环境变量 `STRIPE_SECRET_KEY`。先用测试模式的 `sk_test_...` 试，测试卡号 `4242 4242 4242 4242`，有效期填任意未来日期。
2. 在 Developers → Webhooks 添加端点 `https://你的域名/stripe/webhook`，勾选事件
   `checkout.session.completed`、`checkout.session.expired`、`checkout.session.async_payment_succeeded`、`checkout.session.async_payment_failed`，
   把生成的 **Signing secret**（`whsec_...`）设置为 `STRIPE_WEBHOOK_SECRET`。
   本地测试可以用 Stripe CLI：`stripe listen --forward-to localhost:8080/stripe/webhook`，它会打印要用的 `whsec_...`。
3. 部署在反向代理（Nginx 等）后面时设置 `PUBLIC_BASE_URL`，付款完成后才能正确跳回本站。

订单流程：

- 下单时库存先锁定，订单为“待付款”；付款成功后自动变为“已付款”，在后台“待发货订单”里处理。
- 顾客在付款页点返回、30 分钟内未付款、或付款失败，订单自动取消、库存退回，商品放回购物车。即使 webhook 没配好，系统每 10 分钟也会向 Stripe 核对一次超时订单。
- 已付款订单要退款时，请在 Stripe 后台操作，再在本站后台把订单改为“已取消”（库存会退回）。

默认只有美元订单走 Stripe；如果你的 Stripe 账户支持人民币收款，可以设置 `STRIPE_CURRENCIES=USD,CNY`。

## 管理后台（Vue）

管理后台地址 **/admin**，用 Vue 3 + Element Plus 编写，通过下面的 REST 接口读写数据：概览、商品（多图拖拽上传和排序、跨境资料、AI 生成英文资料、复制、批量上下架）、批量导入、平台导出、订单、分类、运费。

前端源码在 `admin-ui/`，打包好的文件已经放在 `src/main/resources/static/admin/` 并提交在仓库里，所以**平时启动和部署不需要 Node.js**。只有修改了 `admin-ui` 的代码，才需要重新打包：

```bash
./mvnw -Pui generate-resources
```

（Maven 会自动下载 Node 到 `admin-ui/node`，不用自己安装。）开发前端时也可以用热更新：先启动后端，再在 `admin-ui` 里运行 `npm install` 和 `npm run dev`，打开 http://localhost:5173/admin/ ，接口会自动转发到 8080 端口。

## 接口（REST API）

除了网页，系统还提供一套 JSON 接口，可用于 App、小程序或单独开发的前端。启动后浏览器打开 **http://localhost:8080/swagger-ui.html** 查看接口文档并在线调试。

- **公开接口** `/api/...`：商品列表 / 详情（含颜色尺码规格）、分类、国家列表、运费试算、下单（开通 Stripe 时返回付款链接）。语言用 `lang=zh|en`，币种用 `currency=CNY|USD`。
- **后台接口** `/api/admin/...`：商品增删改查、图片上传、CSV 导入、批量上下架、订单、分类、运费、数据概览、AI 生成英文资料。
  登录方式：`GET /api/admin/session` 获取 CSRF 令牌（Cookie `XSRF-TOKEN`）→ `POST /api/admin/session` 登录 → 之后修改类请求在请求头 `X-XSRF-TOKEN` 带上令牌。在接口文档页调试时，先在另一个标签页登录 `/admin` 后台即可。
- 出错统一返回 `{"message": "...", "fields": {...}}`，状态码：400 格式或字段错误、401 未登录、403 无权限或缺 CSRF 令牌、404 不存在、409 数据刚被修改、422 业务规则不允许（如库存不足）。

正式环境模式（`SPRING_PROFILES_ACTIVE=prod`）默认不公开接口文档，需要时设置 `API_DOCS=true`。

## 会员、订单服务与运营

- **顾客查单与售后**（`/orders/lookup`）：凭订单号和下单邮箱收验证码查看订单、物流，并申请售后退款。后台订单页可填写物流单号、审核售后，在线支付的订单自动通过 Stripe 退款。详见 [docs/order-service.md](docs/order-service.md)。
- **会员**（`/account`）：邮箱验证码登录，保存收货地址、收藏商品、查看历史订单。
- **优惠券**：后台“优惠券”页创建满减 / 折扣码，结账页可试算。
- **运营数据**：后台查看下单数、收款、退款、热销商品。
- **员工权限**：后台“员工权限”页给员工开账号，分为管理员、商品管理、客服、运营四种角色；所有后台修改操作记录在“操作记录”页。

验证码和订单通知需要配置发信邮箱（SMTP），见 `config.example.env` 里的“邮件”部分。没配置时查单和会员登录不可用，其他功能照常。更多说明见 [docs/phase-two.md](docs/phase-two.md)。

## 配置（环境变量）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | 后台账号，**上线前一定要修改密码** |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 本地 H2 文件 `./data/shop` | 换成 MySQL 时修改，见“换成 MySQL” |
| `BACKUP_DIR` / `BACKUP_KEEP` | `./backups` / `30` | 一键备份的保存位置和保留份数 |
| `MYSQLDUMP` | 自动查找 | 用 MySQL 时，备份用的 mysqldump 路径 |
| `UPLOAD_DIR` | `./uploads` | 图片保存目录 |
| `PORT` | `8080` | 端口 |
| `DEMO_DATA` | `true` | 空库时是否写入示例数据 |
| `CNY_PER_USD` | `7.10` | 美元价格未填写时的换算汇率（1 美元 = 多少人民币） |
| `PUBLIC_BASE_URL` | 空（使用当前访问地址） | 网站对外网址，导出平台表格时用于拼完整图片链接 |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` | 空（不启用） | Stripe 在线支付的密钥和 webhook 签名密钥 |
| `STRIPE_CURRENCIES` | `USD` | 哪些币种的订单走 Stripe 付款 |
| `ANTHROPIC_API_KEY` | 空（不启用） | Claude API 密钥，启用 AI 生成英文资料 |
| `AI_MODEL` | `claude-opus-5-5` | AI 生成使用的模型 |

## 打包部署

```bash
./mvnw package
SPRING_PROFILES_ACTIVE=prod ADMIN_PASSWORD=你的强密码 java -jar target/shop-0.1.0.jar
```

`prod` 模式与本地开发的区别：

- 后台密码必须至少 8 位且不是默认的 `admin123`，否则**拒绝启动**（开发模式只打印警告）
- 不写入示例商品、开启模板缓存、不开放 H2 的 TCP 端口、错误页不显示异常细节
- 正式运营建议换成 MySQL（见“换成 MySQL”）；数据库表结构由 Flyway 迁移脚本管理（`src/main/resources/db/migration`），启动时自动升级
- 会话 Cookie 只通过 HTTPS 发送，**正式环境必须配置 HTTPS**（临时用 HTTP 访问时设 `SESSION_COOKIE_SECURE=false`）
- 部署在 Nginx 等反向代理后面时，程序从 `X-Forwarded-For` 读取顾客的真实 IP（验证码、AI 导购、下单的限流按每位顾客计算），
  只信任本机和内网地址的代理。Nginx 里加上 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` 和
  `proxy_set_header X-Forwarded-Proto $scheme;`。代理在公网上（例如 CDN 直接连到本程序）时，
  启动参数加 `--server.tomcat.remoteip.trusted-proxies=代理地址的正则`，否则所有顾客会共用代理的限流额度

### 集群部署（服务器长期营业）

一台服务器上跑 Nginx（HTTPS）+ 2 个应用节点 + MySQL + Redis，密码和密钥用文件管理、不写在环境变量里。
步骤、HTTPS 证书申请和续期、更换密钥的方法见 [docs/deploy-cluster.md](docs/deploy-cluster.md)。

## 测试

```bash
./mvnw test
```

默认用内存数据库。在 MySQL 上跑全部测试（先建一个空的 `shop_test` 库，建法同上）：

```bash
./mvnw test -Dspring.datasource.url=jdbc:mysql://localhost:3306/shop_test -Dspring.datasource.username=shop -Dspring.datasource.password=你的密码
```
