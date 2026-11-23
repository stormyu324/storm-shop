package com.javastorm.shop.service;

import com.javastorm.shop.domain.Coupon;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.repository.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
        if (!coupon.isEnabled()
                || coupon.getCurrency() != currency
                || now.isBefore(coupon.getStartsAt())
                || !now.isBefore(coupon.getEndsAt())
                || coupon.getUsedCount() >= coupon.getUsageLimit()
                || subtotal.compareTo(coupon.getMinimum()) < 0) {
            throw new BusinessException("优惠码不适用、已过期或已达使用上限 / Coupon unavailable");
        }
        var amount =
                "PERCENT".equals(coupon.getKind())
                        ? subtotal.multiply(coupon.getValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                        : coupon.getValue();
        return amount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public BigDecimal preview(String code, BigDecimal subtotal, Currency currency) {
        String normalized = normalize(code);
        if (normalized.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return calculate(
                coupons
                        .findByCode(normalized)
                        .orElseThrow(() -> new BusinessException("优惠码无效 / Invalid coupon")),
                subtotal,
                currency);
    }

    public void reserve(CustomerOrder order, String code) {
        String normalized = normalize(code);
        if (normalized.isEmpty()) {
            return;
        }
        Coupon coupon =
                coupons
                        .lockCode(normalized)
                        .orElseThrow(() -> new BusinessException("优惠码无效 / Invalid coupon"));
        BigDecimal discount = calculate(coupon, order.getItemsTotal(), order.getCurrency());
        coupon.setUsedCount(coupon.getUsedCount() + 1);
        order.applyDiscount(coupon.getId(), coupon.getCode(), discount);
    }

    public void release(CustomerOrder order) {
        if (order.getCouponId() == null || order.isCouponReleased()) {
            return;
        }
        coupons.lock(order.getCouponId()).ifPresent(c -> c.setUsedCount(Math.max(0, c.getUsedCount() - 1)));
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
                || !Set.of("FIXED", "PERCENT").contains(kind)
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
                || limit < 1) {
            throw new BusinessException("优惠券参数无效");
        }
        Coupon c =
                id == null
                        ? new Coupon()
                        : coupons.lock(id).orElseThrow(() -> new BusinessException("优惠券不存在"));
        if (c.getId() != null
                && (!c.getCode().equals(normalized)
                        || !c.getKind().equals(kind)
                        || c.getValue().compareTo(value) != 0
                        || c.getCurrency() != currency)) {
            throw new BusinessException("已创建优惠券的代码、类型、金额和币种不能修改，请创建新优惠券");
        }
        coupons
                .findByCode(normalized)
                .filter(other -> !Objects.equals(other.getId(), c.getId()))
                .ifPresent(
                        other -> {
                            throw new BusinessException("优惠码已存在");
                        });
        if (limit < c.getUsedCount()) {
            throw new BusinessException("使用上限不能小于已使用次数");
        }
        c.setCode(normalized);
        c.setKind(kind);
        c.setValue(value);
        c.setMinimum(minimum);
        c.setCurrency(currency);
        c.setStartsAt(starts);
        c.setEndsAt(ends);
        c.setEnabled(enabled);
        c.setUsageLimit(limit);
        return coupons.save(c);
    }
}
