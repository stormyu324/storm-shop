package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.repository.ShippingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** 按币种的运费规则计算运费。没有设置规则时运费为 0（与以前一样包邮）。 */
@Service
public class ShippingService {

    private final ShippingRuleRepository rules;

    public ShippingService(ShippingRuleRepository rules) {
        this.rules = rules;
    }

    /**
     * @param fee           运费
     * @param freeRemaining 还差多少金额可以包邮；已包邮或没有包邮门槛时为 null
     */
    public record Quote(BigDecimal fee, BigDecimal freeRemaining) {
        public boolean isFree() {
            return fee.signum() == 0;
        }
    }

    @Transactional(readOnly = true)
    public Quote quote(Currency currency, BigDecimal itemsTotal, int totalWeightGrams) {
        return calculate(rules.findByCurrency(currency).orElse(null), itemsTotal, totalWeightGrams);
    }

    /** 纯计算，方便测试和后台预览。 */
    public static Quote calculate(ShippingRule rule, BigDecimal itemsTotal, int totalWeightGrams) {
        if (rule == null || !rule.isEnabled() || itemsTotal.signum() == 0) {
            return new Quote(BigDecimal.ZERO, null);
        }
        BigDecimal threshold = rule.getFreeThreshold();
        if (threshold != null && itemsTotal.compareTo(threshold) >= 0) {
            return new Quote(BigDecimal.ZERO, null);
        }
        BigDecimal fee = rule.getBaseFee();
        int extraGrams = Math.max(0, totalWeightGrams - rule.getIncludedGrams());
        if (extraGrams > 0 && rule.getExtraPerKg() != null) {
            long extraKg = (extraGrams + 999) / 1000; // 不足 1 公斤按 1 公斤
            fee = fee.add(rule.getExtraPerKg().multiply(BigDecimal.valueOf(extraKg)));
        }
        BigDecimal remaining = threshold == null ? null : threshold.subtract(itemsTotal);
        return new Quote(fee.setScale(2, java.math.RoundingMode.HALF_UP), remaining);
    }
}
