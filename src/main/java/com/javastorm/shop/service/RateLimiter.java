package com.javastorm.shop.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 导购的调用限制（按量计费，防止被刷）：每个访客 10 分钟内最多 N 次，全站每天最多 M 次。
 * 只在本机内存里计数，重启后清零；多台服务器部署时每台各自计数。
 */
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final int perClient;
    private final int perDay;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private LocalDate day;
    private int todayCount;

    @Autowired
    public RateLimiter(@Value("${shop.ai.recommend.per-client:10}") int perClient,
                       @Value("${shop.ai.recommend.daily-limit:300}") int perDay) {
        this(perClient, perDay, Clock.systemDefaultZone());
    }

    RateLimiter(int perClient, int perDay, Clock clock) {
        this.perClient = perClient;
        this.perDay = perDay;
        this.clock = clock;
    }

    /** 允许时计一次并返回 null；超出限制时返回提示的 code（error.ai.tooMany / error.ai.dailyLimit）。 */
    public synchronized String tryAcquire(String clientKey) {
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
