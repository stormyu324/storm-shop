package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSubmissionGuardTest {

    static final Duration TTL = OrderSubmissionGuard.KEY_TTL;

    @Test
    void concurrentRetriesExecuteOnce() throws Exception {
        var guard = new OrderSubmissionGuard();
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> guard.submit("client", "ip", "request", "body", TTL, calls::incrementAndGet)));
            }
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            }
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void sameAddressDoesNotShareOrderResponses() {
        var guard = new OrderSubmissionGuard();
        assertThat(guard.submit("alice", "ip", "request", "body", TTL, () -> 1)).isEqualTo(1);
        assertThat(guard.submit("bob", "ip", "request", "body", TTL, () -> 2)).isEqualTo(2);
    }

    @Test
    void rejectsChangedBodyAndLimitsNewOrders() {
        var guard = new OrderSubmissionGuard();
        guard.submit("client", "ip", "request", "body", TTL, () -> 1);
        assertThatThrownBy(() -> guard.submit("client", "ip", "request", "changed", TTL, () -> 2))
                .isInstanceOf(ResponseStatusException.class);
        for (int i = 1; i < OrderSubmissionGuard.PER_ADDRESS; i++) {
            guard.submit("client", "ip", "request" + i, "body", TTL, () -> 1);
        }
        assertThatThrownBy(() -> guard.submit("client", "ip", "extra", "body", TTL, () -> 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void automaticDeduplicationOnlyCoversShortWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
        var guard = new OrderSubmissionGuard(clock(now));
        var calls = new AtomicInteger();
        Duration auto = OrderSubmissionGuard.AUTO_TTL;
        assertThat(guard.submit("c", "ip", "auto", "fp", auto, calls::incrementAndGet)).isEqualTo(1);
        now.set(now.get().plusSeconds(10)); // 双击 / 网络重试：拿到同一个订单
        assertThat(guard.submit("c", "ip", "auto", "fp", auto, calls::incrementAndGet)).isEqualTo(1);
        now.set(now.get().plusSeconds(25)); // 过了 30 秒：顾客真想再买一份，可以再下
        assertThat(guard.submit("c", "ip", "auto", "fp", auto, calls::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void failedRequestsTakeNoSlotAndAreNotRemembered() {
        var guard = new OrderSubmissionGuard();
        for (int i = 0; i < OrderSubmissionGuard.MAX_ENTRIES + 10; i++) {
            try {
                guard.submit("client-" + i, "10.0." + (i / 250) + "." + (i % 250), "k", "fp", TTL,
                        () -> { throw new IllegalArgumentException("invalid body"); });
            } catch (IllegalArgumentException expected) {
                // 无效请求
            }
        }
        assertThat(guard.submit("real-customer", "1.2.3.4", "k", "fp", TTL, () -> "ok")).isEqualTo("ok");

        // 失败后修正内容重试：不会被当成“内容不同”拒绝
        assertThatThrownBy(() -> guard.submit("c", "5.6.7.8", "k", "bad", TTL, () -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(guard.submit("c", "5.6.7.8", "k", "good", TTL, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void withoutClientIdOnlyRateLimits() {
        var guard = new OrderSubmissionGuard();
        var calls = new AtomicInteger();
        guard.submit(null, "ip", "auto", "fp", TTL, calls::incrementAndGet);
        guard.submit(null, "ip", "auto", "fp", TTL, calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(2);
    }

    static Clock clock(AtomicReference<Instant> now) {
        return new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
    }
}
