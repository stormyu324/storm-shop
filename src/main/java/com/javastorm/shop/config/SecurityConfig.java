package com.javastorm.shop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** 前台所有人可访问；/admin/** 需要管理员登录。 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                // Stripe 服务器的通知没有 CSRF token，靠签名校验
                .csrf(csrf -> csrf.ignoringRequestMatchers("/stripe/webhook"))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .defaultSuccessUrl("/admin", true)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/"));
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
                                  PasswordEncoder encoder) {
        checkPassword(password, requireStrong);
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build());
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
