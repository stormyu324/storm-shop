package com.javastorm.shop.service;

import com.javastorm.shop.domain.MemberAccount;
import com.javastorm.shop.domain.MemberAddress;
import com.javastorm.shop.domain.MemberChallenge;
import com.javastorm.shop.domain.MemberFavorite;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.repository.MemberAddressRepository;
import com.javastorm.shop.repository.MemberChallengeRepository;
import com.javastorm.shop.repository.MemberFavoriteRepository;
import com.javastorm.shop.repository.MemberRepository;
import com.javastorm.shop.repository.ProductRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MemberService {
    private final MemberRepository members;
    private final MemberChallengeRepository challenges;
    private final MemberAddressRepository addresses;
    private final MemberFavoriteRepository favorites;
    private final ProductRepository products;
    private final OrderMailService mail;
    private final SecureRandom random = new SecureRandom();
    // 只按来源 IP 限制（10 分钟 5 次），不设全站每日总数：否则少数人刷满后所有顾客当天都收不到验证码
    private final RateLimiter limiter = new RateLimiter(5, Integer.MAX_VALUE, Clock.systemUTC());

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
        if (!mail.available()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "邮箱服务尚未配置 / Email service unavailable");
        }
        if (limiter.tryAcquire(client) != null) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁 / Too many requests");
        }
        MemberChallenge c = new MemberChallenge();
        c.setId(UUID.randomUUID().toString());
        c.setEmail(email.trim().toLowerCase(Locale.ROOT));
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        c.setCodeHash(hash(c.getId(), code));
        c.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        challenges.save(c);
        mail.enqueue(
                "MEMBER:" + c.getId(),
                c.getEmail(),
                "Storm member verification / 会员验证码",
                "Code / 验证码 " + code + "\n10 分钟内有效。请勿向他人透露。",
                c.getExpiresAt());
        return c.getId();
    }

    @Transactional
    public Long verify(String id, String code) {
        var c = challenges.lock(id).orElse(null);
        if (c == null || c.isUsed() || c.getAttempts() >= 5 || c.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        c.setAttempts(c.getAttempts() + 1);
        if (!MessageDigest.isEqual(
                c.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(id, code).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        var member =
                members
                        .findByEmail(c.getEmail())
                        .orElseGet(
                                () -> {
                                    var m = new MemberAccount();
                                    m.setEmail(c.getEmail());
                                    return members.saveAndFlush(m);
                                });
        c.setUsed(true);
        return member.isEnabled() ? member.getId() : null;
    }

    public MemberAccount current(HttpSession session) {
        if (session == null
                || !(session.getAttribute("memberId") instanceof Long id)
                || !(session.getAttribute("memberExpiry") instanceof Instant expiry)
                || expiry.isBefore(Instant.now())) {
            return null;
        }
        return members.findById(id).filter(m -> m.isEnabled()).orElse(null);
    }

    public MemberAccount require(HttpSession session) {
        var member = current(session);
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录会员 / Sign in first");
        }
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
        if (!Countries.isValid(country)) {
            throw new BusinessException("国家代码无效");
        }
        MemberAddress saved;
        if (id == null) {
            if (addresses(memberId).size() >= 20) {
                throw new BusinessException("最多保存 20 个地址");
            }
            saved = new MemberAddress();
            saved.setMemberId(memberId);
        } else {
            saved =
                addresses
                        .findById(id)
                        .filter(a -> a.getMemberId().equals(memberId))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在"));
        }
        saved.setName(name.trim());
        saved.setPhone(phone.trim());
        saved.setCountry(country.toUpperCase(Locale.ROOT));
        saved.setAddress(address.trim());
        saved.setActive(active);
        if (preferred && active) {
            // 取消其他地址的默认标记；修改已有地址时列表里包含它自己，要跳过
            addresses(memberId).stream()
                    .filter(other -> !other.getId().equals(saved.getId()))
                    .forEach(other -> other.setPreferred(false));
        }
        saved.setPreferred(preferred && active);
        return addresses.save(saved);
    }

    public List<Long> favorites(Long id) {
        return favorites.findByMemberIdAndActiveTrueOrderByIdDesc(id).stream()
                .map(f -> f.getProductId())
                .toList();
    }

    @Transactional
    public void favorite(Long memberId, Long productId, boolean enabled) {
        members.lock(memberId).orElseThrow();
        if (enabled && products.findById(productId).filter(Product::isOnSale).isEmpty()) {
            throw new BusinessException("商品不存在或已下架");
        }
        var f =
                favorites
                        .findByMemberIdAndProductId(memberId, productId)
                        .orElseGet(
                                () -> {
                                    if (favorites(memberId).size() >= 200) {
                                        throw new BusinessException("最多收藏 200 件商品");
                                    }
                                    var created = new MemberFavorite();
                                    created.setMemberId(memberId);
                                    created.setProductId(productId);
                                    return created;
                                });
        f.setActive(enabled);
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
