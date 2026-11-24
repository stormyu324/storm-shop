package com.javastorm.shop.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 防止重复下单（单机内存实现）：同一个顾客用同一个下单标识重复提交时，只真正下一次单，后面的请求直接拿到第一次的结果。
 * <ul>
 *   <li>只记住<b>成功</b>的下单结果；失败的请求不占名额，也不会被“记住”，改正后可以直接重试。</li>
 *   <li>记录满了淘汰最旧的一条，不会因为记录太多而拒绝所有人下单。</li>
 *   <li>同一 IP 10 分钟内最多尝试下单 {@link #PER_ADDRESS} 次（命中已有结果的重复提交不计）。</li>
 * </ul>
 */
@Component
public class OrderSubmissionGuard {

    /** 显式传了 Idempotency-Key 的请求，结果保留 10 分钟。 */
    public static final Duration KEY_TTL = Duration.ofMinutes(10);
    /** 没传标识时按订单内容自动去重，只防双击和网络重试，30 秒后同样的订单可以再下。 */
    public static final Duration AUTO_TTL = Duration.ofSeconds(30);
    static final int MAX_ENTRIES = 10_000;
    static final int PER_ADDRESS = 20;

    private record Result(String fingerprint, Object value, Instant expires) {
    }

    /** 同一下单标识共用的锁；users 记录还在使用它的请求数，减到 0 才移除，避免排队中的请求和新请求各拿一把锁。 */
    private static final class SubmissionLock {
        private int users;
    }

    private final Clock clock;
    private final RateLimiter limiter;
    private final Map<String, SubmissionLock> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Result> results = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    @Autowired
    public OrderSubmissionGuard() {
        this(Clock.systemUTC());
    }

    OrderSubmissionGuard(Clock clock) {
        this.clock = clock;
        this.limiter = new RateLimiter(PER_ADDRESS, Integer.MAX_VALUE, clock);
    }

    /**
     * @param clientId    顾客标识（会话 ID 等）；为空时不做去重，只限制频率
     * @param address     来源 IP，用于频率限制
     * @param key         下单标识（显式的 Idempotency-Key，或按内容生成的指纹）
     * @param fingerprint 订单内容指纹：同一个标识对应的内容不同时拒绝
     * @param ttl         结果保留多久
     */
    public <T> T submit(String clientId, String address, String key, String fingerprint, Duration ttl, Supplier<T> action) {
        if (clientId == null) {
            checkRate(address);
            return action.get();
        }
        String fullKey = clientId + "\n" + key;
        SubmissionLock lock = inFlight.compute(fullKey, (ignored, existing) -> {
            SubmissionLock shared = existing == null ? new SubmissionLock() : existing;
            shared.users++;
            return shared;
        });
        try {
            synchronized (lock) {
                Result previous = cached(fullKey);
                if (previous != null) {
                    if (!previous.fingerprint().equals(fingerprint)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "同一下单标识对应的订单内容不同");
                    }
                    @SuppressWarnings("unchecked") T value = (T) previous.value();
                    return value;
                }
                checkRate(address);
                T value = action.get(); // 抛出异常时什么都不记，顾客改正后可以重试
                synchronized (results) {
                    results.put(fullKey, new Result(fingerprint, value, clock.instant().plus(ttl)));
                }
                return value;
            }
        } finally {
            // 还有请求在排队时保留同一把锁
            inFlight.computeIfPresent(fullKey, (ignored, shared) -> --shared.users == 0 ? null : shared);
        }
    }

    private Result cached(String fullKey) {
        synchronized (results) {
            Result r = results.get(fullKey);
            if (r != null && r.expires().isBefore(clock.instant())) {
                results.remove(fullKey);
                return null;
            }
            return r;
        }
    }

    private void checkRate(String address) {
        if (limiter.tryAcquire(address == null ? "unknown" : address) != null) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "下单过于频繁，请稍后重试");
        }
    }
}
