package com.javastorm.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.math.BigDecimal;

/**
 * 某个币种（人民币 = 中文前台，美元 = 英文前台）的运费规则：
 * 运费 = 首重运费 + 超出首重的部分每公斤加价（不足 1 公斤按 1 公斤算）；商品金额达到包邮门槛时免运费。
 */
@Entity
public class ShippingRule implements ShippingFees {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 3)
    private Currency currency;

    private boolean enabled;

    /** 首重运费（包含 includedGrams 以内的重量）。 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal baseFee = BigDecimal.ZERO;

    /** 首重包含的重量（克）。 */
    private int includedGrams = 1000;

    /** 续重：超出首重后每公斤加价，为空表示不按重量加价。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal extraPerKg;

    /** 包邮门槛（商品金额），为空表示不包邮。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal freeThreshold;

    /** true 时只配送到运费分组里列出的国家，其他国家不能下单。老数据为空，视为 false。 */
    private Boolean zonesOnly;

    protected ShippingRule() {
    }

    public ShippingRule(Currency currency) {
        this.currency = currency;
    }

    public boolean isZonesOnly() {
        return Boolean.TRUE.equals(zonesOnly);
    }

    public void setZonesOnly(boolean zonesOnly) {
        this.zonesOnly = zonesOnly;
    }

    public Long getId() {
        return id;
    }

    public Currency getCurrency() {
        return currency;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public BigDecimal getBaseFee() {
        return baseFee;
    }

    public void setBaseFee(BigDecimal baseFee) {
        this.baseFee = baseFee == null ? BigDecimal.ZERO : baseFee;
    }

    @Override
    public int getIncludedGrams() {
        return includedGrams;
    }

    public void setIncludedGrams(int includedGrams) {
        this.includedGrams = Math.max(0, includedGrams);
    }

    @Override
    public BigDecimal getExtraPerKg() {
        return extraPerKg;
    }

    public void setExtraPerKg(BigDecimal extraPerKg) {
        this.extraPerKg = extraPerKg;
    }

    @Override
    public BigDecimal getFreeThreshold() {
        return freeThreshold;
    }

    public void setFreeThreshold(BigDecimal freeThreshold) {
        this.freeThreshold = freeThreshold;
    }
}
