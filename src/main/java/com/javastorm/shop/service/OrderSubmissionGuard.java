package com.javastorm.shop.service;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Single-instance submission protection. Entries expire after ten minutes. */
@Component
public class OrderSubmissionGuard {
    private record Result(String fingerprint, Object value) {}
    private static final class Client {
        final Instant created = Instant.now();
        int attempts;
        final Map<String, Result> results = new LinkedHashMap<>();
    }
    private final RateLimiter limiter = new RateLimiter(20, 10000, java.time.Clock.systemUTC());
    private final Map<String, Client> clients = new LinkedHashMap<>();

    public <T> T submit(String clientId, String address, String key, String fingerprint, Supplier<T> action) {
        Client client;
        synchronized (clients) {
            clients.entrySet().removeIf(e -> e.getValue().created.plusSeconds(600).isBefore(Instant.now()));
            if (!clients.containsKey(clientId) && clients.size() >= 10000) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "下单人数较多，请稍后重试");
            }
            client = clients.computeIfAbsent(clientId, ignored -> new Client());
        }
        synchronized (client) {
            Result previous = client.results.get(key);
            if (previous != null) {
                if (!previous.fingerprint().equals(fingerprint)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "同一下单标识对应的订单内容不同");
                }
                @SuppressWarnings("unchecked") T value = (T) previous.value();
                return value;
            }
            if (++client.attempts > 20 || limiter.tryAcquire(address) != null) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "下单过于频繁，请稍后重试");
            }
            T value = action.get();
            client.results.put(key, new Result(fingerprint, value));
            return value;
        }
    }
}
