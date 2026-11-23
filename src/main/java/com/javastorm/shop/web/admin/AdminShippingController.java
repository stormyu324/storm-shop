package com.javastorm.shop.web.admin;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.repository.ShippingRuleRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 运费设置：人民币（中文前台）和美元（英文前台）各一套规则。 */
@Controller
@RequestMapping("/admin/shipping")
public class AdminShippingController {

    private final ShippingRuleRepository rules;

    public AdminShippingController(ShippingRuleRepository rules) {
        this.rules = rules;
    }

    @GetMapping
    public String page(Model model) {
        Map<Currency, ShippingRule> byCurrency = new LinkedHashMap<>();
        for (Currency c : Currency.values()) {
            byCurrency.put(c, rules.findByCurrency(c).orElseGet(() -> new ShippingRule(c)));
        }
        model.addAttribute("rules", byCurrency);
        return "admin/shipping";
    }

    @PostMapping("/{currency}")
    @Transactional
    public String save(@PathVariable Currency currency,
                       @RequestParam(defaultValue = "false") boolean enabled,
                       @RequestParam(required = false) BigDecimal baseFee,
                       @RequestParam(defaultValue = "1000") int includedGrams,
                       @RequestParam(required = false) BigDecimal extraPerKg,
                       @RequestParam(required = false) BigDecimal freeThreshold,
                       RedirectAttributes flash) {
        if (negative(baseFee) || negative(extraPerKg) || negative(freeThreshold) || includedGrams < 0) {
            flash.addFlashAttribute("error", "运费和重量不能为负数");
            return "redirect:/admin/shipping";
        }
        ShippingRule rule = rules.findByCurrency(currency).orElseGet(() -> new ShippingRule(currency));
        rule.setEnabled(enabled);
        rule.setBaseFee(baseFee);
        rule.setIncludedGrams(includedGrams);
        rule.setExtraPerKg(extraPerKg);
        rule.setFreeThreshold(freeThreshold);
        rules.save(rule);
        flash.addFlashAttribute("message", (currency == Currency.USD ? "美元" : "人民币") + "运费规则已保存"
                + (enabled ? "" : "（未启用，顾客下单免运费）"));
        return "redirect:/admin/shipping";
    }

    private static boolean negative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }
}
