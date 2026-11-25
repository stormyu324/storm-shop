package com.javastorm.shop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.config.LoginThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证开启 Redis 模式时，限流、登录防爆破与下单幂等守卫均正确走 Redis 分布式路径。 */
class RedisStateSupportTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC);

    record SampleOrder(String orderNo, int total) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimiterDelegatesToRedisLuaScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L, 1L, 2L);

        RateLimiter limiter = new RateLimiter(10, 300, FIXED_CLOCK, "ai", redis);
        assertThat(limiter.tryAcquire("client-1")).isNull();
        assertThat(limiter.tryAcquire("client-1")).isEqualTo("error.ai.tooMany");
        assertThat(limiter.tryAcquire("client-1")).isEqualTo("error.ai.dailyLimit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void orderSubmissionGuardUsesRedisLockAndCachesResult() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        // 1) 首次提交：缓存未命中 -> 抢锁成功 -> 限流放行 -> 执行下单 -> 写缓存 -> 释放锁
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        ObjectMapper mapper = new ObjectMapper();
        OrderSubmissionGuard guard = new OrderSubmissionGuard(FIXED_CLOCK, redis, mapper);
        AtomicInteger calls = new AtomicInteger();

        SampleOrder first = guard.submit("sess-1", "1.2.3.4", "k1", "fp-1", Duration.ofMinutes(10),
                SampleOrder.class, () -> new SampleOrder("ORD-" + calls.incrementAndGet(), 100));

        assertThat(first).isEqualTo(new SampleOrder("ORD-1", 100));
        assertThat(calls.get()).isEqualTo(1);
        verify(values).set(anyString(), anyString(), any(Duration.class));

        // 2) 重复提交命中 Redis 缓存：直接反序列化返回，不重复下单
        String storedJson = "{\"fingerprint\":\"fp-1\",\"payload\":\"{\\\"orderNo\\\":\\\"ORD-1\\\",\\\"total\\\":100}\"}";
        when(values.get(anyString())).thenReturn(storedJson);

        SampleOrder second = guard.submit("sess-1", "1.2.3.4", "k1", "fp-1", Duration.ofMinutes(10),
                SampleOrder.class, () -> new SampleOrder("ORD-" + calls.incrementAndGet(), 100));
        assertThat(second).isEqualTo(new SampleOrder("ORD-1", 100));
        assertThat(calls.get()).isEqualTo(1);

        // 3) 同一 Key 但指纹不同 -> 409 CONFLICT
        assertThatThrownBy(() -> guard.submit("sess-1", "1.2.3.4", "k1", "fp-DIFFERENT", Duration.ofMinutes(10),
                SampleOrder.class, () -> new SampleOrder("ORD-99", 999)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginThrottleUsesRedisZSetWhenEnabled() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard(anyString())).thenReturn(5L);

        // 通过反射或同包/Spring 注入构造器测试
        LoginThrottle throttle = new LoginThrottle(true, new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(Object... args) {
                return redis;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return redis;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return redis;
            }

            @Override
            public StringRedisTemplate getObject() {
                return redis;
            }
        });

        assertThat(throttle.isBlocked("10.0.0.1", "admin")).isTrue();
        throttle.recordFailure("10.0.0.1", "admin");
        throttle.recordSuccess("10.0.0.1", "admin");
        verify(redis).delete(anyString());
    }
}
