package com.javastorm.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 开启 {@code shop.redis.enabled=true}（环境变量 {@code REDIS_ENABLED=true}）时启用 Spring Session Redis，
 * 实现多实例集群下的购物车、会员和后台登录会话共享；未开启时使用标准 Servlet Session，无需运行 Redis。
 */
@Configuration
@ConditionalOnProperty(name = "shop.redis.enabled", havingValue = "true")
@EnableRedisHttpSession(redisNamespace = "storm-shop:session")
public class RedisSessionConfig {

    /**
     * Spring Session 的会话 Cookie 不读 server.servlet.session.cookie.*，这里按同样的设置配置：
     * HttpOnly、SameSite=Lax；secure 未配置时按请求是否为 HTTPS 决定，正式环境强制只走 HTTPS。
     */
    @Bean
    CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:#{null}}") Boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        if (secure != null) {
            serializer.setUseSecureCookie(secure);
        }
        return serializer;
    }
}
