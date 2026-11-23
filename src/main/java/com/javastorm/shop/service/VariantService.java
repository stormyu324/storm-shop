package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 同款不同规格：填了同一个款号（parentSku）的在售商品视为一个款式的多个变体。
 * 前台列表每个款式只显示一张卡片，详情页可以按颜色 / 尺码切换。
 */
@Service
public class VariantService {

    /** 常见尺码的排列顺序；不在表里的尺码按数字大小或出现顺序排在后面。 */
    private static final List<String> SIZE_ORDER = List.of(
            "XXXS", "XXS", "XS", "S", "M", "L", "XL", "XXL", "2XL", "XXXL", "3XL", "4XL", "5XL",
            "均码", "ONE SIZE", "OS", "FREE");

    private final ProductRepository products;
    private final Pricing pricing;

    public VariantService(ProductRepository products, Pricing pricing) {
        this.products = products;
        this.pricing = pricing;
    }

    /** 列表卡片用的款式汇总。 */
    public record Family(int variantCount, BigDecimal minPrice, boolean priceVaries, boolean inStock,
                         String titleZh, String titleEn) {
        /** 款式名称：各变体名称的共同部分（去掉颜色、尺码等差异），提取不出来时为 null。 */
        public String title(boolean english) {
            return english && titleEn != null ? titleEn : titleZh;
        }
    }

    /** 某个选项（一个颜色或一个尺码）：点击后跳到 targetId 对应的变体。 */
    public record Option(String label, Long targetId, boolean selected, boolean available) {
    }

    public record Variants(List<Option> colors, List<Option> sizes) {
        public boolean isEmpty() {
            return colors.isEmpty() && sizes.isEmpty();
        }
    }

    /** 当前页面上各款号的汇总：价格区间、变体数、是否有货。 */
    @Transactional(readOnly = true)
    public Map<String, Family> families(Collection<Product> page, Currency currency) {
        Set<String> parentSkus = page.stream().map(Product::getParentSku).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (parentSkus.isEmpty()) {
            return Map.of();
        }
        Map<String, Family> result = new HashMap<>();
        products.findByParentSkuInAndStatus(parentSkus, ProductStatus.ON_SALE).stream()
                .collect(Collectors.groupingBy(Product::getParentSku))
                .forEach((parentSku, members) -> {
                    List<BigDecimal> prices = members.stream().map(p -> pricing.price(p, currency)).toList();
                    BigDecimal min = prices.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
                    boolean varies = prices.stream().anyMatch(x -> x.compareTo(min) != 0);
                    boolean inStock = members.stream().anyMatch(p -> p.getStock() > 0);
                    String zh = commonTitle(members.stream().map(m -> m.title(false)).toList());
                    String en = commonTitle(members.stream().map(m -> m.title(true)).toList());
                    result.put(parentSku, new Family(members.size(), min, varies, inStock, zh, en));
                });
        return result;
    }

    /** 详情页的颜色 / 尺码选项；没有款号或只有一个变体时为空。 */
    @Transactional(readOnly = true)
    public Variants variants(Product current) {
        if (current.getParentSku() == null) {
            return new Variants(List.of(), List.of());
        }
        List<Product> family = products.findByParentSkuAndStatusOrderByIdAsc(current.getParentSku(), ProductStatus.ON_SALE);
        if (family.size() < 2) {
            return new Variants(List.of(), List.of());
        }
        List<Option> colors = options(family, current, Product::getColor, Product::getSize, false);
        List<Option> sizes = options(family, current, Product::getSize, Product::getColor, true);
        // 只有一个取值的维度没有选择的意义
        return new Variants(colors.size() > 1 ? colors : List.of(), sizes.size() > 1 ? sizes : List.of());
    }

    /**
     * 为 dimension（颜色或尺码）的每个取值找一个跳转目标：优先保持另一个维度（other）不变，
     * 找不到时退而选该取值下有货的变体，再不行就选第一个。
     */
    private static List<Option> options(List<Product> family, Product current,
                                        Function<Product, String> dimension, Function<Product, String> other,
                                        boolean sortAsSize) {
        Set<String> values = new LinkedHashSet<>();
        family.stream().map(dimension).filter(Objects::nonNull).forEach(values::add);
        List<String> ordered = new ArrayList<>(values);
        if (sortAsSize) {
            ordered.sort(Comparator.comparingInt(VariantService::sizeRank));
        }
        String currentOther = other.apply(current);
        List<Option> options = new ArrayList<>();
        for (String value : ordered) {
            List<Product> withValue = family.stream().filter(p -> value.equals(dimension.apply(p))).toList();
            Product target = withValue.stream().filter(p -> Objects.equals(other.apply(p), currentOther)).findFirst()
                    .or(() -> withValue.stream().filter(p -> p.getStock() > 0).findFirst())
                    .orElse(withValue.get(0));
            options.add(new Option(value, target.getId(), value.equals(dimension.apply(current)), target.getStock() > 0));
        }
        return options;
    }

    /**
     * 多个变体名称的共同开头，例如「纯棉T恤 白色 M码」「纯棉T恤 黑色 L码」→「纯棉T恤」。
     * 只有一个变体，或共同部分太短（不像一个完整名称）时返回 null，页面改用变体自己的名称。
     */
    public static String commonTitle(List<String> titles) {
        if (titles.size() < 2) {
            return null;
        }
        String prefix = titles.get(0);
        for (String t : titles) {
            int i = 0;
            while (i < prefix.length() && i < t.length() && prefix.charAt(i) == t.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, i);
        }
        // 英文按单词切分；中文没有空格，直接按字切分即可
        boolean endsInWord = !prefix.isEmpty() && isLatinWordChar(prefix.charAt(prefix.length() - 1));
        boolean cutMidWord = false;
        for (String t : titles) {
            if (endsInWord && t.length() > prefix.length() && isLatinWordChar(t.charAt(prefix.length()))) {
                cutMidWord = true;
            }
        }
        if (cutMidWord) {
            // 截在了词语中间（如 "Mug Blue" / "Mug Black" 的共同开头 "Mug Bl"），退回到上一个分隔符
            int cut = prefix.length();
            while (cut > 0 && isLatinWordChar(prefix.charAt(cut - 1))) {
                cut--;
            }
            return trimTitle(prefix.substring(0, cut));
        }
        return trimTitle(prefix);
    }

    private static boolean isLatinWordChar(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || "-–—_/|,，、·(（[【".indexOf(c) >= 0;
    }

    private static String trimTitle(String s) {
        int end = s.length();
        while (end > 0 && isSeparator(s.charAt(end - 1))) {
            end--;
        }
        String t = s.substring(0, end);
        return t.codePointCount(0, t.length()) >= 2 ? t : null;
    }

    static int sizeRank(String size) {
        String s = size.trim().toUpperCase(Locale.ROOT);
        int known = SIZE_ORDER.indexOf(s);
        if (known >= 0) {
            return known;
        }
        String digits = s.replaceAll("[^0-9.]", "");
        if (!digits.isEmpty()) {
            try {
                return 1000 + (int) Math.round(Double.parseDouble(digits) * 10);
            } catch (NumberFormatException ignored) {
                // 落到下面
            }
        }
        return Integer.MAX_VALUE;
    }
}
