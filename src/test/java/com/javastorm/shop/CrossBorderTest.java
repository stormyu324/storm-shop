package com.javastorm.shop;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.http.MediaType;
import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ProductExportService;
import com.javastorm.shop.service.ProductImportService;
import com.javastorm.shop.service.ProductImportService.ImportResult;
import com.javastorm.shop.web.form.CheckoutForm;
import jakarta.servlet.http.Cookie;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"shop.cny-per-usd=7.00", "shop.public-base-url=https://shop.example.com"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossBorderTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired ProductExportService exportService;
    @Autowired ProductImportService importService;

    @BeforeEach
    void clean() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
    }

    @Test
    void englishStorefrontShowsEnglishTitlesAndUsdPrices() throws Exception {
        Category c = new Category("服装");
        c.setNameEn("Apparel");
        categories.save(c);
        Product withUsd = product("TEE-1", "纯棉T恤", "70.00", "Cotton Tee");
        withUsd.setCategory(c);
        withUsd.setPriceUsd(new BigDecimal("12.99"));
        products.save(withUsd);
        product("MUG-1", "马克杯", "35.00", null);

        mvc.perform(get("/").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("shop_lang=en")))
                .andExpect(content().string(containsString("Cotton Tee")))
                .andExpect(content().string(containsString("$12.99")))
                .andExpect(content().string(containsString("$5.00")))      // 35 / 7，未填英文标题时显示中文名
                .andExpect(content().string(containsString("马克杯")))
                .andExpect(content().string(containsString("Apparel")))
                .andExpect(content().string(containsString("All products")))
                .andExpect(content().string(not(containsString("¥"))));

        mvc.perform(get("/products/" + withUsd.getId()).cookie(new Cookie("shop_lang", "en")))
                .andExpect(content().string(containsString("Add to cart")));

        mvc.perform(get("/"))
                .andExpect(content().string(containsString("纯棉T恤")))
                .andExpect(content().string(containsString("¥70.00")));
    }

    @Test
    void usdOrderUsesUsdPricesAndEnglishNames() {
        Product a = product("A-1", "商品A", "70.00", "Item A");
        a.setPriceUsd(new BigDecimal("9.99"));
        products.save(a);
        Product b = product("B-1", "商品B", "14.00", null);
        Cart cart = new Cart();
        cart.add(a.getId(), 2);
        cart.add(b.getId(), 1);

        CustomerOrder order = orderService.placeOrder(cart, checkout(), Currency.USD);
        assertThat(order.getCurrency()).isEqualTo(Currency.USD);
        assertThat(order.getTotal()).isEqualByComparingTo("21.98"); // 9.99*2 + 14/7
        assertThat(order.getItems()).extracting("productName").containsExactly("Item A", "商品B");
    }

    @Test
    void checkoutValidationMessagesFollowLanguage() throws Exception {
        Product p = product("V-1", "商品", "10.00", "Thing");
        var session = new org.springframework.mock.web.MockHttpSession();
        Cookie en = new Cookie("shop_lang", "en");
        mvc.perform(post("/cart/add").session(session).cookie(en).param("productId", p.getId().toString()).with(csrf()));
        mvc.perform(post("/checkout").session(session).cookie(en).with(csrf())
                        .param("customerName", "").param("phone", "abc").param("address", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please enter your name")))
                .andExpect(content().string(containsString("Please enter a valid phone number")));
    }

    @Test
    void amazonExportBuildsParentChildRowsWithAbsoluteImages() throws IOException {
        Product white = variant("TEE-W-M", "White", "M", "012345678905");
        white.setImageUrls(List.of("/uploads/products/a.jpg", "https://cdn.example.com/b.jpg"));
        products.save(white);
        variant("TEE-B-M", "Black", "M", "4006381333931");
        Product single = product("MUG-1", "马克杯", "35.00", "Mug");

        List<CSVRecord> rows = parse(exportService.amazon(products.findAll(), "https://shop.example.com/"));
        assertThat(rows).hasSize(4);

        CSVRecord parent = rows.get(0);
        assertThat(parent.get("item_sku")).isEqualTo("TEE");
        assertThat(parent.get("parent_child")).isEqualTo("parent");
        assertThat(parent.get("variation_theme")).isEqualTo("SizeColor");

        CSVRecord child = rows.stream().filter(r -> r.get("item_sku").equals("TEE-W-M")).findFirst().orElseThrow();
        assertThat(child.get("parent_child")).isEqualTo("child");
        assertThat(child.get("parent_sku")).isEqualTo("TEE");
        assertThat(child.get("relationship_type")).isEqualTo("Variation");
        assertThat(child.get("external_product_id_type")).isEqualTo("UPC");
        assertThat(child.get("main_image_url")).isEqualTo("https://shop.example.com/uploads/products/a.jpg");
        assertThat(child.get("other_image_url1")).isEqualTo("https://cdn.example.com/b.jpg");
        assertThat(child.get("standard_price")).isEqualTo("12.99");
        assertThat(child.get("bullet_point1")).isEqualTo("SOFT: cotton");
        assertThat(child.get("bullet_point2")).isEqualTo("FIT: classic");
        assertThat(child.get("package_weight_unit_of_measure")).isEqualTo("GR");

        CSVRecord mug = rows.stream().filter(r -> r.get("item_sku").equals("MUG-1")).findFirst().orElseThrow();
        assertThat(mug.get("parent_child")).isEmpty();
        assertThat(mug.get("standard_price")).isEqualTo("5.00");
        assertThat(mug.get("item_name")).isEqualTo("Mug");
        assertThat(single.getId()).isNotNull();

        CSVRecord black = rows.stream().filter(r -> r.get("item_sku").equals("TEE-B-M")).findFirst().orElseThrow();
        assertThat(black.get("external_product_id_type")).isEqualTo("EAN");
    }

    @Test
    void genericExportCanBeReimportedWithoutChanges() {
        variant("TEE-W-M", "White", "M", "012345678905");
        Product before = products.findBySku("TEE-W-M").orElseThrow();

        byte[] csv = exportService.generic(products.findAll(), "https://shop.example.com");
        ImportResult r = importService.importCsv(csv, ProductStatus.DRAFT);
        assertThat(r.errors()).isEmpty();
        assertThat(r.created()).isZero();
        assertThat(r.updated()).isEqualTo(1);

        Product after = products.findBySku("TEE-W-M").orElseThrow();
        assertThat(after.getTitleEn()).isEqualTo(before.getTitleEn());
        assertThat(after.getBulletPointsEn()).isEqualTo("SOFT: cotton\nFIT: classic");
        assertThat(after.getPriceUsd()).isEqualByComparingTo("12.99");
        assertThat(after.getWeightGrams()).isEqualTo(180);
        assertThat(after.getLengthCm()).isEqualByComparingTo("30.0");
        assertThat(after.getBarcode()).isEqualTo("012345678905");
        assertThat(after.getParentSku()).isEqualTo("TEE");
        assertThat(after.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    void importRejectsInvalidBarcodeButKeepsOtherRows() {
        String csv = """
                名称,售价,英文标题,条码,品牌,重量(g)
                好商品,10,Good Item,012345678905,Acme,250
                坏条码,10,Bad Item,12345,Acme,
                """;
        ImportResult r = importService.importCsv(csv.getBytes(StandardCharsets.UTF_8), ProductStatus.ON_SALE);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("条码"));
        Product p = products.findAll().get(0);
        assertThat(p.getTitleEn()).isEqualTo("Good Item");
        assertThat(p.getBrand()).isEqualTo("Acme");
        assertThat(p.getWeightGrams()).isEqualTo(250);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void exportPageAndDownloadsWork() throws Exception {
        product("X-1", "无英文商品", "10.00", null);
        mvc.perform(get("/api/admin/export/readiness").param("status", "ON_SALE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingTitleEn").value(1))
                .andExpect(jsonPath("$.incomplete[0].name").value("无英文商品"))
                .andExpect(jsonPath("$.imageBaseUrl").value("https://shop.example.com"));
        mvc.perform(get("/api/admin/products/import/template.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("名称,SKU,分类")));
        mvc.perform(get("/api/admin/export/amazon.csv").param("status", "ON_SALE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().string(containsString("item_sku")));
        mvc.perform(get("/api/admin/export/products.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("X-1")));
    }

    private Product variant(String sku, String color, String size, String barcode) {
        Product p = product(sku, "纯棉T恤 " + color + " " + size, "70.00", "Cotton Tee " + color + " " + size);
        p.setParentSku("TEE");
        p.setColor(color);
        p.setSize(size);
        p.setBarcode(barcode);
        p.setBrand("StormWear");
        p.setPriceUsd(new BigDecimal("12.99"));
        p.setBulletPointsEn("SOFT: cotton\nFIT: classic");
        p.setWeightGrams(180);
        p.setLengthCm(new BigDecimal("30.0"));
        p.setWidthCm(new BigDecimal("25.0"));
        p.setHeightCm(new BigDecimal("3.0"));
        return products.save(p);
    }

    private Product product(String sku, String name, String price, String titleEn) {
        Product p = new Product();
        p.setName(name);
        p.setSku(sku);
        p.setPrice(new BigDecimal(price));
        p.setStock(10);
        p.setStatus(ProductStatus.ON_SALE);
        p.setTitleEn(titleEn);
        return products.save(p);
    }

    private static List<CSVRecord> parse(byte[] csv) throws IOException {
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (CSVParser parser = CSVParser.parse(new StringReader(text.substring(1)), format)) {
            return parser.getRecords();
        }
    }

    private static CheckoutForm checkout() {
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("John Smith");
        f.setPhone("+1 (415) 555-0100");
        f.setAddress("1 Market St, San Francisco, CA 94105, USA");
        return f;
    }
}
