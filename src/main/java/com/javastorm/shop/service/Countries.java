package com.javastorm.shop.service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 国家 / 地区列表（ISO 3166 两位代码），名称按顾客语言显示。 */
public final class Countries {

    /** 跨境电商常见目的地，下拉框里排在最前面。 */
    public static final List<String> COMMON = List.of("US", "CA", "GB", "AU", "DE", "FR", "JP", "CN");

    private static final Set<String> ALL = Set.of(Locale.getISOCountries());

    private Countries() {
    }

    public record Country(String code, String name) {
    }

    public static boolean isValid(String code) {
        return code != null && ALL.contains(code.toUpperCase(Locale.ROOT));
    }

    public static String name(String code, Locale locale) {
        if (code == null) {
            return "";
        }
        String n = new Locale("", code).getDisplayCountry(locale);
        return n.isEmpty() ? code : n;
    }

    /** 常用国家在前，其余按名称排序。 */
    public static List<Country> list(Locale locale) {
        List<Country> result = new ArrayList<>();
        COMMON.forEach(c -> result.add(new Country(c, name(c, locale))));
        Collator collator = Collator.getInstance(locale);
        Arrays.stream(Locale.getISOCountries())
                .filter(c -> !COMMON.contains(c))
                .map(c -> new Country(c, name(c, locale)))
                .sorted(Comparator.comparing(Country::name, collator))
                .forEach(result::add);
        return result;
    }

    /** 把 "us, CA；de" 之类的输入整理成代码列表；有无效代码时返回 null。 */
    public static List<String> parse(String input) {
        if (input == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String raw : input.split("[,，;；\\s]+")) {
            String c = raw.trim().toUpperCase(Locale.ROOT);
            if (c.isEmpty()) {
                continue;
            }
            if (!isValid(c)) {
                return null;
            }
            if (!codes.contains(c)) {
                codes.add(c);
            }
        }
        return codes;
    }
}
