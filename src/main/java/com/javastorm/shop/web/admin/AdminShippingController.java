package com.javastorm.shop.web.admin;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.domain.ShippingZone;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Countries;
import com.javastorm.shop.service.ShippingSettingsService;
import com.javastorm.shop.service.ShippingSettingsService.Fees;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 运费设置：人民币（中文前台）和美元（英文前台）各一套。
 * 每套有一条默认规则（其他国家），以及任意多个按国家 / 地区的分组。
 */
@Controller
@RequestMapping("/admin/shipping")
public class AdminShippingController {

    private final ShippingSettingsService settings;

    public AdminShippingController(ShippingSettingsService settings) {
        this.settings = settings;
    }

    /** 页面上每个币种一栏。 */
    public record Section(Currency currency, ShippingRule rule, List<ShippingZone> zones) {
    }

    @GetMapping
    public String page(Model model) {
        List<Section> sections = new ArrayList<>();
        for (Currency c : Currency.values()) {
            sections.add(new Section(c, settings.rule(c), settings.zones(c)));
        }
        model.addAttribute("sections", sections);
        model.addAttribute("countryNames", Countries.list(Locale.SIMPLIFIED_CHINESE).stream()
                .collect(Collectors.toMap(Countries.Country::code, Countries.Country::name)));
        return "admin/shipping";
    }

    /** 默认规则（没有单独设置的国家都用它）。 */
    @PostMapping("/{currency}")
    public String save(@PathVariable Currency currency,
                       @RequestParam(defaultValue = "false") boolean enabled,
                       @RequestParam(defaultValue = "false") boolean zonesOnly,
                       @RequestParam(required = false) BigDecimal baseFee,
                       @RequestParam(defaultValue = "1000") int includedGrams,
                       @RequestParam(required = false) BigDecimal extraPerKg,
                       @RequestParam(required = false) BigDecimal freeThreshold,
                       RedirectAttributes flash) {
        try {
            settings.saveDefault(currency, enabled, zonesOnly, new Fees(baseFee, includedGrams, extraPerKg, freeThreshold));
            flash.addFlashAttribute("message", label(currency) + "默认运费已保存");
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shipping";
    }

    /** 新建（id 为空）或修改国家分组。 */
    @PostMapping("/{currency}/zones")
    public String saveZone(@PathVariable Currency currency,
                           @RequestParam(required = false) Long id,
                           @RequestParam String name,
                           @RequestParam String countries,
                           @RequestParam(required = false) BigDecimal baseFee,
                           @RequestParam(defaultValue = "1000") int includedGrams,
                           @RequestParam(required = false) BigDecimal extraPerKg,
                           @RequestParam(required = false) BigDecimal freeThreshold,
                           RedirectAttributes flash) {
        try {
            ShippingZone zone = settings.saveZone(currency, id, name, countries,
                    new Fees(baseFee, includedGrams, extraPerKg, freeThreshold));
            flash.addFlashAttribute("message", label(currency) + "分组「" + zone.getName() + "」已保存");
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shipping";
    }

    @PostMapping("/zones/{id}/delete")
    public String deleteZone(@PathVariable Long id, RedirectAttributes flash) {
        try {
            flash.addFlashAttribute("message", "已删除分组「" + settings.deleteZone(id) + "」");
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shipping";
    }

    private static String label(Currency c) {
        return c == Currency.USD ? "美元" : "人民币";
    }
}
