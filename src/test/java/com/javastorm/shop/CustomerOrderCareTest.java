package com.javastorm.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderAccess;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.domain.OrderMail;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.AfterSaleRepository;
import com.javastorm.shop.repository.OrderAccessRepository;
import com.javastorm.shop.repository.OrderMailRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.OrderAccessService;
import com.javastorm.shop.service.OrderCareService;
import com.javastorm.shop.service.OrderMailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"shop.mail.from=shop@example.invalid", "shop.demo-data=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerOrderCareTest {
    @Autowired OrderCareService care;
    @Autowired OrderAccessService access;
    @Autowired OrderMailService notifications;
    @Autowired OrderRepository orders;
    @Autowired ProductRepository products;
    @Autowired AfterSaleRepository cases;
    @Autowired OrderAccessRepository challenges;
    @Autowired OrderMailRepository mails;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean StripePaymentService stripe;
    @MockBean JavaMailSender sender;

    private CustomerOrder order(OrderStatus state, boolean online) {
        Product p = new Product();
        p.setName("Care test");
        p.setSku(UUID.randomUUID().toString());
        p.setPrice(new BigDecimal("12.00"));
        p.setStock(8);
        p.setStatus(ProductStatus.ON_SALE);
        products.saveAndFlush(p);
        CustomerOrder o = new CustomerOrder();
        o.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        o.setCustomerName("Test");
        o.setPhone("12345678");
        o.setAddress("Test address");
        o.setEmail(UUID.randomUUID() + "@example.invalid");
        o.setCountry("US");
        o.setStatus(state);
        o.addItem(new OrderItem(p, "Care test", new BigDecimal("12.00"), 2));
        if (online) {
            o.setPaymentSessionId("cs_care");
            o.setPaidAt(LocalDateTime.now());
        }
        return orders.saveAndFlush(o);
    }

    @Test
    void shippingIsValidatedAndNotifiedOnce() {
        CustomerOrder o = order(OrderStatus.PAID, false);
        assertThatThrownBy(() -> care.ship(o.getId(), "Carrier", "TRACK", "javascript:alert(1)"))
                .isInstanceOf(BusinessException.class);
        assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        care.ship(o.getId(), "Carrier", "TRACK", "https://example.invalid/track");
        care.ship(o.getId(), "Carrier", "TRACK", "https://example.invalid/track");
        CustomerOrder saved = orders.findById(o.getId()).orElseThrow();
        assertThat(saved.getShippedAt()).isNotNull();
        assertThat(saved.getTrackingNumber()).isEqualTo("TRACK");
        assertThat(
                        mails.findAll().stream().filter(m -> m.getEventKey().equals(o.getId() + ":SHIPPED")).count())
                .isEqualTo(1);
    }

    @Test
    void refundIsApprovedThenProcessedOnce() {
        CustomerOrder o = order(OrderStatus.PAID, true);
        Long product = o.getItems().get(0).getProductId();
        var request = care.request(o.getId(), "Changed mind");
        assertThatThrownBy(() -> care.request(o.getId(), "Duplicate"))
                .isInstanceOf(BusinessException.class);
        care.decide(o.getId(), request.id(), "APPROVE", "");
        when(stripe.refund(any(), eq(request.id().toString()), isNull()))
                .thenReturn(new StripePaymentService.RefundState("re_care", "succeeded"));
        care.processRefunds();
        care.processRefunds();
        assertThat(orders.findById(o.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(products.findById(product).orElseThrow().getStock()).isEqualTo(10);
        verify(stripe, times(1)).refund(any(), eq(request.id().toString()), isNull());
        assertThatThrownBy(() -> care.decide(o.getId(), request.id(), "APPROVE", ""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void manualRefundRequiresEvidenceAndDoesNotRestockShippedGoods() {
        CustomerOrder o = order(OrderStatus.SHIPPED, false);
        Long product = o.getItems().get(0).getProductId();
        var request = care.request(o.getId(), "Return");
        care.decide(o.getId(), request.id(), "APPROVE", "");
        assertThat(care.cases(o.getId()).get(0).state()).isEqualTo("MANUAL_REQUIRED");
        assertThatThrownBy(() -> care.decide(o.getId(), request.id(), "CONFIRM_MANUAL", ""))
                .isInstanceOf(BusinessException.class);
        care.decide(o.getId(), request.id(), "CONFIRM_MANUAL", "Bank reference test");
        assertThat(products.findById(product).orElseThrow().getStock()).isEqualTo(8);
        assertThat(orders.findById(o.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void verificationIsSingleUseAndRequiredForCustomerData() throws Exception {
        CustomerOrder o = order(OrderStatus.PLACED, false);
        mvc.perform(get("/api/customer/orders/current")).andExpect(status().isUnauthorized());
        MockHttpSession session = new MockHttpSession();
        String body =
                json.writeValueAsString(Map.of("orderNo", o.getOrderNo(), "email", o.getEmail()));
        var result =
                mvc.perform(
                                post("/api/customer/orders/code")
                                        .session(session)
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isOk())
                        .andReturn();
        String challenge =
                json.readTree(result.getResponse().getContentAsString()).get("challenge").asText();
        OrderMail message =
                mails.findAll().stream()
                        .filter(m -> m.getEventKey().equals("OTP:" + challenge))
                        .findFirst()
                        .orElseThrow();
        var match = Pattern.compile("[0-9]{6}").matcher(message.getBody());
        assertThat(match.find()).isTrue();
        String code = match.group();
        mvc.perform(
                        post("/api/customer/orders/verify")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("challenge", challenge, "code", code))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/customer/orders/current").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNo").value(o.getOrderNo()));
        assertThat(access.verify(challenge, code)).isNull();
        mvc.perform(get("/api/customer/orders/current").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/customer/orders/current/after-sales")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"reason\":\"Support request\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/customer/orders/current/after-sales")
                                .session(session)
                                .contentType("application/json")
                                .content("{\"reason\":\"Support request\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void codesExpireAndBadAttemptsAreBounded() {
        CustomerOrder o = order(OrderStatus.PLACED, false);
        String id = access.request(o.getOrderNo(), o.getEmail(), "test-" + UUID.randomUUID());
        OrderAccess row = challenges.findById(id).orElseThrow();
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        challenges.saveAndFlush(row);
        assertThat(access.verify(id, "000000")).isNull();
        String second = access.request(o.getOrderNo(), o.getEmail(), "test-" + UUID.randomUUID());
        for (int i = 0; i < 6; i++) {
            assertThat(access.verify(second, "bad")).isNull();
        }
        assertThat(challenges.findById(second).orElseThrow().getAttempts()).isEqualTo(5);
    }

    @Test
    void pendingRefundIsPolledWithoutCreatingAnotherRefund() {
        CustomerOrder o = order(OrderStatus.PAID, true);
        var request = care.request(o.getId(), "Refund pending test");
        care.decide(o.getId(), request.id(), "APPROVE", "");
        when(stripe.refund(any(), eq(request.id().toString()), isNull()))
                .thenReturn(new StripePaymentService.RefundState("re_pending", "pending"));
        care.processRefunds();
        assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        var pending = cases.findById(request.id()).orElseThrow();
        assertThat(pending.getRefundId()).isEqualTo("re_pending");
        pending.setNextAttempt(LocalDateTime.now().minusMinutes(1));
        cases.saveAndFlush(pending);
        when(stripe.refund(any(), eq(request.id().toString()), eq("re_pending")))
                .thenReturn(new StripePaymentService.RefundState("re_pending", "succeeded"));
        care.processRefunds();
        assertThat(orders.findById(o.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(care.cases(o.getId()).get(0).history()).hasSize(3);
    }

    @Test
    void mailDeliveryFailureIsRetriedAndSuccessIsNotResent() {
        CustomerOrder o = order(OrderStatus.PLACED, false);
        care.ship(o.getId(), "Carrier", "MAILTEST", null);
        OrderMail queued =
                mails.findAll().stream()
                        .filter(m -> m.getEventKey().equals(o.getId() + ":SHIPPED"))
                        .findFirst()
                        .orElseThrow();
        doThrow(new MailSendException("simulated"))
                .when(sender)
                .send(ArgumentMatchers.any(SimpleMailMessage.class));
        notifications.deliver();
        OrderMail failed = mails.findById(queued.getId()).orElseThrow();
        assertThat(failed.getSentAt()).isNull();
        assertThat(failed.getAttempts()).isEqualTo(1);
        failed.setNextAttempt(LocalDateTime.now().minusMinutes(1));
        mails.saveAndFlush(failed);
        doNothing()
                .when(sender)
                .send(ArgumentMatchers.any(SimpleMailMessage.class));
        notifications.deliver();
        assertThat(mails.findById(queued.getId()).orElseThrow().getSentAt()).isNotNull();
    }

    @Test
    void rejectsCrossOrderReviewAndRequiresAdmin() throws Exception {
        CustomerOrder first = order(OrderStatus.PLACED, false);
        CustomerOrder other = order(OrderStatus.PLACED, false);
        var request = care.request(first.getId(), "Support");
        assertThatThrownBy(() -> care.decide(other.getId(), request.id(), "APPROVE", ""))
                .isInstanceOf(BusinessException.class);
        mvc.perform(
                        post("/api/admin/orders/" + first.getId() + "/shipping")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"carrier\":\"Carrier\",\"trackingNumber\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }
}
