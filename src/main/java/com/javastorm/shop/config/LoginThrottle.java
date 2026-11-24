package com.javastorm.shop.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 后台登录失败限制，防止暴力猜密码（单机内存计数，重启后清零）：
 * <ul>
 *   <li>同一 IP 对同一账号 15 分钟内输错 {@link #PER_ACCOUNT} 次，暂停这个 IP 登录这个账号 15 分钟；</li>
 *   <li>同一 IP 15 分钟内总共输错 {@link #PER_ADDRESS} 次（换着账号猜），暂停这个 IP 登录 15 分钟。</li>
 * </ul>
 * 只按“IP + 账号”锁，不单独按账号锁：否则别人故意输错几次就能让店主自己登录不了。登录成功后清除这个 IP 对这个账号的失败记录。
 */
@Component
public class LoginThrottle {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int PER_ACCOUNT = 5;
    static final int PER_ADDRESS = 30;
    static final int MAX_KEYS = 50_000;

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Deque<Instant>> failures;

    @Autowired
    public LoginThrottle() {
        this(Clock.systemUTC(), MAX_KEYS);
    }

    LoginThrottle(Clock clock) {
        this(clock, MAX_KEYS);
    }

    LoginThrottle(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
        // 按访问顺序淘汰最久未活跃的键，保证极端随机 IP 洪泛下内存严格有界且 O(1)
        this.failures = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > LoginThrottle.this.maxKeys;
            }
        };
    }

    /** 当前是否被暂停登录。 */
    public synchronized boolean isBlocked(String address, String username) {
        Instant now = clock.instant();
        return recent(accountKey(address, username), now) >= PER_ACCOUNT
                || recent(addressKey(address), now) >= PER_ADDRESS;
    }

    public synchronized void recordFailure(String address, String username) {
        Instant now = clock.instant();
        failures.computeIfAbsent(accountKey(address, username), k -> new ArrayDeque<>()).addLast(now);
        failures.computeIfAbsent(addressKey(address), k -> new ArrayDeque<>()).addLast(now);
    }

    synchronized int trackedKeys() {
        return failures.size();
    }

    public synchronized void recordSuccess(String address, String username) {
        failures.remove(accountKey(address, username));
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
}
