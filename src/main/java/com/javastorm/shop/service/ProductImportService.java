package com.javastorm.shop.service;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通过 CSV（可用 Excel 编辑后“另存为 CSV”）批量上架商品。
 * 以 SKU 为准：已存在的 SKU 会被更新，新的 SKU 会新建；未填写 SKU 时自动生成。
 */
@Service
public class ProductImportService {

    /** 模板表头，同时接受这些列的英文别名。 */
    public static final List<String> HEADERS = List.of("名称", "SKU", "分类", "售价", "原价", "库存", "状态", "图片", "描述",
            "英文标题", "美元售价", "品牌", "款号", "颜色", "尺码", "材质", "重量(g)", "长(cm)", "宽(cm)", "高(cm)",
            "条码", "英文卖点", "英文描述");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("name", "名称"), Map.entry("商品名称", "名称"),
            Map.entry("sku", "SKU"), Map.entry("编码", "SKU"), Map.entry("商品编码", "SKU"),
            Map.entry("category", "分类"),
            Map.entry("price", "售价"), Map.entry("价格", "售价"),
            Map.entry("originalprice", "原价"), Map.entry("original_price", "原价"),
            Map.entry("stock", "库存"),
            Map.entry("status", "状态"),
            Map.entry("images", "图片"), Map.entry("image", "图片"), Map.entry("图片地址", "图片"),
            Map.entry("description", "描述"), Map.entry("商品描述", "描述"),
            Map.entry("title_en", "英文标题"), Map.entry("titleen", "英文标题"), Map.entry("english title", "英文标题"),
            Map.entry("price_usd", "美元售价"), Map.entry("priceusd", "美元售价"), Map.entry("usd", "美元售价"),
            Map.entry("brand", "品牌"),
            Map.entry("parent_sku", "款号"), Map.entry("parentsku", "款号"), Map.entry("父sku", "款号"),
            Map.entry("color", "颜色"), Map.entry("size", "尺码"), Map.entry("material", "材质"),
            Map.entry("weight", "重量(g)"), Map.entry("重量", "重量(g)"), Map.entry("重量g", "重量(g)"),
            Map.entry("length", "长(cm)"), Map.entry("width", "宽(cm)"), Map.entry("height", "高(cm)"),
            Map.entry("barcode", "条码"), Map.entry("upc", "条码"), Map.entry("ean", "条码"),
            Map.entry("bullet_points", "英文卖点"), Map.entry("bullets", "英文卖点"),
            Map.entry("description_en", "英文描述"), Map.entry("descriptionen", "英文描述"));

    private static final Charset GB18030 = Charset.forName("GB18030");

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductService productService;

    public ProductImportService(ProductRepository products, CategoryRepository categories, ProductService productService) {
        this.products = products;
        this.categories = categories;
        this.productService = productService;
    }

    public record RowError(long line, String message) {
    }

    public record ImportResult(int created, int updated, List<RowError> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * @param bytes         CSV 文件内容，自动识别 UTF-8 / GBK 编码
     * @param defaultStatus 行内未填写“状态”时使用的状态
     */
    @Transactional
    public ImportResult importCsv(byte[] bytes, ProductStatus defaultStatus) {
        String text = decode(bytes);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        int created = 0;
        int updated = 0;
        List<RowError> errors = new ArrayList<>();

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            Map<String, String> columns = resolveColumns(parser.getHeaderNames());
            if (!columns.containsKey("名称") || !columns.containsKey("售价")) {
                throw new BusinessException("CSV 表头至少需要包含「名称」和「售价」两列，请下载模板对照");
            }
            for (CSVRecord record : parser) {
                long line = record.getRecordNumber() + 1; // +1 是表头行
                try {
                    boolean isNew = importRow(record, columns, defaultStatus);
                    if (isNew) {
                        created++;
                    } else {
                        updated++;
                    }
                } catch (BusinessException | IllegalArgumentException e) {
                    errors.add(new RowError(line, e.getMessage()));
                }
            }
        } catch (IOException | IllegalStateException e) {
            throw new BusinessException("无法解析 CSV 文件：" + e.getMessage());
        }
        return new ImportResult(created, updated, errors);
    }

    /** 带 BOM 的 UTF-8 模板，Excel 打开不会乱码。 */
    public byte[] template() {
        String csv = "\uFEFF" + String.join(",", HEADERS) + "\r\n"
                + "纯棉圆领T恤 白色 M码,TSHIRT-WHITE-M,服装,59.00,99.00,100,上架,https://example.com/a.jpg|https://example.com/b.jpg,100% 纯棉，透气舒适,"
                + "Classic Cotton Crew Neck T-Shirt,12.99,StormWear,TSHIRT,White,M,100% Cotton,180,30,25,3,012345678905,"
                + "SOFT & BREATHABLE: 100% cotton|CLASSIC FIT: true to size,Soft and breathable everyday tee.\r\n"
                + "陶瓷马克杯,,家居,35,,50,,,,,,,,,,,,,,,,,\r\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private boolean importRow(CSVRecord record, Map<String, String> columns, ProductStatus defaultStatus) {
        String name = value(record, columns, "名称");
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("名称不能为空");
        }
        if (name.length() > 200) {
            throw new BusinessException("名称超过 200 字");
        }
        BigDecimal price = decimal(value(record, columns, "售价"), "售价");
        if (price == null) {
            throw new BusinessException("售价不能为空");
        }
        BigDecimal originalPrice = decimal(value(record, columns, "原价"), "原价");
        String stockText = value(record, columns, "库存");
        Integer stock = null;
        if (StringUtils.hasText(stockText)) {
            try {
                stock = Integer.parseInt(stockText);
            } catch (NumberFormatException e) {
                throw new BusinessException("库存「" + stockText + "」不是整数");
            }
            if (stock < 0) {
                throw new BusinessException("库存不能为负数");
            }
        }
        ProductStatus status = ProductStatus.parse(value(record, columns, "状态"));

        String sku = value(record, columns, "SKU");
        Product product = StringUtils.hasText(sku) ? products.findBySku(sku).orElse(null) : null;
        boolean isNew = product == null;
        if (isNew) {
            product = new Product();
            product.setSku(StringUtils.hasText(sku) ? sku : productService.generateSku());
            product.setStatus(defaultStatus);
            product.setStock(0);
        }
        product.setName(name);
        product.setPrice(price);
        if (originalPrice != null || isNew) {
            product.setOriginalPrice(originalPrice);
        }
        if (stock != null) {
            product.setStock(stock);
        }
        if (status != null) {
            product.setStatus(status);
        }
        String categoryName = value(record, columns, "分类");
        if (StringUtils.hasText(categoryName)) {
            product.setCategory(categories.findByName(categoryName)
                    .orElseGet(() -> categories.save(new Category(categoryName))));
        }
        String imagesText = value(record, columns, "图片");
        if (StringUtils.hasText(imagesText)) {
            product.setImageUrls(Arrays.stream(imagesText.split("[|\\n]"))
                    .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
        }
        String description = value(record, columns, "描述");
        if (description != null && (!description.isEmpty() || isNew)) {
            product.setDescription(description);
        }
        importCrossBorder(record, columns, product);
        products.save(product);
        return isNew;
    }

    /** 跨境 / 平台资料：单元格留空表示不修改。 */
    private static void importCrossBorder(CSVRecord record, Map<String, String> columns, Product product) {
        text(record, columns, "英文标题", 200).ifPresent(product::setTitleEn);
        text(record, columns, "品牌", 100).ifPresent(product::setBrand);
        text(record, columns, "款号", 64).ifPresent(product::setParentSku);
        text(record, columns, "颜色", 50).ifPresent(product::setColor);
        text(record, columns, "尺码", 50).ifPresent(product::setSize);
        text(record, columns, "材质", 100).ifPresent(product::setMaterial);
        text(record, columns, "英文描述", 10000).ifPresent(product::setDescriptionEn);
        text(record, columns, "英文卖点", 2500).ifPresent(v -> product.setBulletPointsEn(
                String.join("\n", Arrays.stream(v.split("[|\\n]")).map(String::trim).filter(x -> !x.isEmpty()).toList())));
        text(record, columns, "条码", 14).ifPresent(v -> {
            if (!v.matches("\\d{12,14}")) {
                throw new BusinessException("条码「" + v + "」应为 12~14 位数字（UPC/EAN/GTIN）");
            }
            product.setBarcode(v);
        });
        BigDecimal usd = decimal(value(record, columns, "美元售价"), "美元售价");
        if (usd != null) {
            product.setPriceUsd(usd);
        }
        String weight = value(record, columns, "重量(g)");
        if (StringUtils.hasText(weight)) {
            try {
                int w = new BigDecimal(weight).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
                if (w < 0) {
                    throw new BusinessException("重量不能为负数");
                }
                product.setWeightGrams(w);
            } catch (ArithmeticException | NumberFormatException e) {
                throw new BusinessException("重量「" + weight + "」不是有效数字（单位：克）");
            }
        }
        BigDecimal length = dimension(value(record, columns, "长(cm)"), "长");
        BigDecimal width = dimension(value(record, columns, "宽(cm)"), "宽");
        BigDecimal height = dimension(value(record, columns, "高(cm)"), "高");
        if (length != null) product.setLengthCm(length);
        if (width != null) product.setWidthCm(width);
        if (height != null) product.setHeightCm(height);
    }

    private static java.util.Optional<String> text(CSVRecord record, Map<String, String> columns, String key, int max) {
        String v = value(record, columns, key);
        if (!StringUtils.hasText(v)) {
            return java.util.Optional.empty();
        }
        if (v.length() > max) {
            throw new BusinessException(key + "超过 " + max + " 个字符");
        }
        return java.util.Optional.of(v);
    }

    private static BigDecimal dimension(String text, String field) {
        BigDecimal v = decimal(text, field);
        return v == null ? null : v.setScale(1, java.math.RoundingMode.HALF_UP);
    }

    private static Map<String, String> resolveColumns(List<String> headerNames) {
        Map<String, String> columns = new HashMap<>();
        for (String raw : headerNames) {
            String h = raw.trim();
            String key = HEADERS.contains(h) ? h
                    : HEADERS.stream().filter(x -> x.equalsIgnoreCase(h)).findFirst()
                    .orElse(ALIASES.get(h.toLowerCase(Locale.ROOT)));
            if (key != null) {
                columns.putIfAbsent(key, raw);
            }
        }
        return columns;
    }

    private static String value(CSVRecord record, Map<String, String> columns, String key) {
        String column = columns.get(key);
        if (column == null || !record.isSet(column)) {
            return null;
        }
        return record.get(column).trim();
    }

    private static BigDecimal decimal(String text, String field) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(text.replace("¥", "").replace("￥", "").replace(",", "").trim());
            if (v.signum() < 0) {
                throw new BusinessException(field + "不能为负数");
            }
            return v.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new BusinessException(field + "「" + text + "」不是有效数字");
        }
    }

    /** 优先按 UTF-8 解码；失败时按 GBK/GB18030（中文版 Excel 另存 CSV 的默认编码）。 */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, GB18030);
        }
    }
}
