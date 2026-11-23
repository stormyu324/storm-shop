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

/** 新版后台（Vue 单页应用）的托管：首页、前端路由深链接、静态文件。页面本身是公开的，数据走需要登录的接口。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsoleTest {

    @Autowired MockMvc mvc;

    @Test
    void servesSpaForAllRoutes() throws Exception {
        mvc.perform(get("/console")).andExpect(redirectedUrl("/console/"));
        mvc.perform(get("/console/")).andExpect(forwardedUrl("/console/index.html"));

        MvcResult index = mvc.perform(get("/console/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"app\"></div>")))
                .andReturn();
        // 刷新任意前端页面（深链接）也返回同一个 index.html
        mvc.perform(get("/console/products/12")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"app\"></div>")));
        mvc.perform(get("/console/orders")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")));

        Matcher js = Pattern.compile("/console/assets/index-[\\w-]+\\.js").matcher(index.getResponse().getContentAsString());
        assertThat(js.find()).as("index.html 引用了打包后的 js").isTrue();
        mvc.perform(get(js.group())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
    }

    @Test
    void oldAdminLinksToNewConsole() throws Exception {
        mvc.perform(get("/admin/login")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/export/readiness")).andExpect(status().isUnauthorized());
    }
}
