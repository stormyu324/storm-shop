package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void limitsPerClientWindowAndPerDay() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneId.systemDefault(); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        RateLimiter limiter = new RateLimiter(2, 3, clock);
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("a")).isEqualTo("error.ai.tooMany");
        assertThat(limiter.tryAcquire("b")).isNull();
        assertThat(limiter.tryAcquire("c")).isEqualTo("error.ai.dailyLimit");

        // 第二天重新计数；10 分钟窗口过后同一访客又可以问
        now.set(now.get().plus(Duration.ofDays(1)));
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("a")).isEqualTo("error.ai.tooMany");
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(limiter.tryAcquire("a")).isNull();
        assertThat(limiter.tryAcquire("b")).isEqualTo("error.ai.dailyLimit");
    }
}
