package com.javastorm.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** 前台和后台页面所有人可访问；后台数据接口 /api/admin/** 需要管理员登录（登录接口见 AdminSessionApi）。 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * 接口（/api/**）：未登录返回 401 JSON，不跳转登录页；CSRF 令牌放在 XSRF-TOKEN Cookie 里，
     * 前端（Vue 等）读取后放到请求头 X-XSRF-TOKEN。公开的下单、运费试算等接口不依赖登录，不需要 CSRF。
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, StaffSessionFilter staffSessions) throws Exception {
        http
                .securityMatcher("/api/**")
                .addFilterBefore(staffSessions, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/session").permitAll()
                        .requestMatchers("/api/admin/staff/**","/api/admin/audit/**","/api/admin/orders/*/after-sales/*").hasRole("ADMIN")
                        .requestMatchers("/api/admin/orders/**").hasAnyRole("ADMIN","SUPPORT")
                        .requestMatchers("/api/admin/coupons/**","/api/admin/analytics/**").hasAnyRole("ADMIN","OPERATIONS")
                        // 商品管理：商品、图片上传、分类、运费、AI 英文资料、平台导出
                        .requestMatchers("/api/admin/products/**","/api/admin/images","/api/admin/categories/**","/api/admin/shipping/**","/api/admin/ai/**","/api/admin/export/**").hasAnyRole("ADMIN","CATALOG")
                        .requestMatchers("/api/admin/dashboard").hasAnyRole("ADMIN","STAFF")
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
        return http.build();
    }

    private static void json(jakarta.servlet.http.HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 网页部分：商城前台全部公开；/admin 是后台的 Vue 页面（本身公开，数据都走需要登录的 /api/admin 接口）
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Stripe 服务器的通知没有 CSRF token，靠签名校验
                .csrf(csrf -> csrf.ignoringRequestMatchers("/stripe/webhook"));
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
                                  PasswordEncoder encoder, com.javastorm.shop.repository.StaffRepository staff) {
        checkPassword(password, requireStrong);
        InMemoryUserDetailsManager owner = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build());
        return loginName -> {
            if(owner.userExists(loginName)) return owner.loadUserByUsername(loginName);
            var account=staff.findByUsername(loginName).orElseThrow(() ->
                    new org.springframework.security.core.userdetails.UsernameNotFoundException("账号不存在"));
            return User.withUsername(account.username).password(account.passwordHash)
                    .roles("STAFF",account.role).disabled(!account.enabled).build();
        };
    }

    /** 生产环境（prod）下拒绝用默认密码或过短的密码启动，开发环境只打印警告。 */
    static void checkPassword(String password, boolean requireStrong) {
        boolean weak = password == null || password.length() < 8 || DEFAULT_PASSWORD.equals(password);
        if (!weak) {
            return;
        }
        if (requireStrong) {
            throw new IllegalStateException("后台密码不安全：请用环境变量 ADMIN_PASSWORD 设置至少 8 位、且不是默认值的密码");
        }
        log.warn("正在使用默认或过短的后台密码，上线前请设置环境变量 ADMIN_PASSWORD");
    }
}
