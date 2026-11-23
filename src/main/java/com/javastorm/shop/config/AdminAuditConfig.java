package com.javastorm.shop.config;

import com.javastorm.shop.domain.AuditEntry;
import com.javastorm.shop.repository.AuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

@Configuration
public class AdminAuditConfig implements WebMvcConfigurer {
    private final AuditRepository entries;

    public AdminAuditConfig(AuditRepository entries) {
        this.entries = entries;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
                .addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request, HttpServletResponse response, Object handler) {
                                var auth = SecurityContextHolder.getContext().getAuthentication();
                                if (auth != null) {
                                    request.setAttribute("auditActor", auth.getName());
                                }
                                return true;
                            }

                            @Override
                            public void afterCompletion(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler,
                                    Exception exception) {
                                if (!(handler instanceof HandlerMethod)
                                        || "GET".equals(request.getMethod())
                                        || "HEAD".equals(request.getMethod())) {
                                    return;
                                }
                                var auth = SecurityContextHolder.getContext().getAuthentication();
                                Object actor = request.getAttribute("auditActor");
                                if (auth != null
                                        && auth.isAuthenticated()
                                        && !"anonymousUser".equals(auth.getName())) {
                                    actor = auth.getName();
                                }
                                AuditEntry entry = new AuditEntry();
                                entry.setActor(actor == null ? "anonymous" : actor.toString());
                                entry.setAction(request.getMethod()
                                                + " "
                                                + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
                                Object variables =
                                        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                                entry.setTarget(variables instanceof Map ? variables.toString() : "");
                                entry.setResult(response.getStatus());
                                try {
                                    entries.save(entry);
                                } catch (DataAccessException e) {
                                    LoggerFactory.getLogger(AdminAuditConfig.class).error("无法保存后台操作记录");
                                }
                            }
                        })
                .addPathPatterns("/api/admin/**");
    }
}
