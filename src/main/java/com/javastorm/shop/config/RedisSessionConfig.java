package com.javastorm.shop.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * 开启 {@code shop.redis.enabled=true}（环境变量 {@code REDIS_ENABLED=true}）时启用 Spring Session Redis，
 * 实现多实例集群下的购物车、会员和后台登录会话共享；未开启时使用标准 Servlet Session，无需运行 Redis。
 */
@Configuration
@ConditionalOnProperty(name = "shop.redis.enabled", havingValue = "true")
@EnableRedisHttpSession(redisNamespace = "storm-shop:session")
public class RedisSessionConfig {
}
