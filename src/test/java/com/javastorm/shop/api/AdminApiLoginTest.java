package com.javastorm.shop.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实的后台登录流程（Cookie 里的 CSRF 令牌）。
 * 单独一个 Spring 上下文：其他测试用的 csrf() 测试工具会替换掉 CSRF 令牌仓库，影响这里要验证的真实 Cookie。
 */
@SpringBootTest(properties = "test.context=real-csrf")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiLoginTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;

    @Test
    void loginFlowWithCookieCsrf() throws Exception {
        mvc.perform(get("/api/admin/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录后台"));

        MockHttpSession session = new MockHttpSession();
        MvcResult first = mvc.perform(get("/api/admin/session").session(session))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andReturn();
        Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        String token = JSON.readTree(first.getResponse().getContentAsString()).path("csrfToken").asText();
        assertThat(token).isEqualTo(xsrf.getValue());

        String login = "{\"username\":\"admin\",\"password\":\"admin123\"}";
        // 没带 CSRF 令牌：403
        mvc.perform(post("/api/admin/session").session(session).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        // 密码错误：401
        mvc.perform(post("/api/admin/session").session(session).cookie(xsrf).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        MvcResult ok = mvc.perform(post("/api/admin/session").session(session).cookie(xsrf).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                .andReturn();
        MockHttpSession loggedIn = (MockHttpSession) ok.getRequest().getSession();

        mvc.perform(get("/api/admin/dashboard").session(loggedIn)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/categories").session(loggedIn).cookie(xsrf).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"数码\",\"sortOrder\":1}"))
                .andExpect(status().isCreated());
        // 后台页面（Vue）和接口用同一个会话
        mvc.perform(get("/admin/products").session(loggedIn)).andExpect(status().isOk());

        mvc.perform(delete("/api/admin/session").session(loggedIn).cookie(xsrf).header("X-XSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/dashboard").session(loggedIn)).andExpect(status().isUnauthorized());
    }

}
