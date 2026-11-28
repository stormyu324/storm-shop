package com.javastorm.shop.config;

import com.javastorm.shop.repository.StaffRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.io.IOException;

/** 前台和后台页面所有人可访问；后台数据接口 /api/admin/** 需要管理员登录（登录接口见 AdminSessionApi）。 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * 默认内容安全策略：脚本只允许本站文件（页面里不能有内联脚本，防 XSS 注入后执行）；
     * 样式允许内联（后台 Element Plus 组件会动态插入样式）；图片允许 https 外链（商品图可能放在 CDN / 第三方）；
     * 表单提交后会跳转到 Stripe / PayPal 付款页，所以 form-action 放行这两家。可用 SECURITY_CSP 覆盖。
     */
    static final String DEFAULT_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; font-src 'self' data:; connect-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; object-src 'none'; "
            + "form-action 'self' https://checkout.stripe.com https://*.paypal.com";

    private final String contentSecurityPolicy;

    public SecurityConfig(@Value("${shop.security.csp:}") String csp) {
        this.contentSecurityPolicy = csp == null || csp.isBlank() ? DEFAULT_CSP : csp.trim();
    }

    /**
     * 两条规则链共用的安全响应头。HSTS 只在 HTTPS 请求上发送（经反向代理时依赖 X-Forwarded-Proto），
     * 告诉浏览器一年内只用 HTTPS 访问本站。
     */
    private void securityHeaders(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), payment=(self)")));
    }

    /**
     * 接口（/api/**）：未登录返回 401 JSON，不跳转登录页；CSRF 令牌放在 XSRF-TOKEN Cookie 里，
     * 前端（Vue 等）读取后放到请求头 X-XSRF-TOKEN。公开的下单、运费试算等接口不依赖登录，不需要 CSRF。
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, StaffSessionFilter staffSessions) throws Exception {
        http
                .securityMatcher("/api/**")
                .addFilterBefore(staffSessions, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/session").permitAll()
                        .requestMatchers("/api/admin/staff/**", "/api/admin/audit/**",
                                "/api/admin/orders/*/after-sales/*", "/api/admin/orders/*/offline-payment")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/admin/orders/**").hasAnyRole("ADMIN", "SUPPORT")
                        .requestMatchers("/api/admin/coupons/**", "/api/admin/analytics/**").hasAnyRole("ADMIN", "OPERATIONS")
                        // 商品管理：商品、图片上传、分类、运费、AI 英文资料、平台导出
                        .requestMatchers("/api/admin/products/**", "/api/admin/images", "/api/admin/categories/**",
                                "/api/admin/shipping/**", "/api/admin/ai/**", "/api/admin/export/**")
                        .hasAnyRole("ADMIN", "CATALOG")
                        .requestMatchers("/api/admin/dashboard").hasAnyRole("ADMIN", "STAFF")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/orders", "/api/shipping/quote", "/api/recommendations"))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> json(res, 401, "请先登录后台"))
                        .accessDeniedHandler((req, res, ex) -> json(res, 403,
                                "没有权限，或请求缺少 CSRF 令牌（请求头 X-XSRF-TOKEN）")))
                .requestCache(cache -> cache.disable());
        securityHeaders(http);
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 网页部分：商城前台全部公开；/admin 是后台的 Vue 页面（本身公开，数据都走需要登录的 /api/admin 接口）
        // /actuator/health 供负载均衡/K8s 探针访问；其余 /actuator/**（如 prometheus 指标）仅允许管理员访问
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                // Stripe 服务器的通知没有 CSRF token，靠签名校验
                .csrf(csrf -> csrf.ignoringRequestMatchers("/stripe/webhook"));
        securityHeaders(http);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    static final String DEFAULT_PASSWORD = "admin123";

    @Bean
    UserDetailsService adminUsers(@Value("${shop.admin.username}") String username,
                                  @Value("${shop.admin.password}") String password,
                                  @Value("${shop.admin.require-strong-password:false}") boolean requireStrong,
                                  PasswordEncoder encoder, StaffRepository staff) {
        checkPassword(password, requireStrong);
        InMemoryUserDetailsManager owner = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build());
        return loginName -> {
            if (owner.userExists(loginName)) {
                return owner.loadUserByUsername(loginName);
            }
            var account = staff.findByUsername(loginName).orElseThrow(() ->
                    new UsernameNotFoundException("账号不存在"));
            return User.withUsername(account.getUsername()).password(account.getPasswordHash())
                    .roles("STAFF", account.getRole()).disabled(!account.isEnabled()).build();
        };
    }

    /** 生产环境（prod）下拒绝用默认密码或过短的密码启动，开发环境只打印警告。 */
    /** 默认密码或少于 8 位。 */
    public static boolean isWeak(String password) {
        return password == null || password.length() < 8 || DEFAULT_PASSWORD.equals(password);
    }

    static void checkPassword(String password, boolean requireStrong) {
        if (!isWeak(password)) {
            return;
        }
        if (requireStrong) {
            throw new IllegalStateException("后台密码不安全：请用环境变量 ADMIN_PASSWORD 设置至少 8 位、且不是默认值的密码");
        }
        log.warn("正在使用默认或过短的后台密码，只能在本机登录后台；上线前请设置环境变量 ADMIN_PASSWORD");
    }
}
