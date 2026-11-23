package com.javastorm.shop.service;

import com.javastorm.shop.domain.*;
import com.javastorm.shop.repository.*;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberService {
  private final MemberRepository members;
  private final MemberChallengeRepository challenges;
  private final MemberAddressRepository addresses;
  private final MemberFavoriteRepository favorites;
  private final ProductRepository products;
  private final OrderMailService mail;
  private final SecureRandom random = new SecureRandom();
  private final RateLimiter limiter = new RateLimiter(5, 1000, Clock.systemUTC());

  public MemberService(
      MemberRepository members,
      MemberChallengeRepository challenges,
      MemberAddressRepository addresses,
      MemberFavoriteRepository favorites,
      ProductRepository products,
      OrderMailService mail) {
    this.members = members;
    this.challenges = challenges;
    this.addresses = addresses;
    this.favorites = favorites;
    this.products = products;
    this.mail = mail;
  }

  @Transactional
  public String requestCode(String email, String client) {
    if (!mail.available())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "邮箱服务尚未配置 / Email service unavailable");
    if (limiter.tryAcquire(client) != null)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁 / Too many requests");
    MemberChallenge c = new MemberChallenge();
    c.id = UUID.randomUUID().toString();
    c.email = email.trim().toLowerCase(Locale.ROOT);
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
    c.codeHash = hash(c.id, code);
    c.expiresAt = LocalDateTime.now().plusMinutes(10);
    challenges.save(c);
    mail.enqueue(
        "MEMBER:" + c.id,
        c.email,
        "Storm member verification / 会员验证码",
        "Code / 验证码 " + code + "\n10 分钟内有效。请勿向他人透露。",
        c.expiresAt);
    return c.id;
  }

  @Transactional
  public Long verify(String id, String code) {
    var c = challenges.lock(id).orElse(null);
    if (c == null || c.used || c.attempts >= 5 || c.expiresAt.isBefore(LocalDateTime.now()))
      return null;
    c.attempts++;
    if (!MessageDigest.isEqual(
        c.codeHash.getBytes(StandardCharsets.UTF_8),
        hash(id, code).getBytes(StandardCharsets.UTF_8))) return null;
    var member =
        members
            .findByEmail(c.email)
            .orElseGet(
                () -> {
                  var m = new MemberAccount();
                  m.email = c.email;
                  return members.saveAndFlush(m);
                });
    c.used = true;
    return member.enabled ? member.id : null;
  }

  public MemberAccount current(HttpSession session) {
    if (session == null
        || !(session.getAttribute("memberId") instanceof Long id)
        || !(session.getAttribute("memberExpiry") instanceof Instant expiry)
        || expiry.isBefore(Instant.now())) return null;
    return members.findById(id).filter(m -> m.enabled).orElse(null);
  }

  public MemberAccount require(HttpSession session) {
    var member = current(session);
    if (member == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录会员 / Sign in first");
    return member;
  }

  public List<MemberAddress> addresses(Long id) {
    return addresses.findByMemberIdAndActiveTrueOrderByPreferredDescIdDesc(id);
  }

  @Transactional
  public MemberAddress saveAddress(
      Long memberId,
      Long id,
      String name,
      String phone,
      String country,
      String address,
      boolean preferred,
      boolean active) {
    members.lock(memberId).orElseThrow();
    if (!Countries.isValid(country)) throw new BusinessException("国家代码无效");
    MemberAddress saved;
    if (id == null) {
      if (addresses(memberId).size() >= 20) throw new BusinessException("最多保存 20 个地址");
      saved = new MemberAddress();
      saved.memberId = memberId;
    } else
      saved =
          addresses
              .findById(id)
              .filter(a -> a.memberId.equals(memberId))
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在"));
    saved.name = name.trim();
    saved.phone = phone.trim();
    saved.country = country.toUpperCase(Locale.ROOT);
    saved.address = address.trim();
    saved.preferred = preferred && active;
    saved.active = active;
    if (saved.preferred) addresses(memberId).forEach(a -> a.preferred = false);
    return addresses.save(saved);
  }

  public List<Long> favorites(Long id) {
    return favorites.findByMemberIdAndActiveTrueOrderByIdDesc(id).stream()
        .map(f -> f.productId)
        .toList();
  }

  @Transactional
  public void favorite(Long memberId, Long productId, boolean enabled) {
    members.lock(memberId).orElseThrow();
    if (enabled && products.findById(productId).filter(Product::isOnSale).isEmpty())
      throw new BusinessException("商品不存在或已下架");
    var f =
        favorites
            .findByMemberIdAndProductId(memberId, productId)
            .orElseGet(
                () -> {
                  if (favorites(memberId).size() >= 200)
                    throw new BusinessException("最多收藏 200 件商品");
                  var created = new MemberFavorite();
                  created.memberId = memberId;
                  created.productId = productId;
                  return created;
                });
    f.active = enabled;
    favorites.save(f);
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
