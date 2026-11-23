package com.javastorm.shop.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
public class StaffFilterRegistration {
  @Bean
  FilterRegistrationBean<StaffSessionFilter> disabledStaffFilter(StaffSessionFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
