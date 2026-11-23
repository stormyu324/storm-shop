package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingFees;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.domain.ShippingZone;
import com.javastorm.shop.repository.ShippingRuleRepository;
import com.javastorm.shop.repository.ShippingZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 运费计算：先找寄往国家所在的分组（{@link ShippingZone}），没有就用该币种的默认规则（{@link ShippingRule}）。
 * 没有任何设置时运费为 0（与以前一样包邮）。
 */
@Service
public class ShippingService {

    private final ShippingRuleRepository rules;
    private final ShippingZoneRepository zones;

    public ShippingService(ShippingRuleRepository rules, ShippingZoneRepository zones) {
        this.rules = rules;
        this.zones = zones;
    }

    /**
     * @param fee           运费
     * @param freeRemaining 还差多少金额可以包邮；已包邮或没有包邮门槛时为 null
     * @param deliverable   false 表示不配送到这个国家（只配送到已列出国家时）
     */
    public record Quote(BigDecimal fee, BigDecimal freeRemaining, boolean deliverable) {
        public boolean isFree() {
            return fee.signum() == 0;
        }

        static Quote free() {
            return new Quote(BigDecimal.ZERO, null, true);
        }
    }

    /** @param country 寄往国家（两位代码），为空时按默认规则估算 */
    @Transactional(readOnly = true)
    public Quote quote(Currency currency, String country, BigDecimal itemsTotal, int totalWeightGrams) {
        if (country != null) {
            for (ShippingZone zone : zones.findByCurrencyOrderByIdAsc(currency)) {
                if (zone.covers(country)) {
                    return calculate(zone, itemsTotal, totalWeightGrams);
                }
            }
        }
        ShippingRule rule = rules.findByCurrency(currency).orElse(null);
        if (rule != null && rule.isZonesOnly() && country != null) {
            return new Quote(BigDecimal.ZERO, null, false);
        }
        if (rule == null || !rule.isEnabled()) {
            return Quote.free();
        }
        return calculate(rule, itemsTotal, totalWeightGrams);
    }

    /** 纯计算：运费 = 首重 + 超出部分每公斤加价（不足 1 公斤按 1 公斤），达到包邮门槛免运费。 */
    public static Quote calculate(ShippingFees fees, BigDecimal itemsTotal, int totalWeightGrams) {
        if (fees == null || itemsTotal.signum() == 0) {
            return Quote.free();
        }
        BigDecimal threshold = fees.getFreeThreshold();
        if (threshold != null && itemsTotal.compareTo(threshold) >= 0) {
            return Quote.free();
        }
        BigDecimal fee = fees.getBaseFee();
        int extraGrams = Math.max(0, totalWeightGrams - fees.getIncludedGrams());
        if (extraGrams > 0 && fees.getExtraPerKg() != null) {
            long extraKg = (extraGrams + 999) / 1000;
            fee = fee.add(fees.getExtraPerKg().multiply(BigDecimal.valueOf(extraKg)));
        }
        BigDecimal remaining = threshold == null ? null : threshold.subtract(itemsTotal);
        return new Quote(fee.setScale(2, RoundingMode.HALF_UP), remaining, true);
    }
}
