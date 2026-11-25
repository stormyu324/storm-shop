package com.javastorm.shop.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

/**
 * 热点读缓存配置：
 * <ul>
 *   <li>开启 {@code shop.redis.enabled=true} 时使用 Redis 集群缓存（分类 10 分钟、概览 30 秒、运营报表 5 分钟），大幅降低 MySQL 读压力；</li>
 *   <li>本地开发/测试未开 Redis 时自动降级为 {@link NoOpCacheManager}，零依赖且测试结果完全实时。</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    CacheManager cacheManager(@Value("${shop.redis.enabled:false}") boolean redisEnabled,
                              ObjectProvider<RedisConnectionFactory> factoryProvider) {
        RedisConnectionFactory factory = redisEnabled ? factoryProvider.getIfAvailable() : null;
        if (factory == null) {
            return new NoOpCacheManager();
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("storm:cache:")
                .disableCachingNullValues()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                "categories", base.entryTtl(Duration.ofMinutes(10)),
                "dashboard", base.entryTtl(Duration.ofSeconds(30)),
                "analytics", base.entryTtl(Duration.ofMinutes(5))
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
