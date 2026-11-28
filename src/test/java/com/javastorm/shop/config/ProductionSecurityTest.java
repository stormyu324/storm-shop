package com.javastorm.shop.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 上线安全：安全响应头、页面不含内联脚本（内容安全策略才能生效）、密码可以从密钥文件读取。 */
@SpringBootTest(properties = "SECRETS_DIR=${java.io.tmpdir}/storm-shop-test-secrets/")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionSecurityTest {

    static final Path SECRETS = Path.of(System.getProperty("java.io.tmpdir"), "storm-shop-test-secrets");

    static {
        // 和 Docker secrets 一样：一个文件一个值，文件名是变量名（包括带点的属性名）
        try {
            Files.createDirectories(SECRETS);
            Files.writeString(SECRETS.resolve("ADMIN_TOTP_SECRET"), "JBSWY3DPEHPK3PXP\n");
            Files.writeString(SECRETS.resolve("spring.mail.password"), "mail-pass-from-file");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired Environment env;

    @Test
    void storefrontAndApiSendSecurityHeaders() throws Exception {
        for (String path : List.of("/", "/api/products")) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", SecurityConfig.DEFAULT_CSP))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(header().exists("Permissions-Policy"));
        }
    }

    @Test
    void hstsOnlyOnHttps() throws Exception {
        mvc.perform(get("/").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
        mvc.perform(get("/"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void scriptsOnlyFromOwnSite() {
        assertThat(SecurityConfig.DEFAULT_CSP).contains("script-src 'self';")
                .doesNotContain("unsafe-eval")
                .contains("frame-ancestors 'none'")
                .contains("form-action 'self' https://checkout.stripe.com https://*.paypal.com");
    }

    /** CSP 只允许本站脚本文件：模板里一旦出现内联脚本或 onclick 这类内联事件，浏览器会直接拦掉，功能失效。 */
    @Test
    void templatesHaveNoInlineScriptsOrHandlers() throws IOException {
        Pattern inline = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>|\\son[a-z]+\\s*=|javascript:",
                Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            List<String> offenders = files.filter(f -> f.toString().endsWith(".html"))
                    .flatMap(f -> {
                        try {
                            String html = Files.readString(f, StandardCharsets.UTF_8);
                            return inline.matcher(html).find() ? Stream.of(f.toString()) : Stream.empty();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).as("这些模板含内联脚本，会被内容安全策略拦截").isEmpty();
        }
    }

    @Test
    void secretsAreReadFromFilesWithTrailingNewlineTrimmed() {
        assertThat(env.getProperty("shop.admin.totp-secret")).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(env.getProperty("spring.mail.password")).isEqualTo("mail-pass-from-file");
    }
}
