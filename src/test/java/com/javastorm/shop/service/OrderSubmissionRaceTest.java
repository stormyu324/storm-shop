package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 同一下单标识并发重试：第一次失败后，排队中的请求和后来的请求仍然只能有一个真正下单。 */
class OrderSubmissionRaceTest {

    private static final String CLIENT = "customer";
    private static final String KEY = "same-key";
    private static final String BODY = "same-body";

    @Test
    void failedFirstRequestStillDeduplicatesConcurrentRetries() throws Exception {
        OrderSubmissionGuard guard = new OrderSubmissionGuard();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch failFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch finishSecond = new CountDownLatch(1);
        AtomicInteger successfulOrders = new AtomicInteger();

        Thread first = new Thread(() -> {
            try {
                guard.submit(CLIENT, "ip", KEY, BODY, OrderSubmissionGuard.KEY_TTL, () -> {
                    firstEntered.countDown();
                    await(failFirst);
                    throw new IllegalStateException("temporary failure");
                });
            } catch (IllegalStateException expected) {
                // 第一次提交按预期失败
            }
        });
        Thread second = new Thread(() -> guard.submit(CLIENT, "ip", KEY, BODY, OrderSubmissionGuard.KEY_TTL, () -> {
            secondEntered.countDown();
            await(finishSecond);
            return successfulOrders.incrementAndGet();
        }));
        Thread third = new Thread(() -> guard.submit(CLIENT, "ip", KEY, BODY, OrderSubmissionGuard.KEY_TTL,
                successfulOrders::incrementAndGet));
        try {
            first.start();
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            second.start();
            waitUntilBlocked(second);
            assertThat(second.getState()).isEqualTo(Thread.State.BLOCKED);

            failFirst.countDown();
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            first.join(5000);
            assertThat(first.isAlive()).isFalse();

            // 第二个请求还在处理中，第三个请求必须排队，不能另起一单
            third.start();
            waitUntilBlocked(third);
            assertThat(third.getState()).isEqualTo(Thread.State.BLOCKED);
        } finally {
            failFirst.countDown();
            finishSecond.countDown();
            first.join(5000);
            second.join(5000);
            third.join(5000);
        }
        assertThat(second.isAlive()).isFalse();
        assertThat(third.isAlive()).isFalse();
        assertThat(successfulOrders.get()).isOne();
    }

    private static void waitUntilBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
