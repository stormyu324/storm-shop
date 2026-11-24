package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.domain.ShippingZone;
import com.javastorm.shop.repository.ShippingRuleRepository;
import com.javastorm.shop.repository.ShippingZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/** 运费设置的保存和校验，后台页面和后台接口共用。 */
@Service
public class ShippingSettingsService {

    private final ShippingRuleRepository rules;
    private final ShippingZoneRepository zones;

    public ShippingSettingsService(ShippingRuleRepository rules, ShippingZoneRepository zones) {
        this.rules = rules;
        this.zones = zones;
    }

    /** 运费参数。 */
    public record Fees(BigDecimal baseFee, int includedGrams, BigDecimal extraPerKg, BigDecimal freeThreshold) {
        /** requireBaseFee：这套运费会被用来计算时，首重运费必须填写（结算时按它计算）。 */
        void validate(boolean requireBaseFee) {
            if (requireBaseFee && baseFee == null) {
                throw new BusinessException("请填写首重运费（不收运费填 0）");
            }
            if (negative(baseFee) || negative(extraPerKg) || negative(freeThreshold) || includedGrams < 0) {
                throw new BusinessException("运费和重量不能为负数");
            }
            if (tooLarge(baseFee) || tooLarge(extraPerKg) || tooLarge(freeThreshold)) {
                throw new BusinessException("金额过大");
            }
        }

        private static boolean negative(BigDecimal v) {
            return v != null && v.signum() < 0;
        }

        /** 数据库金额列最多 10 位整数。 */
        private static boolean tooLarge(BigDecimal v) {
            return v != null && v.compareTo(new BigDecimal("9999999999.99")) > 0;
        }
    }

    @Transactional(readOnly = true)
    public ShippingRule rule(Currency currency) {
        return rules.findByCurrency(currency).orElseGet(() -> new ShippingRule(currency));
    }

    @Transactional(readOnly = true)
    public List<ShippingZone> zones(Currency currency) {
        return zones.findByCurrencyOrderByIdAsc(currency);
    }

    /** 保存默认规则（没有单独设置的国家都用它）。 */
    @Transactional
    public ShippingRule saveDefault(Currency currency, boolean enabled, boolean zonesOnly, Fees fees) {
        fees.validate(enabled);
        if (zonesOnly && zones.findByCurrencyOrderByIdAsc(currency).isEmpty()) {
            throw new BusinessException("还没有添加任何国家分组，不能设为“只配送到列出的国家”");
        }
        ShippingRule rule = rules.findByCurrency(currency).orElseGet(() -> new ShippingRule(currency));
        rule.setEnabled(enabled);
        rule.setZonesOnly(zonesOnly);
        rule.setBaseFee(fees.baseFee());
        rule.setIncludedGrams(fees.includedGrams());
        rule.setExtraPerKg(fees.extraPerKg());
        rule.setFreeThreshold(fees.freeThreshold());
        return rules.save(rule);
    }

    /** 新建（id 为空）或修改国家分组。countries 为用户输入的国家代码，如 "US, CA"。 */
    @Transactional
    public ShippingZone saveZone(Currency currency, Long id, String name, String countries, Fees fees) {
        if (!StringUtils.hasText(name) || name.trim().length() > 50) {
            throw new BusinessException("分组名称需为 1~50 个字");
        }
        List<String> codes = Countries.parse(countries);
        if (codes == null) {
            throw new BusinessException("国家代码有误：请填写两位代码，例如 US, CA, GB（用逗号或空格分隔）");
        }
        if (codes.isEmpty()) {
            throw new BusinessException("请至少填写一个国家代码");
        }
        fees.validate(true);
        for (ShippingZone other : zones.findByCurrencyOrderByIdAsc(currency)) {
            if (other.getId().equals(id)) {
                continue;
            }
            List<String> overlap = codes.stream().filter(other::covers).toList();
            if (!overlap.isEmpty()) {
                throw new BusinessException(String.join(", ", overlap) + " 已经在分组「" + other.getName()
                        + "」里了，一个国家只能属于一个分组");
            }
        }
        ShippingZone zone = id == null ? new ShippingZone(currency)
                : zones.findById(id).filter(z -> z.getCurrency() == currency)
                .orElseThrow(() -> new BusinessException("分组不存在"));
        zone.setName(name.trim());
        zone.setCountries(codes);
        zone.setBaseFee(fees.baseFee());
        zone.setIncludedGrams(fees.includedGrams());
        zone.setExtraPerKg(fees.extraPerKg());
        zone.setFreeThreshold(fees.freeThreshold());
        return zones.save(zone);
    }

    /** @return 被删除的分组名称 */
    @Transactional
    public String deleteZone(Long id) {
        ShippingZone z = zones.findById(id).orElseThrow(() -> new BusinessException("分组不存在"));
        zones.delete(z);
        // 删掉最后一个分组时，自动关闭“只配送到列出的国家”，避免所有国家都不能下单
        if (zones.findByCurrencyOrderByIdAsc(z.getCurrency()).isEmpty()) {
            rules.findByCurrency(z.getCurrency()).ifPresent(r -> r.setZonesOnly(false));
        }
        return z.getName();
    }
}
