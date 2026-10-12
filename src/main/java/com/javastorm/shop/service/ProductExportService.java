package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把商品导出为平台批量上架用的 CSV（带 BOM，Excel 可直接打开）。
 * <ul>
 *   <li>通用资料表：列与批量导入一致，可改完再导回本站；也可复制到 SHEIN 等平台的模板里。</li>
 *   <li>亚马逊格式：列名沿用亚马逊库存文件（flat file）的字段名，按款号生成父子变体。</li>
 * </ul>
 */
@Service
public class ProductExportService {

    private static final int EXTRA_IMAGES = 8;

    public static final List<String> AMAZON_HEADERS = List.of(
            "feed_product_type", "item_sku", "brand_name", "item_name",
            "external_product_id", "external_product_id_type",
            "standard_price", "quantity",
            "main_image_url", "other_image_url1", "other_image_url2", "other_image_url3", "other_image_url4",
            "other_image_url5", "other_image_url6", "other_image_url7", "other_image_url8",
            "parent_child", "parent_sku", "relationship_type", "variation_theme",
            "color_name", "size_name", "material_type",
            "product_description",
            "bullet_point1", "bullet_point2", "bullet_point3", "bullet_point4", "bullet_point5",
            "package_weight", "package_weight_unit_of_measure",
            "package_length", "package_width", "package_height", "package_dimensions_unit_of_measure");

    private final Pricing pricing;

    public ProductExportService(Pricing pricing) {
        this.pricing = pricing;
    }

    /** 通用资料表：导入列 + 完整图片链接，方便复制到其他平台的模板。 */
    public byte[] generic(List<Product> list, String baseUrl) {
        List<String> headers = new ArrayList<>(ProductImportService.HEADERS);
        headers.add("主图链接");
        for (int i = 1; i <= EXTRA_IMAGES; i++) {
            headers.add("附图链接" + i);
        }
        return csv(headers, list.stream().map(p -> {
            List<Object> row = new ArrayList<>(List.of(
                    p.getName(), p.getSku(),
                    p.getCategory() == null ? "" : p.getCategory().getName(),
                    p.getPrice(), nz(p.getOriginalPrice()), p.getStock(), p.getStatus().getLabel(),
                    String.join("|", p.getImageUrls()), nz(p.getDescription()),
                    nz(p.getTitleEn()), nz(p.getPriceUsd()), nz(p.getBrand()), nz(p.getParentSku()),
                    nz(p.getColor()), nz(p.getSize()), nz(p.getMaterial()),
                    nz(p.getWeightGrams()), nz(p.getLengthCm()), nz(p.getWidthCm()), nz(p.getHeightCm()),
                    nz(p.getBarcode()), String.join("|", p.getBulletPointList()), nz(p.getDescriptionEn())));
            row.addAll(imageColumns(p, baseUrl));
            return row;
        }).toList());
    }

    /** 亚马逊库存文件格式；有款号的商品会额外生成一行父商品。 */
    public byte[] amazon(List<Product> list, String baseUrl) {
        Map<String, List<Product>> families = list.stream()
                .filter(p -> p.getParentSku() != null)
                .collect(Collectors.groupingBy(Product::getParentSku, LinkedHashMap::new, Collectors.toList()));

        List<List<Object>> rows = new ArrayList<>();
        families.forEach((parentSku, children) -> rows.add(amazonParentRow(parentSku, children)));
        for (Product p : list) {
            rows.add(amazonRow(p, baseUrl, p.getParentSku() == null ? null : families.get(p.getParentSku())));
        }
        return csv(AMAZON_HEADERS, rows);
    }

