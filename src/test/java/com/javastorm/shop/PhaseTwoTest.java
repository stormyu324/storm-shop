package com.javastorm.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.domain.Coupon;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.MemberAccount;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.AuditRepository;
import com.javastorm.shop.repository.CouponRepository;
import com.javastorm.shop.repository.MemberRepository;
import com.javastorm.shop.repository.OrderMailRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.repository.StaffRepository;
import com.javastorm.shop.service.AnalyticsService;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.CouponService;
import com.javastorm.shop.service.MemberService;
import com.javastorm.shop.service.OrderCareService;
import com.javastorm.shop.service.OrderPlacer;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"shop.mail.from=shop@example.invalid", "shop.demo-data=false", "shop.refunds.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseTwoTest {
    @Autowired MemberService members;
    @Autowired OrderCareService care;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderMailRepository mail;
    @Autowired CouponService coupons;
    @Autowired CouponRepository couponRepository;
    @Autowired OrderService orderService;
    @Autowired OrderPlacer placer;
    @Autowired OrderRepository orders;
    @Autowired ProductRepository products;
    @Autowired AnalyticsService analytics;
    @Autowired StaffRepository staff;
    @Autowired AuditRepository audit;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean JavaMailSender sender;
    @MockBean StripePaymentService stripe;

    private MemberAccount member() {
        var m = new MemberAccount();
        m.setEmail(UUID.randomUUID() + "@example.invalid");
        return memberRepository.saveAndFlush(m);
    }

    private MockHttpSession session(MemberAccount member) {
        var s = new MockHttpSession();
        s.setAttribute("memberId", member.getId());
        s.setAttribute("memberExpiry", Instant.now().plusSeconds(3600));
        return s;
    }

    private Product product() {
        var p = new Product();
        p.setName("Phase two");
        p.setSku(UUID.randomUUID().toString());
        p.setPrice(new BigDecimal("10.00"));
        p.setStock(20);
        p.setStatus(ProductStatus.ON_SALE);
        return products.saveAndFlush(p);
    }

    private CheckoutForm form(String code) {
        var f = new CheckoutForm();
        f.setCustomerName("Test");
        f.setPhone("12345678");
        f.setCountry("CN");
        f.setAddress("Test address");
        f.setCouponCode(code);
        return f;
    }

    private Cart cart(Product product) {
        var cart = new Cart();
        cart.add(product.getId(), 2);
        return cart;
    }

    private Coupon coupon(int limit) {
        return coupons.save(
                null,
                "C" + UUID.randomUUID().toString().replace("-", ""),
                "FIXED",
                new BigDecimal("5.00"),
                BigDecimal.ZERO,
                Currency.CNY,
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(1),
                true,
                limit);
    }

    @Test
    void addressOwnershipAndDefaultAreEnforced() throws Exception {
        var alice = member();
        var bob = member();
        var a =
                members.saveAddress(alice.getId(), null, "Alice", "12345678", "CN", "Address one", true, true);
        members.saveAddress(alice.getId(), null, "Alice", "12345678", "CN", "Address two", true, true);
        assertThat(members.addresses(alice.getId()).stream().filter(x -> x.isPreferred()).count()).isEqualTo(1);
        // 修改已有地址设为默认：它成为唯一的默认地址
        members.saveAddress(alice.getId(), a.getId(), "Alice", "12345678", "CN", "Address one", true, true);
        assertThat(members.addresses(alice.getId()).stream().filter(x -> x.isPreferred()))
                .singleElement()
                .satisfies(x -> assertThat(x.getId()).isEqualTo(a.getId()));
        mvc.perform(
                        put("/api/member/addresses/" + a.getId())
                                .session(session(bob))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Bob\",\"phone\":\"12345678\",\"country\":\"CN\",\"address\":\"Other\",\"active\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/member/addresses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/member/addresses").session(session(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void memberEmailVerificationIsRequiredAndSingleUse() throws Exception {
        var session = new MockHttpSession();
        String email = UUID.randomUUID() + "@example.invalid";
        var result =
                mvc.perform(
                                post("/api/member/code")
                                        .session(session)
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(Map.of("email", email))))
                        .andExpect(status().isOk())
                        .andReturn();
        String challenge =
                json.readTree(result.getResponse().getContentAsString()).get("challenge").asText();
        var message =
                mail.findAll().stream()
                        .filter(m -> m.getEventKey().equals("MEMBER:" + challenge))
                        .findFirst()
                        .orElseThrow();
        var matcher = Pattern.compile("[0-9]{6}").matcher(message.getBody());
        assertThat(matcher.find()).isTrue();
        String code = matcher.group();
        mvc.perform(
                        post("/api/member/verify")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(Map.of("challenge", challenge, "code", code))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/member/session").session(session))
                .andExpect(jsonPath("$.email").value(email));
        assertThat(members.verify(challenge, code)).isNull();
    }

    @Test
    void favoritesAreIdempotentAndIsolated() {
        var alice = member();
        var bob = member();
        var p = product();
        members.favorite(alice.getId(), p.getId(), true);
        members.favorite(alice.getId(), p.getId(), true);
        assertThat(members.favorites(alice.getId())).containsExactly(p.getId());
        assertThat(members.favorites(bob.getId())).isEmpty();
        members.favorite(alice.getId(), p.getId(), false);
        assertThat(members.favorites(alice.getId())).isEmpty();
    }

    @Test
    void couponIsCalculatedOnServerAndReleasedOnce() {
        var c = coupon(2);
        var p = product();
        var order = placer.place(cart(p), form(c.getCode()), Currency.CNY, false);
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("5.00");
        assertThat(order.getItemsTotal()).isEqualByComparingTo("20.00");
        assertThat(order.getTotal())
                .isEqualByComparingTo(
                        order.getItemsTotal().add(order.getShippingFee()).subtract(new BigDecimal("5")));
        orderService.changeStatus(order.getId(), OrderStatus.CANCELLED);
        orderService.changeStatus(order.getId(), OrderStatus.CANCELLED);
        assertThat(couponRepository.findById(c.getId()).orElseThrow().getUsedCount()).isZero();
        assertThat(coupons.preview(c.getCode(), new BigDecimal("20"), Currency.CNY))
                .isEqualByComparingTo("5");
        assertThatThrownBy(() -> coupons.preview(c.getCode(), new BigDecimal("20"), Currency.USD))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void finalCouponSlotCannotBeOversubscribed() throws Exception {
        var c = coupon(1);
        var p = product();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task =
                    () -> {
                        try {
                            placer.place(cart(p), form(c.getCode()), Currency.CNY, false);
                            return true;
                        } catch (BusinessException e) {
                            return false;
                        }
                    };
            var tasks = executor.invokeAll(List.of(task, task));
            int success = 0;
            for (var result : tasks) if (result.get(10, TimeUnit.SECONDS)) {
                success++;
            }
            assertThat(success).isEqualTo(1);
        }
        assertThat(couponRepository.findById(c.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(18);
    }

    @Test
    void expiredCouponAndInsufficientSpendAreRejected() {
        var c = coupon(1);
        c.setEndsAt(LocalDateTime.now().minusMinutes(1));
        couponRepository.saveAndFlush(c);
        assertThatThrownBy(() -> coupons.preview(c.getCode(), new BigDecimal("20"), Currency.CNY))
                .isInstanceOf(BusinessException.class);
        c.setEndsAt(LocalDateTime.now().plusDays(1));
        c.setMinimum(new BigDecimal("30"));
        couponRepository.saveAndFlush(c);
        assertThatThrownBy(() -> coupons.preview(c.getCode(), new BigDecimal("20"), Currency.CNY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rolesProtectApiEndpoints() throws Exception {
        mvc.perform(get("/api/admin/coupons").with(user("catalog-test").roles("STAFF", "CATALOG")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products").with(user("catalog-test").roles("STAFF", "CATALOG")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/orders").with(user("support-test").roles("STAFF", "SUPPORT")))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/admin/orders/1/after-sales/1")
                                .with(user("support-test").roles("STAFF", "SUPPORT"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/admin/analytics").with(user("operations-test").roles("STAFF", "OPERATIONS")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/staff").with(user("operations-test").roles("STAFF", "OPERATIONS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffChangesRevokeExistingSessionsAndResponsesExcludeCredentials() throws Exception {
        String username = "staff" + UUID.randomUUID().toString().replace("-", "");
        String password = "Test-only-pass-123!";
        var created =
                mvc.perform(
                                post("/api/admin/staff")
                                        .with(user("owner-test").roles("ADMIN"))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                password,
                                                                "role",
                                                                "CATALOG",
                                                                "enabled",
                                                                true))))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain("password", password);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/admin/session")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/products").session(session)).andExpect(status().isOk());
        var employee = staff.findByUsername(username).orElseThrow();
        employee.setEnabled(false);
        employee.setCredentialVersion(employee.getCredentialVersion() + 1);
        staff.saveAndFlush(employee);
        mvc.perform(get("/api/admin/products").session(session)).andExpect(status().isUnauthorized());
        assertThat(
                        audit.findAll().stream()
                                .anyMatch(
                                        a -> a.getAction().contains("/api/admin/staff") && a.getActor().equals("owner-test")))
                .isTrue();
    }

    @Test
    void memberHistoryAndOrderAccessCannotCrossAccounts() throws Exception {
        var alice = member();
        var bob = member();
        var p = product();
        var f = form(null);
        f.setEmail(alice.getEmail());
        var order = placer.place(cart(p), f, Currency.CNY, false);
        mvc.perform(get("/api/member/orders").session(session(alice)))
                .andExpect(jsonPath("$.items[0].orderNo").value(order.getOrderNo()));
        mvc.perform(get("/api/member/orders").session(session(bob)))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(
                        post("/api/member/orders/" + order.getId() + "/access")
                                .session(session(bob))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void analyticsSeparatesCurrenciesAndNetRefunds() {
        var p = product();
        var order = placer.place(cart(p), form(null), Currency.CNY, false);
        var before =
                analytics.report(LocalDate.now(), LocalDate.now()).stream()
                        .filter(s -> s.currency() == Currency.CNY)
                        .findFirst()
                        .orElseThrow();
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orders.saveAndFlush(order);
        var paid =
                analytics.report(LocalDate.now(), LocalDate.now()).stream()
                        .filter(s -> s.currency() == Currency.CNY)
                        .findFirst()
                        .orElseThrow();
        assertThat(paid.paid().subtract(before.paid())).isEqualByComparingTo(order.getTotal());
        order.setStatus(OrderStatus.REFUNDED);
        orders.saveAndFlush(order);
        var refunded =
                analytics.report(LocalDate.now(), LocalDate.now()).stream()
                        .filter(s -> s.currency() == Currency.CNY)
                        .findFirst()
                        .orElseThrow();
        assertThat(refunded.refunded().subtract(paid.refunded()))
                .isEqualByComparingTo(order.getTotal());
        assertThat(refunded.net()).isEqualByComparingTo(refunded.paid().subtract(refunded.refunded()));
    }

    @Test
    void quoteAndOrderUseSameDiscountAndVerifiedMemberEmail() throws Exception {
        var alice = member();
        var bob = member();
        var c = coupon(2);
        var p = product();
        var items = List.of(Map.of("productId", p.getId(), "quantity", 2));
        var quote =
                mvc.perform(
                                post("/api/shipping/quote")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "country",
                                                                "CN",
                                                                "currency",
                                                                "CNY",
                                                                "items",
                                                                items,
                                                                "couponCode",
                                                                c.getCode()))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.discountAmount").value(5.0))
                        .andReturn();
        var placed =
                mvc.perform(
                                post("/api/orders")
                                        .session(session(alice))
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "customerName",
                                                                "Test",
                                                                "phone",
                                                                "12345678",
                                                                "country",
                                                                "CN",
                                                                "address",
                                                                "Test address",
                                                                "currency",
                                                                "CNY",
                                                                "items",
                                                                items,
                                                                "email",
                                                                bob.getEmail(),
                                                                "couponCode",
                                                                c.getCode()))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.couponCode").value(c.getCode()))
                        .andReturn();
        var quoteJson = json.readTree(quote.getResponse().getContentAsString());
        var orderJson = json.readTree(placed.getResponse().getContentAsString());
        assertThat(orderJson.get("total").decimalValue())
                .isEqualByComparingTo(quoteJson.get("total").decimalValue());
        assertThat(orders.findByOrderNo(orderJson.get("orderNo").asText()).orElseThrow().getEmail())
                .isEqualTo(alice.getEmail());
    }

    @Test
    void fullRefundReleasesCouponReservation() {
        var c = coupon(1);
        var p = product();
        var order = placer.place(cart(p), form(c.getCode()), Currency.CNY, true);
        orderService.markPaid(order.getOrderNo(), "cs_phase2");
        var request = care.request(order.getId(), "Refund");
        care.decide(order.getId(), request.id(), "APPROVE", "");
        Mockito.when(
                        stripe.refund(
                                ArgumentMatchers.any(),
                                ArgumentMatchers.eq(request.id().toString()),
                                ArgumentMatchers.isNull()))
                .thenReturn(new StripePaymentService.RefundState("re_phase2", "succeeded"));
        care.processRefunds();
        assertThat(couponRepository.findById(c.getId()).orElseThrow().getUsedCount()).isZero();
        assertThat(orders.findById(order.getId()).orElseThrow().isCouponReleased()).isTrue();
    }

    @Test
    void percentageCouponNeverDiscountsShippingOrExceedsItemAmount() {
        var c =
                coupons.save(
                        null,
                        "P" + UUID.randomUUID().toString().replace("-", ""),
                        "PERCENT",
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        Currency.CNY,
                        LocalDateTime.now().minusHours(1),
                        LocalDateTime.now().plusDays(1),
                        true,
                        5);
        assertThat(coupons.preview(c.getCode(), new BigDecimal("19.99"), Currency.CNY))
                .isEqualByComparingTo("19.99");
        var p = product();
        var order = placer.place(cart(p), form(c.getCode()), Currency.CNY, true);
        assertThat(order.getTotal()).isEqualByComparingTo(order.getShippingFee());
        if (order.getTotal().signum() == 0) {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        }
    }
}
