package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.OrderMailRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.AnalyticsService;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 合并 Codex 分支时修复的问题（审查时复现过的场景）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodexFixesTest {

    static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=");

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired AnalyticsService analytics;
    @Autowired OrderMailRepository mails;

    Product p;

    @BeforeEach
    void setUp() {
        mails.deleteAll();
        orders.deleteAll();
        products.deleteAll();
        p = new Product();
        p.setName("杯子");
        p.setSku("FIX-1");
        p.setPrice(new BigDecimal("10"));
        p.setStock(50);
        p.setStatus(ProductStatus.ON_SALE);
        p = products.save(p);
    }

    static CheckoutForm form(String email) {
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("张三");
        f.setPhone("13800000000");
        f.setAddress("北京");
        f.setCountry("CN");
        f.setEmail(email);
        return f;
    }

    @Test
    @WithMockUser(username = "catalog1", roles = {"STAFF", "CATALOG"})
    void catalogStaffCanUploadImages() throws Exception {
        mvc.perform(multipart("/api/admin/images").file(new MockMultipartFile("files", "a.png", "image/png", PNG)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[0]").value(containsString("/uploads/products/")));
    }

    @Test
    @WithMockUser(username = "support1", roles = {"STAFF", "SUPPORT"})
    void supportStaffStillCannotUploadImages() throws Exception {
        mvc.perform(multipart("/api/admin/images").file(new MockMultipartFile("files", "a.png", "image/png", PNG)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCouponOnCheckoutReturnsJsonWithReason() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(csrf()).param("productId", p.getId().toString()));
        mvc.perform(get("/checkout/shipping").session(session).param("country", "CN").param("couponCode", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.couponError").value(containsString("Invalid coupon")))
                .andExpect(jsonPath("$.total").value("¥10.00"))
                .andExpect(jsonPath("$.deliverable").value(true));
        mvc.perform(get("/checkout/shipping").session(session).param("country", "CN"))
                .andExpect(jsonPath("$.couponError").doesNotExist());
    }

    @Test
    void apiOrderDeduplicationIsShortAndNeedsNoSession() throws Exception {
        String body = "{\"currency\":\"CNY\",\"customerName\":\"张三\",\"phone\":\"13800000000\",\"address\":\"北京\","
                + "\"country\":\"CN\",\"items\":[{\"productId\":" + p.getId() + ",\"quantity\":1}]}";
        // 同一会话双击：只下一单
        MockHttpSession session = new MockHttpSession();
        String first = mvc.perform(post("/api/orders").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/orders").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(orders.count()).isEqualTo(1);

        // 不带 Cookie 的调用方：不新建会话、不自动去重（要防重复请传 Idempotency-Key）
        MvcResult cookieless = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(cookieless.getRequest().getSession(false)).isNull();
        assertThat(orders.count()).isEqualTo(2);

        // 带 Idempotency-Key 时，不带 Cookie 也能防重复
        mvc.perform(post("/api/orders").header("Idempotency-Key", "abc-123").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "abc-123").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(orders.count()).isEqualTo(3);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void offlineOrdersCountAsRevenueOnlyAfterReceiptConfirmation() throws Exception {
        Cart cart = new Cart();
        cart.add(p.getId(), 3);
        var placed = orderService.placeOrder(cart, form(null), Currency.CNY);
        var cny = report();
        assertThat(cny.orders()).isEqualTo(1);
        assertThat(cny.paid()).isEqualByComparingTo("0"); // 还没确认收款

        orderService.changeStatus(placed.getId(), OrderStatus.SHIPPED);
        orderService.changeStatus(placed.getId(), OrderStatus.COMPLETED);
        cny = report();
        assertThat(cny.paidOrders()).isZero();
        assertThat(cny.paid()).isEqualByComparingTo("0");
        mvc.perform(post("/api/admin/orders/{id}/offline-payment", placed.getId()).with(csrf())
                        .with(user("support").roles("STAFF", "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/orders/{id}/offline-payment", placed.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.canConfirmOfflinePayment").value(false));
        var recordedAt = orders.findById(placed.getId()).orElseThrow().getPaidAt();
        assertThat(recordedAt).isNotNull();
        assertThat(orderService.confirmOfflinePayment(placed.getId()).getPaidAt()).isEqualTo(recordedAt);
        cny = report();
        assertThat(cny.paidOrders()).isEqualTo(1);
        assertThat(cny.paid()).isEqualByComparingTo("30.00");
        assertThat(cny.topProducts()).singleElement().satisfies(t -> assertThat(t.quantity()).isEqualTo(3));
    }

    private AnalyticsService.Summary report() {
        return analytics.report(LocalDate.now(), LocalDate.now()).stream()
                .filter(s -> s.currency() == Currency.CNY).findFirst().orElseThrow();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void paidOrdersDoNotOfferDirectCancel() throws Exception {
        Cart cart = new Cart();
        cart.add(p.getId(), 1);
        var order = orderService.placeOrder(cart, form(null), Currency.USD, true);
        orderService.markPaid(order.getOrderNo(), "cs_x");
        mvc.perform(get("/api/admin/orders/{id}", order.getId()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.nextStatuses[0]").value("SHIPPED"))
                .andExpect(content().string(not(containsString("\"CANCELLED\"]"))));

        Cart cart2 = new Cart();
        cart2.add(p.getId(), 1);
        var unpaid = orderService.placeOrder(cart2, form(null), Currency.CNY);
        mvc.perform(get("/api/admin/orders/{id}", unpaid.getId()))
                .andExpect(jsonPath("$.nextStatuses[1]").value("CANCELLED"));
    }

    @Test
    void orderNoticesExpireSoStaleOnesAreNeverSent() {
        Cart cart = new Cart();
        cart.add(p.getId(), 1);
        orderService.placeOrder(cart, form("buyer@example.com"), Currency.CNY);
        assertThat(mails.findAll()).singleElement().satisfies(m -> {
            assertThat(m.getRecipient()).isEqualTo("buyer@example.com");
            assertThat(m.getExpiresAt()).isBetween(LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(4));
        });
    }
}