    private List<Object> amazonRow(Product p, String baseUrl, List<Product> family) {
        List<String> bullets = p.getBulletPointList();
        List<Object> row = new ArrayList<>();
        row.add(null); // feed_product_type：取决于类目，由卖家在平台模板里填写
        row.add(p.getSku());
        row.add(nz(p.getBrand()));
        row.add(p.title(true));
        row.add(nz(p.getBarcode()));
        row.add(nz(p.getBarcodeType()));
        row.add(pricing.price(p, Currency.USD));
        row.add(p.getStock());
        row.addAll(imageColumns(p, baseUrl));
        if (family != null) {
            row.add("child");
            row.add(p.getParentSku());
            row.add("Variation");
            row.add(variationTheme(family));
        } else {
            row.addAll(List.of("", "", "", ""));
        }
        row.add(nz(p.getColor()));
        row.add(nz(p.getSize()));
        row.add(nz(p.getMaterial()));
        row.add(nz(p.description(true)));
        for (int i = 0; i < 5; i++) {
            row.add(i < bullets.size() ? bullets.get(i) : "");
        }
        row.add(nz(p.getWeightGrams()));
        row.add(p.getWeightGrams() == null ? "" : "GR");
        row.add(nz(p.getLengthCm()));
        row.add(nz(p.getWidthCm()));
        row.add(nz(p.getHeightCm()));
        row.add(p.getLengthCm() == null && p.getWidthCm() == null && p.getHeightCm() == null ? "" : "CM");
        return row;
    }

    private List<Object> amazonParentRow(String parentSku, List<Product> children) {
        Product first = children.get(0);
        List<Object> row = new ArrayList<>();
        row.add(null);
        row.add(parentSku);
        row.add(nz(first.getBrand()));
        row.add(first.title(true));
        row.addAll(List.of("", "", "", ""));
        for (int i = 0; i <= EXTRA_IMAGES; i++) {
            row.add("");
        }
        row.add("parent");
        row.add("");
        row.add("");
        row.add(variationTheme(children));
        while (row.size() < AMAZON_HEADERS.size()) {
            row.add("");
        }
        return row;
    }

    static String variationTheme(List<Product> family) {
        boolean color = family.stream().map(Product::getColor).filter(Objects::nonNull).distinct().count() > 0;
        boolean size = family.stream().map(Product::getSize).filter(Objects::nonNull).distinct().count() > 0;
        if (color && size) {
            return "SizeColor";
        }
        return color ? "Color" : size ? "Size" : "";
    }

    private static List<Object> imageColumns(Product p, String baseUrl) {
        List<Object> cols = new ArrayList<>();
        List<String> urls = p.getImageUrls();
        for (int i = 0; i <= EXTRA_IMAGES; i++) {
            cols.add(i < urls.size() ? absolute(urls.get(i), baseUrl) : "");
        }
        return cols;
    }

    static String absolute(String url, String baseUrl) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (url.startsWith("/") ? url : "/" + url);
    }

    private static Object nz(Object v) {
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        return v == null ? "" : v;
    }

    private static byte[] csv(List<String> headers, List<List<Object>> rows) {
        StringWriter out = new StringWriter();
        out.write('\uFEFF');
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).build();
        try (CSVPrinter printer = new CSVPrinter(out, format)) {
            for (List<Object> row : rows) {
                printer.printRecord(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 跨境资料完整度检查结果。 */
    public record Readiness(int total, int missingTitleEn, int missingBrand, int missingBarcode,
                            int missingImage, int missingWeight, int autoUsdPrice, List<Product> incomplete) {
    }

    public Readiness check(List<Product> list) {
        int titleEn = 0, brand = 0, barcode = 0, image = 0, weight = 0, usd = 0;
        List<Product> incomplete = new ArrayList<>();
        for (Product p : list) {
            boolean bad = false;
            if (blank(p.getTitleEn())) { titleEn++; bad = true; }
            if (blank(p.getBrand())) { brand++; bad = true; }
            if (p.getBarcodeType() == null) { barcode++; bad = true; }
            if (p.getImageUrls().isEmpty()) { image++; bad = true; }
            if (p.getWeightGrams() == null) { weight++; }
            if (p.getPriceUsd() == null) { usd++; }
            if (bad && incomplete.size() < 30) {
                incomplete.add(p);
            }
        }
        return new Readiness(list.size(), titleEn, brand, barcode, image, weight, usd, incomplete);
    }

    /** 列出某个商品缺少的关键资料，用于页面提示。 */
    public static String missingFields(Product p) {
        List<String> missing = new ArrayList<>();
        if (blank(p.getTitleEn())) missing.add("英文标题");
        if (blank(p.getBrand())) missing.add("品牌");
        if (p.getBarcodeType() == null) missing.add("条码");
        if (p.getImageUrls().isEmpty()) missing.add("图片");
        return String.join("、", missing);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
