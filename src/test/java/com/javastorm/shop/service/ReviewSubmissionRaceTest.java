package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewSubmissionRaceTest {
    @Test
    void failedFirstRequestStillDeduplicatesConcurrentRetries() throws Exception {
        var guard = new OrderSubmissionGuard();
        var firstEntered = new CountDownLatch(1);
        var failFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var finishSecond = new CountDownLatch(1);
        var successfulOrders = new AtomicInteger();
        var first = new Thread(() -> {
            try {
                guard.submit("customer", "ip", "same-key", "same-body", OrderSubmissionGuard.KEY_TTL, () -> {
                    firstEntered.countDown();
                    await(failFirst);
                    throw new IllegalStateException("temporary failure");
                });
            } catch (IllegalStateException expected) { }
        });
        var second = new Thread(() -> guard.submit("customer", "ip", "same-key", "same-body", OrderSubmissionGuard.KEY_TTL, () -> {
            secondEntered.countDown();
            await(finishSecond);
            return successfulOrders.incrementAndGet();
        }));
        var third = new Thread(() -> guard.submit("customer", "ip", "same-key", "same-body",
                OrderSubmissionGuard.KEY_TTL, successfulOrders::incrementAndGet));
        try {
            first.start();
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            second.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < until) Thread.onSpinWait();
            assertThat(second.getState()).isEqualTo(Thread.State.BLOCKED);
            failFirst.countDown();
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            first.join(5000);
            assertThat(first.isAlive()).isFalse();
            third.start();
            long retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (third.isAlive() && third.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < retryDeadline) Thread.onSpinWait();
            assertThat(third.getState()).isEqualTo(Thread.State.BLOCKED);
        } finally {
            failFirst.countDown(); finishSecond.countDown();
            first.join(5000); second.join(5000); third.join(5000);
        }
        assertThat(second.isAlive()).isFalse();
        assertThat(third.isAlive()).isFalse();
        assertThat(successfulOrders.get()).isOne();
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(e);
        }
    }
}
