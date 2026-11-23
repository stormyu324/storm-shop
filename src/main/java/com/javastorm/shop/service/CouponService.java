package com.javastorm.shop.service;

import com.javastorm.shop.domain.*;
import com.javastorm.shop.repository.CouponRepository;
import java.math.*;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {
  private final CouponRepository coupons;

  public CouponService(CouponRepository coupons) {
    this.coupons = coupons;
  }

  public String normalize(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  private BigDecimal calculate(Coupon coupon, BigDecimal subtotal, Currency currency) {
    var now = LocalDateTime.now();
    if (!coupon.enabled
        || coupon.currency != currency
        || now.isBefore(coupon.startsAt)
        || !now.isBefore(coupon.endsAt)
        || coupon.usedCount >= coupon.usageLimit
        || subtotal.compareTo(coupon.minimum) < 0)
      throw new BusinessException("优惠码不适用、已过期或已达使用上限 / Coupon unavailable");
    var amount =
        "PERCENT".equals(coupon.kind)
            ? subtotal.multiply(coupon.value).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
            : coupon.value;
    return amount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
  }

  @Transactional(readOnly = true)
  public BigDecimal preview(String code, BigDecimal subtotal, Currency currency) {
    String normalized = normalize(code);
    if (normalized.isEmpty()) return BigDecimal.ZERO;
    return calculate(
        coupons
            .findByCode(normalized)
            .orElseThrow(() -> new BusinessException("优惠码无效 / Invalid coupon")),
        subtotal,
        currency);
  }

  public void reserve(CustomerOrder order, String code) {
    String normalized = normalize(code);
    if (normalized.isEmpty()) return;
    Coupon coupon =
        coupons
            .lockCode(normalized)
            .orElseThrow(() -> new BusinessException("优惠码无效 / Invalid coupon"));
    BigDecimal discount = calculate(coupon, order.getItemsTotal(), order.getCurrency());
    coupon.usedCount++;
    order.applyDiscount(coupon.id, coupon.code, discount);
  }

  public void release(CustomerOrder order) {
    if (order.getCouponId() == null || order.isCouponReleased()) return;
    coupons.lock(order.getCouponId()).ifPresent(c -> c.usedCount = Math.max(0, c.usedCount - 1));
    order.setCouponReleased(true);
  }

  @Transactional
  public Coupon save(
      Long id,
      String code,
      String kind,
      BigDecimal value,
      BigDecimal minimum,
      Currency currency,
      LocalDateTime starts,
      LocalDateTime ends,
      boolean enabled,
      int limit) {
    String normalized = normalize(code);
    if (!normalized.matches("[A-Z0-9_-]{3,40}")
        || !java.util.Set.of("FIXED", "PERCENT").contains(kind)
        || value == null
        || value.signum() <= 0
        || value.scale() > 2
        || value.compareTo(new BigDecimal("9999999999.99")) > 0
        || ("PERCENT".equals(kind) && value.compareTo(new BigDecimal("100")) > 0)
        || minimum == null
        || minimum.signum() < 0
        || minimum.scale() > 2
        || minimum.compareTo(new BigDecimal("9999999999.99")) > 0
        || currency == null
        || starts == null
        || ends == null
        || !ends.isAfter(starts)
        || limit < 1) throw new BusinessException("优惠券参数无效");
    Coupon c =
        id == null
            ? new Coupon()
            : coupons.lock(id).orElseThrow(() -> new BusinessException("优惠券不存在"));
    if (c.id != null
        && (!c.code.equals(normalized)
            || !c.kind.equals(kind)
            || c.value.compareTo(value) != 0
            || c.currency != currency))
      throw new BusinessException("已创建优惠券的代码、类型、金额和币种不能修改，请创建新优惠券");
    coupons
        .findByCode(normalized)
        .filter(other -> !java.util.Objects.equals(other.id, c.id))
        .ifPresent(
            other -> {
              throw new BusinessException("优惠码已存在");
            });
    if (limit < c.usedCount) throw new BusinessException("使用上限不能小于已使用次数");
    c.code = normalized;
    c.kind = kind;
    c.value = value;
    c.minimum = minimum;
    c.currency = currency;
    c.startsAt = starts;
    c.endsAt = ends;
    c.enabled = enabled;
    c.usageLimit = limit;
    return coupons.save(c);
  }
}
