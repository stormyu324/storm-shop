package com.javastorm.shop;

import com.javastorm.shop.domain.AfterSale;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.MemberAccount;
import com.javastorm.shop.domain.MemberAddress;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.AfterSaleRepository;
import com.javastorm.shop.repository.MemberAddressRepository;
import com.javastorm.shop.repository.MemberRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.MemberPrivacyService;
import com.javastorm.shop.service.OrderCareService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 第 5 阶段：部分退款、退货后退款并按件入库、会员导出和注销个人信息。 */
@SpringBootTest(properties = {"shop.mail.from=shop@example.invalid", "shop.demo-data=false",
        "shop.refunds.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundsReturnsPrivacyTest {

    @Autowired OrderCareService care;
    @Autowired OrderRepository orders;
    @Autowired ProductRepository products;
    @Autowired AfterSaleRepository cases;
    @Autowired MemberRepository members;
    @Autowired MemberAddressRepository addresses;
    @Autowired MemberPrivacyService privacy;
    @Autowired MockMvc mvc;
    @MockBean StripePaymentService stripe;
    @MockBean JavaMailSender sender;

    Product product;

    /** 两件 12.00 的商品，订单总额 24.00。 */
    private CustomerOrder order(OrderStatus state, boolean online, String email) {
        product = new Product();
        product.setName("退款测试");
        product.setSku(UUID.randomUUID().toString());
        product.setPrice(new BigDecimal("12.00"));
        product.setStock(8);
        product.setStatus(ProductStatus.ON_SALE);
        products.saveAndFlush(product);
        CustomerOrder o = new CustomerOrder();
        o.setOrderNo(UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        o.setCustomerName("张三");
        o.setPhone("13800000000");
        o.setAddress("北京市测试路 1 号");
        o.setEmail(email);
        o.setCountry("CN");
        o.setCurrency(Currency.CNY);
        o.setStatus(state);
        o.addItem(new OrderItem(product, "退款测试", new BigDecimal("12.00"), 2));
        if (online) {
            o.setPaymentSessionId("cs_partial");
        }
        o.setPaidAt(LocalDateTime.now());
        if (state == OrderStatus.SHIPPED || state == OrderStatus.COMPLETED) {
            o.setShippedAt(LocalDateTime.now());
        }
        return orders.saveAndFlush(o);
    }

    private CustomerOrder reload(CustomerOrder o) {
        return orders.findById(o.getId()).orElseThrow();
    }

    private int stock() {
        return products.findById(product.getId()).orElseThrow().getStock();
    }

    @Test
    void twoPartialRefundsThenTheOrderIsFullyRefunded() {
        CustomerOrder o = order(OrderStatus.PAID, false, "partial@example.invalid");

        var first = care.request(o.getId(), "少发了一件");
        care.decide(o.getId(), first.id(), "APPROVE", "退一件的钱", new BigDecimal("12.00"), false, Map.of());
        care.decide(o.getId(), first.id(), "CONFIRM_MANUAL", "支付宝转账 2026112801", null, false, Map.of());
        CustomerOrder afterFirst = reload(o);
        assertThat(afterFirst.getRefundedAmount()).isEqualByComparingTo("12.00");
        assertThat(afterFirst.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(afterFirst.getRefundableAmount()).isEqualByComparingTo("12.00");

        // 部分退款后还能再申请；金额不能超过剩余可退
        var second = care.request(o.getId(), "另一件也坏了");
        assertThatThrownBy(() -> care.decide(o.getId(), second.id(), "APPROVE", "", new BigDecimal("12.01"), false, Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("剩余可退");
        care.decide(o.getId(), second.id(), "APPROVE", "", null, false, Map.of());
        care.decide(o.getId(), second.id(), "CONFIRM_MANUAL", "转账 2026112802", null, false, Map.of());

        CustomerOrder done = reload(o);
        assertThat(done.getRefundedAmount()).isEqualByComparingTo("24.00");
        assertThat(done.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThatThrownBy(() -> care.request(o.getId(), "再来一次")).isInstanceOf(BusinessException.class);
    }

    @Test
    void onlinePartialRefundSendsOnlyThatAmountToStripe() {
        CustomerOrder o = order(OrderStatus.PAID, true, "online@example.invalid");
        var c = care.request(o.getId(), "价格调整");
        care.decide(o.getId(), c.id(), "APPROVE", "", new BigDecimal("5.50"), false, Map.of());
        when(stripe.refund(any(), eq(new BigDecimal("5.50")), eq(c.id().toString()), isNull()))
                .thenReturn(new StripePaymentService.RefundState("re_partial", "succeeded"));

        care.processRefunds();

        verify(stripe).refund(any(), eq(new BigDecimal("5.50")), eq(c.id().toString()), isNull());
        CustomerOrder after = reload(o);
        assertThat(after.getRefundedAmount()).isEqualByComparingTo("5.50");
        assertThat(after.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(cases.findById(c.id()).orElseThrow().getState()).isEqualTo("SUCCEEDED");
    }

    @Test
    void returnFirstThenRefundAndRestockOnlyWhatCameBack() {
        CustomerOrder o = order(OrderStatus.SHIPPED, false, "return@example.invalid");
        Long itemId = o.getItems().get(0).getId();
        int stockBefore = stock();

        var c = care.request(o.getId(), "尺码不合适，退一件");
        care.decide(o.getId(), c.id(), "APPROVE", "请寄回", new BigDecimal("12.00"), true, Map.of());
        assertThat(cases.findById(c.id()).orElseThrow().getState()).isEqualTo("AWAITING_RETURN");
        // 等退货期间不会退款，也不能发货
        assertThatThrownBy(() -> care.ship(o.getId(), "顺丰", "SF1", null)).isInstanceOf(BusinessException.class);

        var shipped = care.submitReturn(o.getId(), c.id(), "顺丰", "SF-RETURN-1");
        assertThat(shipped.state()).isEqualTo("RETURN_SHIPPED");
        assertThat(shipped.returnTracking()).isEqualTo("SF-RETURN-1");

        // 入库数量不能超过购买数量
        assertThatThrownBy(() -> care.decide(o.getId(), c.id(), "RECEIVE", "", null, false, Map.of(itemId, 3)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最多还能退回入库 2 件");
        care.decide(o.getId(), c.id(), "RECEIVE", "包装完好", null, false, Map.of(itemId, 1));

        AfterSale received = cases.findById(c.id()).orElseThrow();
        assertThat(received.getState()).isEqualTo("MANUAL_REQUIRED");
        assertThat(received.getReturnedAt()).isNotNull();
        assertThat(stock()).isEqualTo(stockBefore + 1);
        assertThat(reload(o).getItems().get(0).getReturnedQuantity()).isEqualTo(1);

        care.decide(o.getId(), c.id(), "CONFIRM_MANUAL", "已退款", null, false, Map.of());
        assertThat(reload(o).getRefundedAmount()).isEqualByComparingTo("12.00");
        assertThat(reload(o).getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void customerSubmitsReturnTrackingThroughTheOrderLookupApi() throws Exception {
        CustomerOrder o = order(OrderStatus.SHIPPED, false, "lookup-return@example.invalid");
        var c = care.request(o.getId(), "颜色不对");
        care.decide(o.getId(), c.id(), "APPROVE", "", null, true, Map.of());
        MockHttpSession session = new MockHttpSession();
        // 和订单查询页验证邮箱后一样的会话
        session.setAttribute("customerOrderId", o.getId());
        session.setAttribute("customerOrderExpiry", Instant.now().plusSeconds(600));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/customer/orders/current/after-sales/" + c.id() + "/return")
                        .with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"中通\",\"trackingNumber\":\"ZT123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RETURN_SHIPPED"))
                .andExpect(jsonPath("$.returnTracking").value("ZT123456"));
        mvc.perform(get("/api/customer/orders/current").session(session))
                .andExpect(jsonPath("$.order.refundableAmount").value(24.00))
                .andExpect(jsonPath("$.order.items[0].id").value(o.getItems().get(0).getId()))
                .andExpect(jsonPath("$.cases[0].returnCarrier").value("中通"));
        // 没登录这个订单的会话不能提交
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/customer/orders/current/after-sales/" + c.id() + "/return")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"x\",\"trackingNumber\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    private MemberAccount member(String email) {
        MemberAccount m = new MemberAccount();
        m.setEmail(email);
        m = members.saveAndFlush(m);
        MemberAddress a = new MemberAddress();
        a.setMemberId(m.getId());
        a.setName("张三");
        a.setPhone("13800000000");
        a.setCountry("CN");
        a.setAddress("北京市测试路 1 号");
        addresses.saveAndFlush(a);
        return m;
    }

    private MockHttpSession loggedIn(MemberAccount m, Instant loginAt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("memberId", m.getId());
        session.setAttribute("memberExpiry", loginAt.plusSeconds(86400));
        return session;
    }

    @Test
    void memberCanExportAllPersonalData() throws Exception {
        String email = "export-" + UUID.randomUUID() + "@example.invalid";
        MemberAccount m = member(email);
        CustomerOrder o = order(OrderStatus.COMPLETED, false, email);

        mvc.perform(get("/api/member/export").session(loggedIn(m, Instant.now())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.account.email").value(email))
                .andExpect(jsonPath("$.addresses[0].address").value("北京市测试路 1 号"))
                .andExpect(jsonPath("$.orders[0].orderNo").value(o.getOrderNo()))
                .andExpect(jsonPath("$.orders[0].phone").value("13800000000"));
    }

    @Test
    void deletingAccountAnonymizesOrdersAndRemovesPersonalData() throws Exception {
        String email = "delete-" + UUID.randomUUID() + "@example.invalid";
        MemberAccount m = member(email);
        CustomerOrder open = order(OrderStatus.SHIPPED, false, email);

        // 需要输入 DELETE；登录太久要重新验证；还有未完成订单时不能注销
        mvc.perform(delete("/api/member/account").with(csrf()).session(loggedIn(m, Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"yes\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/member/account").with(csrf()).session(loggedIn(m, Instant.now().minusSeconds(3600)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"DELETE\"}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> privacy.delete(m.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("还没有完成");

        open.setStatus(OrderStatus.COMPLETED);
        orders.saveAndFlush(open);
        mvc.perform(delete("/api/member/account").with(csrf()).session(loggedIn(m, Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"DELETE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymizedOrders").value(1));

        assertThat(members.findById(m.getId())).isEmpty();
        assertThat(addresses.findByMemberId(m.getId())).isEmpty();
        CustomerOrder kept = reload(open);
        assertThat(kept.getOrderNo()).isEqualTo(open.getOrderNo());
        assertThat(kept.getTotal()).isEqualByComparingTo("24.00");
        assertThat(kept.getCustomerName()).isEqualTo("已注销用户");
        assertThat(kept.getPhone()).isEqualTo("-");
        assertThat(kept.getAddress()).isEqualTo("-");
        assertThat(kept.getEmail()).isNull();
    }
}
