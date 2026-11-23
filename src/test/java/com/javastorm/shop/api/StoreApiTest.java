package com.javastorm.shop.api;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StoreApiTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;

    Product whiteM, blackM, mug, hidden;
    Category home;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        Category clothing = new Category("服装");
        clothing.setNameEn("Apparel");
        clothing = categories.save(clothing);
        home = categories.save(new Category("家居"));
        whiteM = product("TEE-W-M", "纯棉T恤 白色 M", "Cotton Tee - White, M", "59", "8.99", 5, clothing, "TEE", "White", "M");
        blackM = product("TEE-B-M", "纯棉T恤 黑色 M", "Cotton Tee - Black, M", "69", "9.99", 0, clothing, "TEE", "Black", "M");
        mug = product("MUG", "陶瓷马克杯", "Ceramic Mug", "35", null, 10, home, null, null, null);
        hidden = product("OFF", "下架商品", null, "10", null, 1, home, null, null, null);
        hidden.setStatus(ProductStatus.OFF_SHELF);
        products.save(hidden);
    }

    Product product(String sku, String name, String titleEn, String price, String usd, int stock, Category c,
                    String parent, String color, String size) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setTitleEn(titleEn);
        p.setPrice(new BigDecimal(price));
        p.setPriceUsd(usd == null ? null : new BigDecimal(usd));
        p.setStock(stock);
        p.setCategory(c);
        p.setParentSku(parent);
        p.setColor(color);
        p.setSize(size);
        p.setStatus(ProductStatus.ON_SALE);
        return products.save(p);
    }

    @Test
    void listsOnSaleProductsOnePerStyleInRequestedLanguage() throws Exception {
        mvc.perform(get("/api/products").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[?(@.sku=='TEE-W-M')].title").value("Cotton Tee"))
                .andExpect(jsonPath("$.items[?(@.sku=='TEE-W-M')].price").value(8.99))
                .andExpect(jsonPath("$.items[?(@.sku=='TEE-W-M')].priceFrom").value(true))
                .andExpect(jsonPath("$.items[?(@.sku=='TEE-W-M')].variantCount").value(2))
                .andExpect(jsonPath("$.items[?(@.sku=='TEE-W-M')].currency").value("USD"))
                .andExpect(content().string(not(containsString("OFF"))));

        mvc.perform(get("/api/products").param("q", "马克杯").param("currency", "CNY"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("陶瓷马克杯"))
                .andExpect(jsonPath("$.items[0].price").value(35));

        mvc.perform(get("/api/categories").param("lang", "en"))
                .andExpect(jsonPath("$[0].name").value("Apparel"));
        mvc.perform(get("/api/countries").param("lang", "en"))
                .andExpect(jsonPath("$[0].code").value("US"))
                .andExpect(jsonPath("$[0].name").value("United States"));
    }

    @Test
    void productDetailIncludesVariantsAndHidesOffShelf() throws Exception {
        mvc.perform(get("/api/products/{id}", whiteM.getId()).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Cotton Tee - White, M"))
                .andExpect(jsonPath("$.colors", hasSize(2)))
                .andExpect(jsonPath("$.colors[1].label").value("Black"))
                .andExpect(jsonPath("$.colors[1].productId").value(blackM.getId()))
                .andExpect(jsonPath("$.colors[1].available").value(false))
                .andExpect(jsonPath("$.sizes", hasSize(0)));

        mvc.perform(get("/api/products/{id}", hidden.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("商品不存在或已下架"));
    }

    @Test
    void quoteAndPlaceOrder() throws Exception {
        String items = "\"items\":[{\"productId\":" + mug.getId() + ",\"quantity\":2}]";
        mvc.perform(post("/api/shipping/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"CNY\",\"country\":\"CN\"," + items + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(70))
                .andExpect(jsonPath("$.shipping").value(0))
                .andExpect(jsonPath("$.deliverable").value(true))
                .andExpect(jsonPath("$.lines[0].available").value(true));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"CNY\",\"customerName\":\"张三\",\"phone\":\"13800000000\","
                                + "\"address\":\"北京市朝阳区\",\"country\":\"CN\"," + items + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.total").value(70))
                .andExpect(jsonPath("$.paymentUrl").value(nullValue()));

        assertThat(orders.findAll()).singleElement().satisfies(o -> {
            assertThat(o.getStatus()).isEqualTo(OrderStatus.PLACED);
            assertThat(o.getCountry()).isEqualTo("CN");
        });
        assertThat(products.findById(mug.getId()).orElseThrow().getStock()).isEqualTo(8);
    }

    @Test
    void orderErrorsAreReadableJson() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).param("lang", "en")
                        .content("{\"customerName\":\"\",\"phone\":\"abc\",\"address\":\"x\",\"country\":\"ZZ\","
                                + "\"items\":[{\"productId\":" + mug.getId() + ",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.customerName").value("Please enter your name"))
                .andExpect(jsonPath("$.fields.phone").value("Please enter a valid phone number"))
                .andExpect(jsonPath("$.fields.country").exists());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).param("lang", "en")
                        .content("{\"customerName\":\"Jo\",\"phone\":\"+1 415 555 0100\",\"address\":\"SF\",\"country\":\"US\","
                                + "\"items\":[{\"productId\":" + mug.getId() + ",\"quantity\":11}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Not enough stock for \"Ceramic Mug\" (only 10 left)"));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("请求格式不正确")));
        assertThat(orders.count()).isZero();
    }

    @Test
    void openApiDocsListOnlyApiEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Storm 商城接口"))
                .andExpect(jsonPath("$.paths['/api/products']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/products/{id}']").exists())
                .andExpect(jsonPath("$.paths['/admin/products']").doesNotExist());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void aiAssistantHiddenWithoutApiKey() throws Exception {
        mvc.perform(get("/api/recommendations/status")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/recommendations").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"gift\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("AI 导购暂未开启"));
        mvc.perform(get("/")).andExpect(content().string(not(containsString("data-ai-form"))));
    }
}
