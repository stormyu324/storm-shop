package com.javastorm.shop.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.javastorm.shop.domain.OrderAccess;
import com.javastorm.shop.repository.OrderAccessRepository;
import com.javastorm.shop.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class OrderAccessService {
    private final OrderAccessRepository access;
    private final OrderRepository orders;
    private final OrderMailService mail;
    private final SecureRandom random = new SecureRandom();
    // 只按来源 IP 限制（10 分钟 5 次），不设全站每日总数：否则少数人刷满后所有顾客当天都收不到验证码。
    // 开启 Redis 时各节点共用同一份计数，否则换着节点请求就能绕过限制
    private final RateLimiter limiter;

    public OrderAccessService(
            OrderAccessRepository access, OrderRepository orders, OrderMailService mail,
            @Value("${shop.redis.enabled:false}") boolean redisEnabled,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this.limiter = new RateLimiter(5, Integer.MAX_VALUE, Clock.systemUTC(), "order-code",
                redisEnabled ? redisProvider.getIfAvailable() : null);
        this.access = access;
        this.orders = orders;
        this.mail = mail;
    }

    @Transactional
    public String request(String number, String email, String client) {
        if (!mail.available()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "订单邮件服务尚未配置 / Email service unavailable");
        }
        if (limiter.tryAcquire(client) != null) {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁 / Too many requests");
        }
        OrderAccess record = new OrderAccess();
        record.setId(UUID.randomUUID().toString());
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        record.setCodeHash(hash(record.getId(), code));
        record.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        orders
                .findByOrderNo(number.trim())
                .filter(o -> o.getEmail() != null && o.getEmail().equalsIgnoreCase(email.trim()))
                .ifPresent(
                        o -> {
                            record.setOrderId(o.getId());
                            mail.enqueue(
                                    "OTP:" + record.getId(),
                                    o.getEmail(),
                                    "Storm order verification / 订单验证码",
                                    "Code / 验证码 " + code + "\n10 分钟内有效，请勿向他人透露。 / Expires in 10 minutes.",
                                    record.getExpiresAt());
                        });
        access.save(record);
        return record.getId();
    }

    @Transactional
    public Long verify(String id, String code) {
        var record = access.lock(id).orElse(null);
        if (record == null
                || record.isConsumed()
                || record.getAttempts() >= 5
                || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        record.setAttempts(record.getAttempts() + 1);
        if (!MessageDigest.isEqual(
                        record.getCodeHash().getBytes(StandardCharsets.UTF_8),
                        hash(id, code).getBytes(StandardCharsets.UTF_8))
                || record.getOrderId() == null) {
            return null;
        }
        record.setConsumed(true);
        return record.getOrderId();
    }

    private static String hash(String id, String code) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
