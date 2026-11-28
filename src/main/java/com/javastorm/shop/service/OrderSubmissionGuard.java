package com.javastorm.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 防止重复下单：同一个顾客用同一个下单标识重复提交时，只真正下一次单，后面的请求直接拿到第一次的结果。
 * <ul>
 *   <li>只记住<b>成功</b>的下单结果；失败的请求不占名额，也不会被“记住”，改正后可以直接重试。</li>
 *   <li>同一 IP 10 分钟内最多尝试下单 {@link #PER_ADDRESS} 次（命中已有结果的重复提交不计）。</li>
 *   <li>开启 {@code shop.redis.enabled=true} 时使用 Redis 分布式锁与结果缓存，支持多实例集群幂等；
 *       未开启时自动降级为单机内存实现。</li>
 * </ul>
 */
@Component
public class OrderSubmissionGuard {

    private static final Logger log = LoggerFactory.getLogger(OrderSubmissionGuard.class);

    /** 显式传了 Idempotency-Key 的请求，结果保留 10 分钟。 */
    public static final Duration KEY_TTL = Duration.ofMinutes(10);
    /** 没传标识时按订单内容自动去重，只防双击和网络重试，30 秒后同样的订单可以再下。 */
    public static final Duration AUTO_TTL = Duration.ofSeconds(30);
    static final int MAX_ENTRIES = 10_000;
    static final int PER_ADDRESS = 20;
    /**
     * 下单锁的有效期必须长于一次下单的最长耗时：下单时会同步创建 Stripe / PayPal 付款页，
     * Stripe 默认连接超时 30 秒 + 读取超时 80 秒，PayPal 鉴权和建单各最多 15 + 15 秒。
     * 锁提前过期会让同一个请求的重复提交也进入下单，可能下出两单。正常情况下下单结束就会主动释放锁。
     */
    static final Duration LOCK_TTL = Duration.ofSeconds(150);
    private static final long LOCK_WAIT_MS = 5_000L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private record Result(String fingerprint, Object value, Instant expires) {
    }

    private record RedisRecord(String fingerprint, String payload) {
    }

    /** 同一下单标识共用的锁；users 记录还在使用它的请求数，减到 0 才移除，避免排队中的请求和新请求各拿一把锁。 */
    private static final class SubmissionLock {
        private int users;
    }

    private final Clock clock;
    private final RateLimiter limiter;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Map<String, SubmissionLock> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Result> results = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    @Autowired
    public OrderSubmissionGuard(@Value("${shop.redis.enabled:false}") boolean redisEnabled,
                                @Value("${shop.orders.per-ip-limit:" + PER_ADDRESS + "}") int perAddress,
                                ObjectProvider<StringRedisTemplate> redisProvider,
                                ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(Clock.systemUTC(),
                redisEnabled ? redisProvider.getIfAvailable() : null,
                objectMapperProvider.getIfAvailable(ObjectMapper::new), perAddress);
    }

    public OrderSubmissionGuard() {
        this(Clock.systemUTC(), null, new ObjectMapper());
    }

    OrderSubmissionGuard(Clock clock) {
        this(clock, null, new ObjectMapper());
    }

    OrderSubmissionGuard(Clock clock, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(clock, redis, objectMapper, PER_ADDRESS);
    }

    /** @param perAddress 同一 IP 10 分钟内最多下单次数；只在压测环境调大（ORDER_PER_IP_LIMIT），正式环境保持默认 */
    OrderSubmissionGuard(Clock clock, StringRedisTemplate redis, ObjectMapper objectMapper, int perAddress) {
        this.clock = clock;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.limiter = new RateLimiter(perAddress, Integer.MAX_VALUE, clock, "order-submit", redis);
    }

    public <T> T submit(String clientId, String address, String key, String fingerprint, Duration ttl, Supplier<T> action) {
        return submit(clientId, address, key, fingerprint, ttl, null, action);
    }

    /**
     * @param clientId    顾客标识（会话 ID 等）；为空时不做去重，只限制频率
     * @param address     来源 IP，用于频率限制
     * @param key         下单标识（显式的 Idempotency-Key，或按内容生成的指纹）
     * @param fingerprint 订单内容指纹：同一个标识对应的内容不同时拒绝
     * @param ttl         结果保留多久
     * @param resultType  Redis 模式下用于反序列化缓存结果的类型；为空时退回单机内存模式
     */
    public <T> T submit(String clientId, String address, String key, String fingerprint, Duration ttl,
                        Class<T> resultType, Supplier<T> action) {
        if (clientId == null) {
            checkRate(address);
            return action.get();
        }
        String fullKey = clientId + "\n" + key;
        if (redis != null && resultType != null) {
            return submitRedis(fullKey, address, fingerprint, ttl, resultType, action);
        }
        return submitLocal(fullKey, address, fingerprint, ttl, action);
    }

    private <T> T submitRedis(String fullKey, String address, String fingerprint, Duration ttl,
                              Class<T> resultType, Supplier<T> action) {
        String digest = DigestUtils.md5DigestAsHex(fullKey.getBytes(StandardCharsets.UTF_8));
        String resKey = "storm:idem:res:" + digest;
        String lockKey = "storm:idem:lock:" + digest;

        T cached = readRedisResult(resKey, fingerprint, resultType);
        if (cached != null) {
            return cached;
        }

        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        boolean acquired = false;
        while (System.currentTimeMillis() < deadline) {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            if (Boolean.TRUE.equals(ok)) {
                acquired = true;
                break;
            }
            T existing = readRedisResult(resKey, fingerprint, resultType);
            if (existing != null) {
                return existing;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.CONFLICT, "下单请求正在处理中，请稍候");
            }
        }
        if (!acquired) {
            T existing = readRedisResult(resKey, fingerprint, resultType);
            if (existing != null) {
                return existing;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "相同下单请求正在处理中，请勿重复提交");
        }

        try {
            T existing = readRedisResult(resKey, fingerprint, resultType);
            if (existing != null) {
                return existing;
            }
            checkRate(address);
            T value = action.get();
            writeRedisResult(resKey, fingerprint, value, ttl);
            return value;
        } finally {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    private <T> T readRedisResult(String resKey, String fingerprint, Class<T> resultType) {
        String json = redis.opsForValue().get(resKey);
        if (json == null) {
            return null;
        }
        try {
            RedisRecord record = objectMapper.readValue(json, RedisRecord.class);
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同一下单标识对应的订单内容不同");
            }
            return objectMapper.readValue(record.payload(), resultType);
        } catch (JsonProcessingException e) {
            log.warn("反序列化 Redis 幂等缓存失败，忽略损坏记录 key={}", resKey, e);
            redis.delete(resKey);
            return null;
        }
    }

    private <T> void writeRedisResult(String resKey, String fingerprint, T value, Duration ttl) {
        try {
            String payload = objectMapper.writeValueAsString(value);
            String wrapped = objectMapper.writeValueAsString(new RedisRecord(fingerprint, payload));
            redis.opsForValue().set(resKey, wrapped, ttl);
        } catch (JsonProcessingException e) {
            log.warn("写入 Redis 幂等缓存失败 key={}", resKey, e);
        }
    }

    private <T> T submitLocal(String fullKey, String address, String fingerprint, Duration ttl, Supplier<T> action) {
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
