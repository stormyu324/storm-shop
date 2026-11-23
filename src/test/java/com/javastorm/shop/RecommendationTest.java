package com.javastorm.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.RecommendationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI 导购：用本地假服务器代替 Claude API。 */
@SpringBootTest(properties = {"shop.ai.api-key=test-key", "shop.ai.recommend.per-client=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer SERVER;
    static final AtomicReference<JsonNode> LAST = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile Map<String, Object> answer = Map.of("reply", "", "items", List.of());

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/v1/messages", ex -> {
            CALLS.incrementAndGet();
            LAST.set(JSON.readTree(ex.getRequestBody()));
            byte[] out = JSON.writeValueAsString(Map.of(
                    "id", "msg_1", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                    "content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(answer))),
                    "stop_reason", "end_turn", "usage", Map.of("input_tokens", 1, "output_tokens", 1)))
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("shop.ai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired RecommendationService service;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;

    Product mug, teeW, teeB, scarf, hidden;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        Category home = categories.save(new Category("家居"));
        mug = product("MUG", "陶瓷马克杯", "Ceramic Mug", "35", home, null, null);
        teeW = product("TEE-W", "纯棉T恤 白色", "Cotton Tee - White", "59", null, "TEE", "White");
        teeB = product("TEE-B", "纯棉T恤 黑色", "Cotton Tee - Black", "69", null, "TEE", "Black");
        scarf = product("SCARF", "羊毛围巾", "Wool Scarf", "129", null, null, null);
        hidden = product("OFF", "下架商品", null, "1", null, null, null);
        hidden.setStatus(ProductStatus.OFF_SHELF);
        products.save(hidden);
        CALLS.set(0);
    }

    Product product(String sku, String name, String en, String price, Category c, String parent, String color) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setTitleEn(en);
        p.setPrice(new BigDecimal(price));
        p.setStock(5);
        p.setCategory(c);
        p.setParentSku(parent);
        p.setColor(color);
        p.setDescription(name + " 的描述");
        p.setStatus(ProductStatus.ON_SALE);
        return products.save(p);
    }

    @Test
    void sendsCatalogAndKeepsOnlyRealProducts() {
        answer = Map.of("reply", "这几件都很适合送妈妈。", "items", List.of(
                Map.of("productId", 999999, "reason", "编造的"),
                Map.of("productId", scarf.getId(), "reason", "冬天保暖"),
                Map.of("productId", hidden.getId(), "reason", "下架的"),
                Map.of("productId", scarf.getId(), "reason", "重复"),
                Map.of("productId", mug.getId(), "reason", "实用")));

        var result = service.recommend("送妈妈的礼物，200 元以内", null, Currency.CNY, false);

        assertThat(result.reply()).isEqualTo("这几件都很适合送妈妈。");
        assertThat(result.picks()).extracting(p -> p.product().getSku()).containsExactly("SCARF", "MUG");
        assertThat(result.picks().get(0).reason()).isEqualTo("冬天保暖");

        JsonNode body = LAST.get();
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("fallbacks").asText()).isEqualTo("default");
        JsonNode system = body.path("system");
        assertThat(system.get(0).path("text").asText()).contains("Recommend only products listed in the catalog");
        String catalog = system.get(1).path("text").asText();
        assertThat(system.get(1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(catalog).contains("id=" + mug.getId() + " | 陶瓷马克杯 | category: 家居 | price: 35.00")
                .contains("纯棉T恤 白色 | category: - | price: 59.00-69.00 | colors: White/Black")
                .doesNotContain("下架商品")
                .doesNotContain("id=" + teeB.getId() + " "); // 同款只列一次
        String user = body.path("messages").get(0).path("content").toString();
        assertThat(user).contains("Language: Chinese").contains("送妈妈的礼物，200 元以内");

        // 同样的问题直接用缓存，不再调用
        service.recommend("送妈妈的礼物，200 元以内", null, Currency.CNY, false);
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void pairingExcludesTheViewedProductAndItsVariants() {
        answer = Map.of("reply", "Pairs well with these.", "items", List.of(
                Map.of("productId", teeB.getId(), "reason", "same style"),
                Map.of("productId", scarf.getId(), "reason", "layer it")));
        var result = service.recommend(null, teeW.getId(), Currency.USD, true);
        assertThat(result.picks()).extracting(p -> p.product().getSku()).containsExactly("SCARF");
        String user = LAST.get().path("messages").get(0).path("content").toString();
        assertThat(user).contains("Language: English").contains("viewing product id " + teeW.getId());
    }

    @Test
    void apiReturnsCardsAndIsRateLimited() throws Exception {
        answer = Map.of("reply", "Try this.", "items", List.of(Map.of("productId", mug.getId(), "reason", "Great for coffee")));
        mvc.perform(get("/api/recommendations/status")).andExpect(jsonPath("$.enabled").value(true));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/recommendations").param("lang", "en").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"coffee lover gift " + i + "\"}").with(r -> { r.setRemoteAddr("10.0.0.7"); return r; }))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reply").value("Try this."))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].product.title").value("Ceramic Mug"))
                    .andExpect(jsonPath("$.items[0].product.currency").value("USD"))
                    .andExpect(jsonPath("$.items[0].reason").value("Great for coffee"));
        }
        mvc.perform(post("/api/recommendations").param("lang", "en").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"one more\"}").with(r -> { r.setRemoteAddr("10.0.0.7"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("too fast")));
        // 其他访客不受影响；空请求给出提示
        mvc.perform(post("/api/recommendations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}").with(r -> { r.setRemoteAddr("10.0.0.8"); return r; }))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("请说说你想买什么"));
    }

    @Test
    void pagesShowAssistantWhenEnabled() throws Exception {
        mvc.perform(get("/")).andExpect(content().string(containsString("data-ai-form")))
                .andExpect(content().string(containsString("/js/ai-shop.js")));
        mvc.perform(get("/products/{id}", mug.getId())).andExpect(content().string(containsString("data-ai-pair")));
        mvc.perform(get("/js/ai-shop.js")).andExpect(status().isOk());
    }
}
