package com.javastorm.shop.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 让 Prometheus 用固定令牌抓取监控指标：请求头 {@code Authorization: Bearer <METRICS_TOKEN>}。
 * 只对 /actuator/prometheus 生效、只授予 METRICS 角色、不创建会话；未配置令牌时不放行任何请求（仍可用管理员登录访问）。
 */
@Component
public class MetricsTokenFilter extends OncePerRequestFilter {

    static final String PATH = "/actuator/prometheus";

    private final byte[] token;

    public MetricsTokenFilter(@Value("${shop.metrics.token:}") String token) {
        this.token = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token == null || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8))) {
            var auth = UsernamePasswordAuthenticationToken.authenticated(
                    "prometheus", null, AuthorityUtils.createAuthorityList("ROLE_METRICS"));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
