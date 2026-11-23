package com.javastorm.shop.web.admin;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.domain.ShippingZone;
import com.javastorm.shop.repository.ShippingRuleRepository;
import com.javastorm.shop.repository.ShippingZoneRepository;
import com.javastorm.shop.service.Countries;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运费设置：人民币（中文前台）和美元（英文前台）各一套。
 * 每套有一条默认规则（其他国家），以及任意多个按国家 / 地区的分组。
 */
@Controller
@RequestMapping("/admin/shipping")
public class AdminShippingController {

    private final ShippingRuleRepository rules;
    private final ShippingZoneRepository zones;

    public AdminShippingController(ShippingRuleRepository rules, ShippingZoneRepository zones) {
        this.rules = rules;
        this.zones = zones;
    }

    /** 页面上每个币种一栏。 */
    public record Section(Currency currency, ShippingRule rule, List<ShippingZone> zones) {
    }

    @GetMapping
    public String page(Model model) {
        Map<Currency, Section> sections = new LinkedHashMap<>();
        for (Currency c : Currency.values()) {
            sections.put(c, new Section(c, rules.findByCurrency(c).orElseGet(() -> new ShippingRule(c)),
                    zones.findByCurrencyOrderByIdAsc(c)));
        }
        model.addAttribute("sections", sections.values());
        model.addAttribute("countryNames", Countries.list(Locale.SIMPLIFIED_CHINESE).stream()
                .collect(Collectors.toMap(Countries.Country::code, Countries.Country::name)));
        return "admin/shipping";
    }

    /** 默认规则（没有单独设置的国家都用它）。 */
    @PostMapping("/{currency}")
    @Transactional
    public String save(@PathVariable Currency currency,
                       @RequestParam(defaultValue = "false") boolean enabled,
                       @RequestParam(defaultValue = "false") boolean zonesOnly,
                       @RequestParam(required = false) BigDecimal baseFee,
                       @RequestParam(defaultValue = "1000") int includedGrams,
                       @RequestParam(required = false) BigDecimal extraPerKg,
                       @RequestParam(required = false) BigDecimal freeThreshold,
                       RedirectAttributes flash) {
        if (negative(baseFee) || negative(extraPerKg) || negative(freeThreshold) || includedGrams < 0) {
            flash.addFlashAttribute("error", "运费和重量不能为负数");
            return "redirect:/admin/shipping";
        }
        if (zonesOnly && zones.findByCurrencyOrderByIdAsc(currency).isEmpty()) {
            flash.addFlashAttribute("error", "还没有添加任何国家分组，不能设为“只配送到列出的国家”");
            return "redirect:/admin/shipping";
        }
        ShippingRule rule = rules.findByCurrency(currency).orElseGet(() -> new ShippingRule(currency));
        rule.setEnabled(enabled);
        rule.setZonesOnly(zonesOnly);
        rule.setBaseFee(baseFee);
        rule.setIncludedGrams(includedGrams);
        rule.setExtraPerKg(extraPerKg);
        rule.setFreeThreshold(freeThreshold);
        rules.save(rule);
        flash.addFlashAttribute("message", label(currency) + "默认运费已保存");
        return "redirect:/admin/shipping";
    }

    /** 新建（id 为空）或修改国家分组。 */
    @PostMapping("/{currency}/zones")
    @Transactional
    public String saveZone(@PathVariable Currency currency,
                           @RequestParam(required = false) Long id,
                           @RequestParam String name,
                           @RequestParam String countries,
                           @RequestParam(required = false) BigDecimal baseFee,
                           @RequestParam(defaultValue = "1000") int includedGrams,
                           @RequestParam(required = false) BigDecimal extraPerKg,
                           @RequestParam(required = false) BigDecimal freeThreshold,
                           RedirectAttributes flash) {
        String error = null;
        List<String> codes = Countries.parse(countries);
        if (!StringUtils.hasText(name) || name.trim().length() > 50) {
            error = "分组名称需为 1~50 个字";
        } else if (codes == null) {
            error = "国家代码有误：请填写两位代码，例如 US, CA, GB（用逗号或空格分隔）";
        } else if (codes.isEmpty()) {
            error = "请至少填写一个国家代码";
        } else if (negative(baseFee) || negative(extraPerKg) || negative(freeThreshold) || includedGrams < 0) {
            error = "运费和重量不能为负数";
        } else {
            for (ShippingZone other : zones.findByCurrencyOrderByIdAsc(currency)) {
                if (other.getId().equals(id)) {
                    continue;
                }
                List<String> overlap = codes.stream().filter(other::covers).toList();
                if (!overlap.isEmpty()) {
                    error = String.join(", ", overlap) + " 已经在分组「" + other.getName() + "」里了，一个国家只能属于一个分组";
                    break;
                }
            }
        }
        if (error != null) {
            flash.addFlashAttribute("error", error);
            return "redirect:/admin/shipping";
        }
        ShippingZone zone = id == null ? new ShippingZone(currency)
                : zones.findById(id).filter(z -> z.getCurrency() == currency).orElse(null);
        if (zone == null) {
            flash.addFlashAttribute("error", "分组不存在");
            return "redirect:/admin/shipping";
        }
        zone.setName(name.trim());
        zone.setCountries(codes);
        zone.setBaseFee(baseFee);
        zone.setIncludedGrams(includedGrams);
        zone.setExtraPerKg(extraPerKg);
        zone.setFreeThreshold(freeThreshold);
        zones.save(zone);
        flash.addFlashAttribute("message", label(currency) + "分组「" + zone.getName() + "」已保存");
        return "redirect:/admin/shipping";
    }

    @PostMapping("/zones/{id}/delete")
    @Transactional
    public String deleteZone(@PathVariable Long id, RedirectAttributes flash) {
        zones.findById(id).ifPresent(z -> {
            zones.delete(z);
            // 删掉最后一个分组时，自动关闭“只配送到列出的国家”，避免所有国家都不能下单
            if (zones.findByCurrencyOrderByIdAsc(z.getCurrency()).isEmpty()) {
                rules.findByCurrency(z.getCurrency()).ifPresent(r -> r.setZonesOnly(false));
            }
            flash.addFlashAttribute("message", "已删除分组「" + z.getName() + "」");
        });
        return "redirect:/admin/shipping";
    }

    private static String label(Currency c) {
        return c == Currency.USD ? "美元" : "人民币";
    }

    private static boolean negative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }
}
