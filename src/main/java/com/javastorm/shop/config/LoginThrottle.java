package com.javastorm.shop.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 后台登录失败限制，防止暴力猜密码：
 * <ul>
 *   <li>同一 IP 对同一账号 15 分钟内输错 {@link #PER_ACCOUNT} 次，暂停这个 IP 登录这个账号 15 分钟；</li>
 *   <li>同一 IP 15 分钟内总共输错 {@link #PER_ADDRESS} 次（换着账号猜），暂停这个 IP 登录 15 分钟。</li>
 * </ul>
 * 开启 {@code shop.redis.enabled=true} 时使用 Redis ZSET 滑动窗口实现跨节点共享；
 * 未开启时降级为本机有界 LRU 内存计数。
 */
@Component
public class LoginThrottle {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int PER_ACCOUNT = 5;
    static final int PER_ADDRESS = 30;
    static final int MAX_KEYS = 50_000;

    private final Clock clock;
    private final int maxKeys;
    private final StringRedisTemplate redis;
    private final Map<String, Deque<Instant>> failures;

    @Autowired
    public LoginThrottle(@Value("${shop.redis.enabled:false}") boolean redisEnabled,
                         ObjectProvider<StringRedisTemplate> redisProvider) {
        this(Clock.systemUTC(), MAX_KEYS, redisEnabled ? redisProvider.getIfAvailable() : null);
    }

    LoginThrottle(Clock clock) {
        this(clock, MAX_KEYS, null);
    }

    LoginThrottle(Clock clock, int maxKeys) {
        this(clock, maxKeys, null);
    }

    LoginThrottle(Clock clock, int maxKeys, StringRedisTemplate redis) {
        this.clock = clock;
        this.maxKeys = maxKeys;
        this.redis = redis;
        // 按访问顺序淘汰最久未活跃的键，保证极端随机 IP 洪泛下内存严格有界且 O(1)
        this.failures = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > LoginThrottle.this.maxKeys;
            }
        };
    }

    /** 当前是否被暂停登录。 */
    public boolean isBlocked(String address, String username) {
        if (redis != null) {
            Instant now = clock.instant();
            return recentRedis(redisAccountKey(address, username), now) >= PER_ACCOUNT
                    || recentRedis(redisAddressKey(address), now) >= PER_ADDRESS;
        }
        synchronized (this) {
            Instant now = clock.instant();
            return recent(accountKey(address, username), now) >= PER_ACCOUNT
                    || recent(addressKey(address), now) >= PER_ADDRESS;
        }
    }

    public void recordFailure(String address, String username) {
        if (redis != null) {
            Instant now = clock.instant();
            addRedisFailure(redisAccountKey(address, username), now);
            addRedisFailure(redisAddressKey(address), now);
            return;
        }
        synchronized (this) {
            Instant now = clock.instant();
            failures.computeIfAbsent(accountKey(address, username), k -> new ArrayDeque<>()).addLast(now);
            failures.computeIfAbsent(addressKey(address), k -> new ArrayDeque<>()).addLast(now);
        }
    }

    synchronized int trackedKeys() {
        return failures.size();
    }

    public void recordSuccess(String address, String username) {
        if (redis != null) {
            redis.delete(redisAccountKey(address, username));
            return;
        }
        synchronized (this) {
            failures.remove(accountKey(address, username));
        }
    }

    private long recentRedis(String key, Instant now) {
        long cutoff = now.minus(WINDOW).toEpochMilli();
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff);
        Long count = redis.opsForZSet().zCard(key);
        return count == null ? 0L : count;
    }

    private void addRedisFailure(String key, Instant now) {
        long nowMs = now.toEpochMilli();
        redis.opsForZSet().add(key, nowMs + ":" + UUID.randomUUID(), nowMs);
        redis.expire(key, WINDOW);
    }

    private int recent(String key, Instant now) {
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return 0;
        }
        while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) {
            q.pollFirst();
        }
        if (q.isEmpty()) {
            failures.remove(key);
            return 0;
        }
        return q.size();
    }

    private static String accountKey(String address, String username) {
        return "user:" + address + "\n" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "ip:" + address;
    }

    private static String redisAccountKey(String address, String username) {
        String digest = DigestUtils.md5DigestAsHex(accountKey(address, username).getBytes(StandardCharsets.UTF_8));
        return "storm:login:user:" + digest;
    }

    private static String redisAddressKey(String address) {
        String digest = DigestUtils.md5DigestAsHex(addressKey(address).getBytes(StandardCharsets.UTF_8));
        return "storm:login:ip:" + digest;
    }
}
