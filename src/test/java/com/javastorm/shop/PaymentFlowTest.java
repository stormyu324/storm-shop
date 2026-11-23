package com.javastorm.shop;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.http.MediaType;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.payment.PaymentFlow;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.payment.StripePaymentService.Checkout;
import com.javastorm.shop.payment.StripePaymentService.SessionState;
import com.javastorm.shop.payment.StripePaymentService.WebhookEvent;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.PaymentController;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentFlowTest {

    static final String STRIPE_URL = "https://checkout.stripe.com/c/pay/cs_test_1";

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired PaymentFlow paymentFlow;
    @Autowired JdbcTemplate jdbc;
    @MockBean StripePaymentService stripe;

    Product tee;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        tee = new Product();
        tee.setName("纯棉T恤");
        tee.setTitleEn("Cotton Tee");
        tee.setSku("TEE-1");
        tee.setPrice(new BigDecimal("71.00"));
        tee.setPriceUsd(new BigDecimal("9.99"));
        tee.setStock(5);
        tee.setStatus(ProductStatus.ON_SALE);
        tee = products.save(tee);

        when(stripe.isEnabled()).thenReturn(true);
        when(stripe.isEnabledFor(Currency.USD)).thenReturn(true);
        when(stripe.createCheckout(any(), anyString(), anyBoolean())).thenReturn(new Checkout("cs_test_1", STRIPE_URL));
    }

    MockHttpSession englishCartWith(int qty) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(csrf()).param("lang", "en")
                .param("productId", tee.getId().toString()).param("quantity", String.valueOf(qty)));
        return session;
    }

    String checkout(MockHttpSession session) throws Exception {
        return mvc.perform(post("/checkout").session(session).with(csrf()).param("lang", "en")
                        .param("customerName", "John Smith").param("phone", "+1 415 555 0100")
                        .param("address", "1 Market St, San Francisco").param("country", "US"))
                .andReturn().getResponse().getRedirectedUrl();
    }

    CustomerOrder onlyOrder() {
        assertThat(orders.count()).isEqualTo(1);
        return orders.findAll().get(0);
    }

    int stock() {
        return products.findById(tee.getId()).orElseThrow().getStock();
    }

    @Test
    void usdCheckoutRedirectsToStripeAndReservesStock() throws Exception {
        MockHttpSession session = englishCartWith(2);
        assertThat(checkout(session)).isEqualTo(STRIPE_URL);

        CustomerOrder order = onlyOrder();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getCurrency()).isEqualTo(Currency.USD);
        assertThat(order.getPaymentSessionId()).isEqualTo("cs_test_1");
        assertThat(order.getTotal()).isEqualByComparingTo("19.98");
        assertThat(stock()).isEqualTo(3);
        assertThat(session.getAttribute(PaymentController.PENDING_ORDER)).isEqualTo(order.getOrderNo());
        assertThat(((Cart) session.getAttribute("scopedTarget.cart")).isEmpty()).isTrue();
    }

    @Test
    void returnFromStripeMarksOrderPaid() throws Exception {
        MockHttpSession session = englishCartWith(1);
        checkout(session);
        String orderNo = onlyOrder().getOrderNo();
        when(stripe.retrieve("cs_test_1")).thenReturn(new SessionState("cs_test_1", orderNo, true, false));

        mvc.perform(get("/checkout/paid").session(session).param("session_id", "cs_test_1").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment successful")))
                .andExpect(content().string(containsString(orderNo)));

        CustomerOrder order = onlyOrder();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(stock()).isEqualTo(4);

        // webhook 晚到也不会出问题
        paymentFlow.handleWebhook(new WebhookEvent("checkout.session.completed",
                new SessionState("cs_test_1", orderNo, true, false)));
        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void cancellingOnStripeRestoresStockAndCart() throws Exception {
        MockHttpSession session = englishCartWith(2);
        checkout(session);
        String orderNo = onlyOrder().getOrderNo();
        when(stripe.retrieve("cs_test_1")).thenReturn(new SessionState("cs_test_1", orderNo, false, false));

        mvc.perform(get("/checkout/cancel").session(session).param("lang", "en"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("message", containsString("Payment cancelled")));

        verify(stripe).expire("cs_test_1");
        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock()).isEqualTo(5);
        Cart cart = (Cart) session.getAttribute("scopedTarget.cart");
        assertThat(cart.getItems()).containsEntry(tee.getId(), 2);
    }

    @Test
    void stripeFailureCancelsOrderAndKeepsCart() throws Exception {
        when(stripe.createCheckout(any(), anyString(), anyBoolean()))
                .thenThrow(new BusinessException("error.payment.unavailable", "在线支付暂时不可用，请稍后再试"));
        MockHttpSession session = englishCartWith(1);

        mvc.perform(post("/checkout").session(session).with(csrf()).param("lang", "en")
                        .param("customerName", "John").param("phone", "+1 415 555 0100").param("address", "SF").param("country", "US"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Online payment is temporarily unavailable")));

        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock()).isEqualTo(5);
        assertThat(((Cart) session.getAttribute("scopedTarget.cart")).getItems()).containsEntry(tee.getId(), 1);
    }

    @Test
    void webhookExpiredCancelsAndBadSignatureIsRejected() throws Exception {
        CustomerOrder order = pendingOrder(2);
        when(stripe.parseWebhook(eq("payload"), eq("sig")))
                .thenReturn(new WebhookEvent("checkout.session.expired",
                        new SessionState("cs_x", order.getOrderNo(), false, false)));
        when(stripe.parseWebhook(eq("payload"), eq("bad"))).thenThrow(new BusinessException("Stripe webhook 签名校验失败"));

        mvc.perform(post("/stripe/webhook").content("payload").header("Stripe-Signature", "bad"))
                .andExpect(status().isBadRequest());
        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        // 没有 CSRF token 也能收到（Stripe 服务器发来的）
        mvc.perform(post("/stripe/webhook").content("payload").header("Stripe-Signature", "sig"))
                .andExpect(status().isOk());
        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void chineseStorefrontKeepsOfflineFlow() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(csrf()).param("productId", tee.getId().toString()));
        mvc.perform(post("/checkout").session(session).with(csrf())
                        .param("customerName", "张三").param("phone", "13800000000").param("address", "北京市").param("country", "CN"))
                .andExpect(redirectedUrl("/order-success"));
        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void staleUnpaidOrdersAreCleanedUp() {
        CustomerOrder order = pendingOrder(1);
        jdbc.update("update customer_order set created_at = ? where id = ?",
                LocalDateTime.now().minusHours(2), order.getId());
        when(stripe.retrieve("cs_x")).thenReturn(new SessionState("cs_x", order.getOrderNo(), false, false));

        paymentFlow.cleanupStaleOrders();

        verify(stripe).expire("cs_x");
        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCancelOfOrderPaidMeanwhileIsRefused() throws Exception {
        CustomerOrder order = pendingOrder(1);
        when(stripe.retrieve("cs_x")).thenReturn(new SessionState("cs_x", order.getOrderNo(), true, false));

        mvc.perform(post("/api/admin/orders/{id}/status", order.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("已经付款成功")));

        assertThat(onlyOrder().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stock()).isEqualTo(4);
    }

    private CustomerOrder pendingOrder(int qty) {
        Cart cart = new Cart();
        cart.add(tee.getId(), qty);
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("John");
        f.setPhone("+1 415 555 0100");
        f.setAddress("SF");
        CustomerOrder order = orderService.placeOrder(cart, f, Currency.USD, true);
        orderService.attachPaymentSession(order.getId(), "cs_x");
        return order;
    }
}
