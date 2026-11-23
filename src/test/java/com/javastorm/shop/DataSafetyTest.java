package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ProductImportService;
import com.javastorm.shop.service.ProductImportService.ImportResult;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 库存、导入数据不被意外改坏的相关测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSafetyTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired ProductImportService importService;
    @Autowired OrderService orderService;

    @BeforeEach
    void clean() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
    }

    @Test
    void failedImportRowDoesNotChangeExistingProduct() {
        importService.importCsv("名称,SKU,售价,库存\n原名,KEEP-1,10,5\n".getBytes(StandardCharsets.UTF_8),
                ProductStatus.ON_SALE);

        ImportResult r = importService.importCsv(
                "名称,SKU,售价,库存,分类,条码\n新名,KEEP-1,99,0,新分类,12345\n".getBytes(StandardCharsets.UTF_8),
                ProductStatus.ON_SALE);

        assertThat(r.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("条码"));
        assertThat(r.updated()).isZero();
        Product p = products.findBySku("KEEP-1").orElseThrow();
        assertThat(p.getName()).isEqualTo("原名");
        assertThat(p.getPrice()).isEqualByComparingTo("10");
        assertThat(p.getStock()).isEqualTo(5);
        assertThat(categories.findByName("新分类")).isEmpty();
    }

    @Test
    void tooLongCategoryIsReportedAsRowError() {
        String longName = "分".repeat(51);
        ImportResult r = importService.importCsv(
                ("名称,售价,分类\n好的,10,服装\n坏的,10," + longName + "\n").getBytes(StandardCharsets.UTF_8),
                ProductStatus.ON_SALE);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("分类"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staleEditFormDoesNotOverwriteStockChangedByOrder() throws Exception {
        Product p = product("EDIT-1", 10);
        long versionWhenOpened = p.getVersion();

        // 管理员打开编辑页之后，顾客买走 2 件
        Cart cart = new Cart();
        cart.add(p.getId(), 2);
        orderService.placeOrder(cart, checkout(), Currency.CNY);
        assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(8);

        // 管理员用旧页面保存（库存仍是打开时的 10）
        mvc.perform(multipart("/admin/products/save").with(csrf())
                        .param("id", p.getId().toString())
                        .param("version", String.valueOf(versionWhenOpened))
                        .param("name", "改了名字").param("sku", "EDIT-1")
                        .param("price", "10").param("stock", "10").param("status", "ON_SALE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑期间被修改过")));

        Product after = products.findById(p.getId()).orElseThrow();
        assertThat(after.getStock()).isEqualTo(8);
        assertThat(after.getName()).isEqualTo("商品EDIT-1");

        // 重新打开（拿到新版本号）后可以正常保存
        mvc.perform(multipart("/admin/products/save").with(csrf())
                        .param("id", p.getId().toString())
                        .param("version", String.valueOf(after.getVersion()))
                        .param("name", "改了名字").param("sku", "EDIT-1")
                        .param("price", "10").param("stock", "8").param("status", "ON_SALE"))
                .andExpect(redirectedUrl("/admin/products"));
        assertThat(products.findById(p.getId()).orElseThrow().getName()).isEqualTo("改了名字");
    }

    @Test
    void concurrentCheckoutsNeverOversellOrShowServerError() throws Exception {
        int stock = 5;
        int buyers = 12;
        Product p = product("HOT-1", stock);

        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            MockHttpSession session = new MockHttpSession();
            mvc.perform(post("/cart/add").session(session).with(csrf()).param("productId", p.getId().toString()));
            sessions.add(session);
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (MockHttpSession session : sessions) {
            Callable<MvcResult> task = () -> {
                start.await();
                return mvc.perform(post("/checkout").session(session).with(csrf())
                        .param("customerName", "张三").param("phone", "13800000000").param("address", "北京市").param("country", "CN"))
                        .andReturn();
            };
            results.add(pool.submit(task));
        }
        start.countDown();

        int succeeded = 0;
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(r.getResponse().getStatus()).isIn(200, 302);
            if ("/order-success".equals(r.getResponse().getRedirectedUrl())) {
                succeeded++;
            } else {
                assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .containsAnyOf("库存不足", "已售罄", "下单人数较多");
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(stock);
        assertThat(orders.count()).isEqualTo(stock);
        assertThat(products.findById(p.getId()).orElseThrow().getStock()).isZero();
    }

    private Product product(String sku, int stock) {
        Product p = new Product();
        p.setName("商品" + sku);
        p.setSku(sku);
        p.setPrice(new BigDecimal("10.00"));
        p.setStock(stock);
        p.setStatus(ProductStatus.ON_SALE);
        return products.save(p);
    }

    private static CheckoutForm checkout() {
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("张三");
        f.setPhone("13800000000");
        f.setAddress("北京市朝阳区");
        return f;
    }
}
