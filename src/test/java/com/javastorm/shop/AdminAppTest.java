package com.javastorm.shop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理后台（Vue 单页应用，/admin）的托管：首页、前端路由深链接、静态文件、旧地址跳转。页面本身是公开的，数据走需要登录的接口。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAppTest {

    @Autowired MockMvc mvc;

    @Test
    void servesSpaForAllRoutes() throws Exception {
        mvc.perform(get("/admin")).andExpect(redirectedUrl("/admin/"));
        mvc.perform(get("/admin/")).andExpect(forwardedUrl("/admin/index.html"));
        // 试用期的 /console 地址跳到 /admin
        mvc.perform(get("/console")).andExpect(redirectedUrl("/admin/"));
        mvc.perform(get("/console/products/3")).andExpect(redirectedUrl("/admin/"));

        MvcResult index = mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"app\"></div>")))
                .andReturn();
        // 刷新任意前端页面（深链接）也返回同一个 index.html
        mvc.perform(get("/admin/products/12")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"app\"></div>")));
        // 旧版后台的地址（例如 /admin/products/5/edit、/admin/login）也返回后台页面，由前端路由处理
        mvc.perform(get("/admin/products/5/edit")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"app\"></div>")));
        mvc.perform(get("/admin/orders")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")));

        Matcher js = Pattern.compile("/admin/assets/index-[\\w-]+\\.js").matcher(index.getResponse().getContentAsString());
        assertThat(js.find()).as("index.html 引用了打包后的 js").isTrue();
        mvc.perform(get(js.group())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
    }

    @Test
    void adminDataNeedsLogin() throws Exception {
        mvc.perform(get("/admin/login")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/export/readiness")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/export/amazon.csv")).andExpect(status().isUnauthorized());
        // 旧版的表单登录、退出地址已经不存在
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/login")
                .param("username", "admin").param("password", "admin123")).andExpect(status().isForbidden());
    }
}
