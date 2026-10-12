package com.javastorm.shop.config;

import com.javastorm.shop.domain.Currency;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;

/**
 * 前台语言：中文（人民币结算）/ English（美元结算）。
 * 访问任意页面带上 ?lang=en 或 ?lang=zh 即可切换，选择会记在 cookie 里。
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("shop_lang");
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(interceptor);
    }

    public static boolean isEnglish() {
        return isEnglish(LocaleContextHolder.getLocale());
    }

    public static boolean isEnglish(Locale locale) {
        return "en".equals(locale.getLanguage());
    }

    public static Currency currentCurrency() {
        return isEnglish() ? Currency.USD : Currency.CNY;
    }
}
