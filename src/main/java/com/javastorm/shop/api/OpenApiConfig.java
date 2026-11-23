package com.javastorm.shop.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 接口文档：浏览器打开 /swagger-ui.html 查看和调试，JSON 格式在 /v3/api-docs。 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI shopOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Storm 商城接口")
                .version("v1")
                .description("""
                        **公开接口**（/api/...）：商品、分类、运费试算、下单、AI 推荐，App / 小程序 / 前端都可以直接用。
                        语言用 `lang=zh|en` 参数切换；价格币种用 `currency=CNY|USD`，不传时中文为人民币、英文为美元。

                        **后台接口**（/api/admin/...）：需要先登录。
                        1. `GET /api/admin/session` 获取 CSRF 令牌（同时写入 Cookie `XSRF-TOKEN`）；
                        2. `POST /api/admin/session` 登录（用户名、密码），登录状态保存在 Cookie 会话里；
                        3. 之后所有修改类请求（POST / PUT / DELETE）都要在请求头 `X-XSRF-TOKEN` 里带上令牌。

                        在本页面调试后台接口：先在浏览器另一个标签页登录 /admin 后台，再回来点 “Try it out”。

                        出错时统一返回 `{"message": "...", "fields": {...}}`：400 请求格式或字段有误，401 未登录，
                        403 无权限或缺少 CSRF 令牌，404 不存在，409 数据刚被修改，422 业务规则不允许（如库存不足）。"""));
    }
}
