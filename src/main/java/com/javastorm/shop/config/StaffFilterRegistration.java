package com.javastorm.shop.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StaffFilterRegistration {
    @Bean
    FilterRegistrationBean<StaffSessionFilter> disabledStaffFilter(StaffSessionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** 只在安全规则链里运行（见 SecurityConfig），不要再注册成普通 Servlet 过滤器。 */
    @Bean
    FilterRegistrationBean<MetricsTokenFilter> disabledMetricsTokenFilter(MetricsTokenFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
