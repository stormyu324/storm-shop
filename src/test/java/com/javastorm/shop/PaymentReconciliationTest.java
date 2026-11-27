package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.payment.PaymentReconciliationService;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.payment.StripePaymentService.SessionState;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.PaymentReconciliationRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderPlacer;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 Stripe 每日对账：自动补救掉单、捕获已取消却已收款高危异常及管理员核销流程。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentReconciliationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductRepository products;

    @Autowired
    OrderRepository orders;

    @Autowired
    OrderPlacer orderPlacer;

    @Autowired
    OrderService orderService;

    @Autowired
    PaymentReconciliationRepository reconciliationRepo;

    @MockBean
    StripePaymentService stripe;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void reconcilesDroppedWebhookAndFlagsCancelledButPaidDiscrepancy() throws Exception {
        reconciliationRepo.deleteAll();
        orders.deleteAll();
        when(stripe.isEnabled()).thenReturn(true);
        when(stripe.isEnabledFor(Currency.USD)).thenReturn(true);

        Product p = new Product();
        p.setName("对账测试商品");
        p.setSku("RECON-" + UUID.randomUUID().toString().substring(0, 8));
        p.setPrice(new BigDecimal("100.00"));
        p.setPriceUsd(new BigDecimal("15.00"));
        p.setStock(10);
        p.setStatus(ProductStatus.ON_SALE);
        p = products.save(p);

        // 1) 模拟 Webhook 丢包：本地 PENDING_PAYMENT，Stripe 已付款
        CustomerOrder dropped = placeOnline(p.getId(), "dropped@example.com", "cs_dropped_1");
        when(stripe.retrieve("cs_dropped_1"))
                .thenReturn(new SessionState("cs_dropped_1", dropped.getOrderNo(), true, false));

        // 2) 模拟高危资金异常：本地已取消，但 Stripe 已收款
        CustomerOrder cancelledPaid = placeOnline(p.getId(), "refundme@example.com", "cs_cancel_paid_2");
        orderService.cancelUnpaid(cancelledPaid.getOrderNo());
        when(stripe.retrieve("cs_cancel_paid_2"))
                .thenReturn(new SessionState("cs_cancel_paid_2", cancelledPaid.getOrderNo(), true, false));

        String today = LocalDate.now().toString();
        mvc.perform(post("/api/admin/reconciliation/run").param("date", today).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedOrders").value(2))
                .andExpect(jsonPath("$.autoRecovered").value(1))
                .andExpect(jsonPath("$.discrepancies").value(1));

        // 掉单已自动补救为 PAID
        assertThat(orders.findById(dropped.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);

        // 未处理异常列表应仅有 CANCELLED_BUT_PAID
        mvc.perform(get("/api/admin/reconciliation/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].issueType").value(PaymentReconciliationService.CANCELLED_BUT_PAID));

        Long issueId = reconciliationRepo.findAll().stream()
                .filter(i -> !i.isResolved())
                .findFirst()
                .orElseThrow()
                .getId();

        // 管理员在 Stripe 退款后标记核销
        mvc.perform(post("/api/admin/reconciliation/issues/" + issueId + "/resolve").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));
    }

    private CustomerOrder placeOnline(Long productId, String email, String sessionId) {
        Cart cart = new Cart();
        cart.add(productId, 1);
        CheckoutForm form = new CheckoutForm();
        form.setCustomerName("Recon User");
        form.setPhone("13800001111");
        form.setAddress("123 Main St");
        form.setCountry("US");
        form.setEmail(email);
        CustomerOrder order = orderPlacer.place(cart, form, Currency.USD, true);
        orderService.attachPaymentSession(order.getId(), sessionId);
        return orders.findById(order.getId()).orElseThrow();
    }
}
