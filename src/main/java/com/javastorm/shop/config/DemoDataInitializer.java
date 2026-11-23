package com.javastorm.shop.config;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 首次启动（数据库为空）时写入几条示例数据，方便直接看到效果。可用 shop.demo-data=false 关闭。 */
@Component
@ConditionalOnProperty(name = "shop.demo-data", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    private final CategoryRepository categories;
    private final ProductRepository products;

    public DemoDataInitializer(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (categories.count() > 0 || products.count() > 0) {
            addMissingDemoImages();
            return;
        }
        Category clothing = category("服装", "Apparel");
        Category home = category("家居", "Home");
        Category digital = category("数码", "Electronics");

        // 同一款 T 恤的 4 个规格（同一款号），前台显示为一个商品，可选颜色和尺码
        String[][] tees = {{"白色", "White", "M", "40"}, {"白色", "White", "L", "35"},
                {"黑色", "Black", "M", "30"}, {"黑色", "Black", "L", "0"}};
        for (int i = 0; i < tees.length; i++) {
            String[] t = tees[i];
            Product tee = product("纯棉圆领T恤 " + t[0] + " " + t[2] + "码", "DEMO-001-" + (i + 1), clothing, "59.00", "99.00",
                    Integer.parseInt(t[3]), "100% 纯棉面料，柔软透气，四季百搭。",
                    "Classic Cotton Crew Neck T-Shirt - " + t[1] + ", " + t[2],
                    "Soft, breathable 100% cotton tee for everyday wear.");
            tee.setParentSku("DEMO-TEE");
            tee.setColor(t[1]);
            tee.setSize(t[2]);
            tee.setImageUrls(demoImage(tee).map(List::of).orElse(List.of()));
            tee.setBrand("StormWear");
            products.save(tee);
        }
        product("休闲牛仔裤", "DEMO-002", clothing, "159.00", null, 60, "经典直筒版型，弹力舒适。",
                "Relaxed Straight-Leg Jeans", "Classic straight fit with comfortable stretch denim.");
        product("陶瓷马克杯", "DEMO-003", home, "35.00", "45.00", 200, "高温烧制，容量 350ml，可微波炉加热。",
                "Ceramic Coffee Mug 12 oz", "High-fired ceramic, 350 ml capacity, microwave safe.");
        product("北欧风抱枕", "DEMO-004", home, "49.00", null, 80, "可拆洗外套，填充饱满。",
                "Nordic Style Throw Pillow", "Removable washable cover with plush filling.");
        product("无线蓝牙耳机", "DEMO-005", digital, "199.00", "299.00", 40, "蓝牙 5.3，续航 30 小时。",
                "Wireless Bluetooth Earbuds", "Bluetooth 5.3 with up to 30 hours of battery life.");
        product("快充数据线", "DEMO-006", digital, "29.90", null, 3, "支持 60W 快充，编织线身更耐用。",
                "60W Fast Charging Cable", "Supports 60W fast charging with a durable braided design.");
    }

    private Category category(String name, String nameEn) {
        Category c = new Category(name);
        c.setNameEn(nameEn);
        return categories.save(c);
    }

    private Product product(String name, String sku, Category category, String price, String originalPrice,
                         int stock, String description, String titleEn, String descriptionEn) {
        Product p = new Product();
        p.setName(name);
        p.setSku(sku);
        p.setCategory(category);
        p.setPrice(new BigDecimal(price));
        p.setOriginalPrice(originalPrice == null ? null : new BigDecimal(originalPrice));
        p.setStock(stock);
        p.setDescription(description);
        p.setTitleEn(titleEn);
        p.setDescriptionEn(descriptionEn);
        p.setStatus(ProductStatus.ON_SALE);
        p.setImageUrls(demoImage(p).map(List::of).orElse(List.of()));
        return products.save(p);
    }

    /** 早期版本写入的示例商品没有配图：补上（只处理 DEMO- 开头、且还没有图片的商品）。 */
    private void addMissingDemoImages() {
        for (Product p : products.findAll()) {
            if (p.getSku() != null && p.getSku().startsWith("DEMO-") && p.getImageUrls().isEmpty()) {
                demoImage(p).ifPresent(url -> p.setImageUrls(List.of(url)));
            }
        }
    }

    /** 示例商品的插图，放在 static/img/demo 下。 */
    static Optional<String> demoImage(Product p) {
        String sku = p.getSku();
        String file;
        if (sku.startsWith("DEMO-001")) {
            boolean black = "Black".equalsIgnoreCase(p.getColor()) || p.getName().contains("黑");
            file = black ? "tee-black" : "tee-white";
        } else {
            file = switch (sku) {
                case "DEMO-002" -> "jeans";
                case "DEMO-003" -> "mug";
                case "DEMO-004" -> "pillow";
                case "DEMO-005" -> "earbuds";
                case "DEMO-006" -> "cable";
                default -> null;
            };
        }
        return Optional.ofNullable(file).map(f -> "/img/demo/" + f + ".svg");
    }
}
