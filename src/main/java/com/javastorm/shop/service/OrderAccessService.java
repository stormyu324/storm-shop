package com.javastorm.shop.service;

import com.javastorm.shop.domain.OrderAccess;
import com.javastorm.shop.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderAccessService {
  private final OrderAccessRepository access;
  private final OrderRepository orders;
  private final OrderMailService mail;
  private final SecureRandom random = new SecureRandom();
  private final RateLimiter limiter = new RateLimiter(5, 1000, Clock.systemUTC());

  public OrderAccessService(
      OrderAccessRepository access, OrderRepository orders, OrderMailService mail) {
    this.access = access;
    this.orders = orders;
    this.mail = mail;
  }

  @Transactional
  public String request(String number, String email, String client) {
    if (!mail.available())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "订单邮件服务尚未配置 / Email service unavailable");
    if (limiter.tryAcquire(client) != null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁 / Too many requests");
    OrderAccess record = new OrderAccess();
    record.id = UUID.randomUUID().toString();
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
    record.codeHash = hash(record.id, code);
    record.expiresAt = LocalDateTime.now().plusMinutes(10);
    orders
        .findByOrderNo(number.trim())
        .filter(o -> o.getEmail() != null && o.getEmail().equalsIgnoreCase(email.trim()))
        .ifPresent(
            o -> {
              record.orderId = o.getId();
              mail.enqueue(
                  "OTP:" + record.id,
                  o.getEmail(),
                  "Storm order verification / 订单验证码",
                  "Code / 验证码 " + code + "\n10 分钟内有效，请勿向他人透露。 / Expires in 10 minutes.",
                  record.expiresAt);
            });
    access.save(record);
    return record.id;
  }

  @Transactional
  public Long verify(String id, String code) {
    var record = access.lock(id).orElse(null);
    if (record == null
        || record.consumed
        || record.attempts >= 5
        || record.expiresAt.isBefore(LocalDateTime.now())) return null;
    record.attempts++;
    if (!MessageDigest.isEqual(
            record.codeHash.getBytes(StandardCharsets.UTF_8),
            hash(id, code).getBytes(StandardCharsets.UTF_8))
        || record.orderId == null) return null;
    record.consumed = true;
    return record.orderId;
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
