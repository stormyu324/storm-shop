package com.javastorm.shop.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 网站对外地址：优先用配置的 PUBLIC_BASE_URL（部署在反向代理后面时必需），否则用当前请求的地址。 */
@Component
public class SiteUrls {

    private final String publicBaseUrl;

    public SiteUrls(@Value("${shop.public-base-url:}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(publicBaseUrl);
    }

    /** 不带结尾斜杠，例如 https://www.myshop.com */
    public String baseUrl() {
        if (isConfigured()) {
            return publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    public static boolean isLocal(String url) {
        return url.toLowerCase(java.util.Locale.ROOT)
                .matches("^https?://(localhost|127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.).*");
    }
}
