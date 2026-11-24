package com.javastorm.shop.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.repository.ShippingRuleRepository;
import com.javastorm.shop.repository.ShippingZoneRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired ShippingRuleRepository rules;
    @Autowired ShippingZoneRepository zones;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        zones.deleteAll();
        rules.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        zones.deleteAll();
        rules.deleteAll();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void productLifecycle() throws Exception {
        MvcResult uploaded = mvc.perform(multipart("/api/admin/images").file(
                        new MockMultipartFile("files", "a.png", "image/png", Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII="))).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String imageUrl = JSON.readTree(uploaded.getResponse().getContentAsString()).path("urls").get(0).asText();

        // 字段校验错误
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"price\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").value("请填写商品名称"))
                .andExpect(jsonPath("$.fields.price").value("售价不能为负数"));
        // 尺寸超过数据库列 DECIMAL(8, 1) 的范围：字段错误，而不是保存时报服务器错误
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"超大箱子\",\"price\":10,\"stock\":1,\"status\":\"DRAFT\",\"lengthCm\":10000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.lengthCm").value("尺寸不能超过 9999999.9 厘米"));

        MvcResult created = mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"保温杯\",\"price\":89,\"stock\":20,\"status\":\"DRAFT\","
                                + "\"imageUrls\":[\"" + imageUrl + "\"],\"bulletPointsEn\":[\"KEEPS HOT: 12h\",\"LEAKPROOF: yes\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value(containsString("P")))
                .andExpect(jsonPath("$.statusLabel").value("草稿"))
                .andExpect(jsonPath("$.bulletPointsEn[1]").value("LEAKPROOF: yes"))
                .andReturn();
        JsonNode p = JSON.readTree(created.getResponse().getContentAsString());
        long id = p.path("id").asLong();
        long version = p.path("version").asLong();

        // 期间有订单扣库存：旧 version 保存被拒绝
        Product entity = products.findById(id).orElseThrow();
        entity.setStatus(ProductStatus.ON_SALE);
        products.save(entity);
        mvc.perform(put("/api/admin/products/{id}", id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"保温杯\",\"price\":99,\"stock\":20,\"version\":" + version + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("编辑期间被修改过")));

        long fresh = JSON.readTree(mvc.perform(get("/api/admin/products/{id}", id)).andReturn()
                .getResponse().getContentAsString()).path("version").asLong();
        mvc.perform(put("/api/admin/products/{id}", id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"保温杯 500ml\",\"price\":99,\"stock\":20,\"status\":\"ON_SALE\",\"version\":" + fresh + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("保温杯 500ml"))
                .andExpect(jsonPath("$.price").value(99));

        mvc.perform(post("/api/admin/products/{id}/duplicate", id).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get("/api/admin/products").param("q", "保温杯"))
                .andExpect(jsonPath("$.totalItems").value(2));

        mvc.perform(post("/api/admin/products/batch").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"action\":\"OFF_SHELF\"}"))
                .andExpect(jsonPath("$.affected").value(1));
        mvc.perform(post("/api/admin/products/batch").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"action\":\"HACK\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/admin/products/{id}", id).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/products/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void csvImport() throws Exception {
        MockMultipartFile csv = new MockMultipartFile("file", "p.csv", "text/csv",
                "名称,售价,库存\n导入商品,12.5,3\n,1,1\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/admin/products/import").file(csv).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors[0].message").value("名称不能为空"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ordersCategoriesAndShipping() throws Exception {
        Product p = new Product();
        p.setName("杯子");
        p.setSku("CUP");
        p.setPrice(new BigDecimal("10"));
        p.setStock(5);
        p.setStatus(ProductStatus.ON_SALE);
        p = products.save(p);
        Cart cart = new Cart();
        cart.add(p.getId(), 2);
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("张三");
        f.setPhone("13800000000");
        f.setAddress("北京");
        f.setCountry("CN");
        CustomerOrder order = orderService.placeOrder(cart, f, Currency.CNY);

        mvc.perform(get("/api/admin/orders").param("toFulfil", "true"))
                .andExpect(jsonPath("$.items[0].orderNo").value(order.getOrderNo()))
                .andExpect(jsonPath("$.items[0].nextStatuses", hasItem("SHIPPED")))
                .andExpect(jsonPath("$.items[0].items[0].quantity").value(2));
        mvc.perform(post("/api/admin/orders/{id}/status", order.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("订单「待处理」不能改为「已完成」"));
        mvc.perform(post("/api/admin/orders/{id}/status", order.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(5);

        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"家居\",\"nameEn\":\"Home\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/admin/categories").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"家居\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("分类「家居」已存在"));
        mvc.perform(get("/api/admin/categories")).andExpect(jsonPath("$[0].nameEn").value("Home"));

        mvc.perform(post("/api/admin/shipping/USD/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"北美\",\"countries\":[\"us\",\"CA\"],\"baseFee\":5.99,\"freeThreshold\":49}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countries[0]").value("US"));
        mvc.perform(post("/api/admin/shipping/USD/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"美国\",\"countries\":[\"US\"],\"baseFee\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("US 已经在分组「北美」里了")));
        mvc.perform(put("/api/admin/shipping/USD/default").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"zonesOnly\":true,\"baseFee\":19.99,\"includedGrams\":1000}"))
                .andExpect(jsonPath("$.defaultRule.zonesOnly").value(true))
                .andExpect(jsonPath("$.zones[0].name").value("北美"));
        mvc.perform(get("/api/admin/shipping")).andExpect(jsonPath("$[1].currency").value("USD"));

        // 公开运费试算跟着生效：巴西不配送
        mvc.perform(post("/api/shipping/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\",\"country\":\"BR\",\"items\":[{\"productId\":" + p.getId() + ",\"quantity\":1}]}"))
                .andExpect(jsonPath("$.deliverable").value(false));

        mvc.perform(get("/api/admin/dashboard")).andExpect(jsonPath("$.onSale").value(1));
    }
}
