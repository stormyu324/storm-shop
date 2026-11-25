package com.javastorm.shop.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用频率限制：每个访客 10 分钟内最多 N 次，全站每天最多 M 次。
 * 开启 {@code shop.redis.enabled=true} 时使用 Redis Lua 脚本做集群级原子滑动窗口限流；
 * 未开启时自动降级为单机内存计数。
 */
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local clientKey = KEYS[1]
            local dailyKey = KEYS[2]
            local nowMs = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local perClient = tonumber(ARGV[3])
            local perDay = tonumber(ARGV[4])
            local member = ARGV[5]
            local checkDaily = tonumber(ARGV[6])

            if checkDaily == 1 then
                local currentDaily = tonumber(redis.call('GET', dailyKey) or '0')
                if currentDaily >= perDay then
                    return 2
                end
            end

            redis.call('ZREMRANGEBYSCORE', clientKey, '-inf', nowMs - windowMs)
            local currentClient = redis.call('ZCARD', clientKey)
            if currentClient >= perClient then
                return 1
            end

            redis.call('ZADD', clientKey, nowMs, member)
            redis.call('PEXPIRE', clientKey, windowMs)

            if checkDaily == 1 then
                local newDaily = redis.call('INCR', dailyKey)
                if newDaily == 1 then
                    redis.call('EXPIRE', dailyKey, 172800)
                end
            end
            return 0
            """,
            Long.class);

    private final int perClient;
    private final int perDay;
    private final Clock clock;
    private final String namespace;
    private final StringRedisTemplate redis;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private LocalDate day;
    private int todayCount;

    @Autowired
    public RateLimiter(@Value("${shop.ai.recommend.per-client:10}") int perClient,
                       @Value("${shop.ai.recommend.daily-limit:300}") int perDay,
                       @Value("${shop.redis.enabled:false}") boolean redisEnabled,
                       ObjectProvider<StringRedisTemplate> redisProvider) {
        this(perClient, perDay, Clock.systemDefaultZone(), "ai",
                redisEnabled ? redisProvider.getIfAvailable() : null);
    }

    RateLimiter(int perClient, int perDay, Clock clock) {
        this(perClient, perDay, clock, "default", null);
    }

    RateLimiter(int perClient, int perDay, Clock clock, String namespace, StringRedisTemplate redis) {
        this.perClient = perClient;
        this.perDay = perDay;
        this.clock = clock;
        this.namespace = namespace;
        this.redis = redis;
    }

    /** 允许时计一次并返回 null；超出限制时返回提示的 code（error.ai.tooMany / error.ai.dailyLimit）。 */
    public String tryAcquire(String clientKey) {
        if (redis != null) {
            return tryAcquireRedis(clientKey);
        }
        return tryAcquireLocal(clientKey);
    }

    private String tryAcquireRedis(String clientKey) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        String cKey = "storm:rl:" + namespace + ":client:" + clientKey;
        String dKey = "storm:rl:" + namespace + ":day:" + today;
        String member = now.toEpochMilli() + ":" + UUID.randomUUID();
        boolean checkDaily = perDay < Integer.MAX_VALUE;
        Long result = redis.execute(
                ACQUIRE_SCRIPT,
                List.of(cKey, dKey),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(WINDOW.toMillis()),
                String.valueOf(perClient),
                String.valueOf(perDay),
                member,
                checkDaily ? "1" : "0");
        if (Long.valueOf(2L).equals(result)) {
            return "error.ai.dailyLimit";
        }
        if (Long.valueOf(1L).equals(result)) {
            return "error.ai.tooMany";
        }
        return null;
    }

    private synchronized String tryAcquireLocal(String clientKey) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        if (!today.equals(day)) {
            day = today;
            todayCount = 0;
            hits.clear();
        }
        if (todayCount >= perDay) {
            return "error.ai.dailyLimit";
        }
        Deque<Instant> q = hits.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        for (Iterator<Instant> it = q.iterator(); it.hasNext(); ) {
            if (it.next().isBefore(now.minus(WINDOW))) {
                it.remove();
            }
        }
        if (q.size() >= perClient) {
            return "error.ai.tooMany";
        }
        q.addLast(now);
        todayCount++;
        return null;
    }
}
