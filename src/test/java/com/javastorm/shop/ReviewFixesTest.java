package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.OrderMailRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OfflineOrderCleanup;
import com.javastorm.shop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 代码审查发现的问题：线下订单长期占库存、后台登录可无限猜密码、默认密码可远程登录。 */
@SpringBootTest(properties = {"shop.mail.from=shop@example.invalid", "shop.refunds.initial-delay-ms=3600000",
        "shop.offline-orders.initial-delay=PT1H"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewFixesTest {

    @Autowired ProductRepository products;
    @Autowired OrderRepository orders;
    @Autowired OrderMailRepository mails;
    @Autowired OrderService orderService;
    @Autowired OfflineOrderCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    Product product;

    @BeforeEach
    void setUp() {
        mails.deleteAll();
        orders.deleteAll();
        product = new Product();
        product.setName("线下杯子");
        product.setSku("REVIEW-" + System.nanoTime());
        product.setPrice(new BigDecimal("10"));
        product.setStock(50);
        product.setStatus(ProductStatus.ON_SALE);
        product = products.save(product);
    }

    private CustomerOrder offlineOrder(int quantity, int hoursAgo) {
        Cart cart = new Cart();
        cart.add(product.getId(), quantity);
        CustomerOrder order = orderService.placeOrder(cart, CodexFixesTest.form("buyer@example.com"), Currency.CNY, false);
        jdbc.update("update customer_order set created_at = ? where id = ?",
                LocalDateTime.now().minusHours(hoursAgo), order.getId());
        return order;
    }

    private int stock() {
        return products.findById(product.getId()).orElseThrow().getStock();
    }

    @Test
    void staleUnpaidOfflineOrdersAreCancelledAndRestocked() {
        CustomerOrder stale = offlineOrder(10, 73);
        CustomerOrder fresh = offlineOrder(5, 1);
        CustomerOrder paid = offlineOrder(3, 100);
        orderService.confirmOfflinePayment(paid.getId());
        assertThat(stock()).isEqualTo(32);

        cleanup.cancelStale();

        assertThat(orders.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(orders.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stock()).isEqualTo(42);

        // 重复执行不会重复退库存
        cleanup.cancelStale();
        assertThat(stock()).isEqualTo(42);
    }

    @Test
    void cleanupCanBeTurnedOff() {
        CustomerOrder stale = offlineOrder(10, 500);
        new OfflineOrderCleanup(orderService, 0).cancelStale();
        assertThat(orders.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(stock()).isEqualTo(40);
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String login(String password) {
        return "{\"username\":\"admin\",\"password\":\"" + password + "\"}";
    }

    @Test
    void repeatedWrongPasswordsPauseLoginFromThatAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/admin/session").with(csrf()).with(from("127.0.0.21"))
                            .contentType(MediaType.APPLICATION_JSON).content(login("wrong-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        // 第 6 次即使密码正确也先暂停
        mvc.perform(post("/api/admin/session").with(csrf()).with(from("127.0.0.21"))
                        .contentType(MediaType.APPLICATION_JSON).content(login("admin123")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("密码错误次数过多，请 15 分钟后再试"));
        // 其他地址的店主不受影响
        mvc.perform(post("/api/admin/session").with(csrf()).with(from("127.0.0.22"))
                        .contentType(MediaType.APPLICATION_JSON).content(login("admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void defaultPasswordOnlyWorksFromThisComputer() throws Exception {
        mvc.perform(post("/api/admin/session").with(csrf()).with(from("203.0.113.9"))
                        .contentType(MediaType.APPLICATION_JSON).content(login("admin123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.authenticated").doesNotExist());
        // 远程地址密码错误仍然是 401，不泄露密码是否正确以外的信息
        mvc.perform(post("/api/admin/session").with(csrf()).with(from("203.0.113.9"))
                        .contentType(MediaType.APPLICATION_JSON).content(login("nope")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/session").with(csrf()).with(from("::1"))
                        .contentType(MediaType.APPLICATION_JSON).content(login("admin123")))
                .andExpect(status().isOk());
    }
}
