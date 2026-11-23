package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OrderSubmissionGuardTest {
    @Test
    void concurrentRetriesExecuteOnce() throws Exception {
        var guard = new OrderSubmissionGuard();
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<Integer>>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> guard.submit("client", "ip", "request", "body", calls::incrementAndGet)));
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
        assertThat(guard.submit("alice", "ip", "request", "body", () -> 1)).isEqualTo(1);
        assertThat(guard.submit("bob", "ip", "request", "body", () -> 2)).isEqualTo(2);
    }
    @Test
    void rejectsChangedBodyAndLimitsNewOrders() {
        var guard = new OrderSubmissionGuard();
        guard.submit("client", "ip", "request", "body", () -> 1);
        assertThatThrownBy(() -> guard.submit("client", "ip", "request", "changed", () -> 2))
                .isInstanceOf(ResponseStatusException.class);
        for (int i = 1; i < 20; i++) {
            guard.submit("client", "ip", "request" + i, "body", () -> 1);
        }
        assertThatThrownBy(() -> guard.submit("client", "ip", "extra", "body", () -> 1))
                .isInstanceOf(ResponseStatusException.class);
    }
}
