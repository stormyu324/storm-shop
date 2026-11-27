package com.javastorm.shop.payment;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.PaymentReconciliationIssue;
import com.javastorm.shop.payment.PaymentGateway.GatewaySessionState;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.PaymentReconciliationRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只开 PayPal 时的订单安全：超时 / 顾客取消能退回库存，已取消的订单不会被扣钱，
 * PayPal 扣款处理中不算已付款。
 */
class PayPalOrderSafetyTest {

    static final String SESSION = "paypal:PP-1";
    static final String ORDER_NO = "202611270000000001";

    PaymentGateway paypal;
    OrderService orders;
    PaymentFlow flow;

    @BeforeEach
    void setUp() {
        paypal = mock(PaymentGateway.class);
        when(paypal.provider()).thenReturn("paypal");
        when(paypal.isEnabled()).thenReturn(true);
        when(paypal.ownsSession(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).startsWith("paypal:"));
        when(paypal.expiresRemotely()).thenReturn(false);
        orders = mock(OrderService.class);
        when(orders.cancelUnpaid(anyString())).thenReturn(Map.of(7L, 2));
        flow = new PaymentFlow(new PaymentGatewayRegistry(List.of(paypal)), orders);
    }

    private CustomerOrder order(OrderStatus status) {
        CustomerOrder o = new CustomerOrder();
        o.setOrderNo(ORDER_NO);
        o.setStatus(status);
        o.setPaymentSessionId(SESSION);
        return o;
    }

    private static GatewaySessionState state(boolean paid, boolean open, boolean pending) {
        return new GatewaySessionState(SESSION, ORDER_NO, paid, open, pending);
    }

    @Test
    void staleUnapprovedPayPalOrderIsCancelledEvenThoughPayPalCannotExpireIt() {
        when(orders.unpaidOlderThan(any())).thenReturn(List.of(order(OrderStatus.PENDING_PAYMENT)));
        when(paypal.capture(SESSION)).thenReturn(state(false, true, false)); // PayPal 上仍是 CREATED

        flow.cleanupStaleOrders();

        verify(orders).cancelUnpaid(ORDER_NO);
        verify(orders, never()).markPaid(anyString(), anyString());
    }

    @Test
    void staleApprovedPayPalOrderIsCapturedAndMarkedPaid() {
        when(orders.unpaidOlderThan(any())).thenReturn(List.of(order(OrderStatus.PENDING_PAYMENT)));
        when(paypal.capture(SESSION)).thenReturn(state(true, false, false));

        flow.cleanupStaleOrders();

        verify(orders).markPaid(ORDER_NO, SESSION);
        verify(orders, never()).cancelUnpaid(anyString());
    }

    @Test
    void staleOrderWithPendingCaptureIsLeftAlone() {
        when(orders.unpaidOlderThan(any())).thenReturn(List.of(order(OrderStatus.PENDING_PAYMENT)));
        when(paypal.capture(SESSION)).thenReturn(state(false, false, true));

        flow.cleanupStaleOrders();

        verify(orders, never()).markPaid(anyString(), anyString());
        verify(orders, never()).cancelUnpaid(anyString());
    }

    @Test
    void customerCancelOnPayPalRestoresStockAndCart() {
        when(orders.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(order(OrderStatus.PENDING_PAYMENT)));
        when(paypal.retrieve(SESSION)).thenReturn(state(false, true, false));
        Cart cart = new Cart();

        assertThat(flow.cancelByCustomer(ORDER_NO, cart)).isFalse();

        verify(orders).cancelUnpaid(ORDER_NO);
        verify(paypal, never()).capture(anyString());
        assertThat(cart.getItems()).containsEntry(7L, 2);
    }

    @Test
    void returningToCancelledOrderNeverCaptures() {
        when(paypal.retrieve(SESSION)).thenReturn(state(false, true, false)); // 顾客事后在 PayPal 上点了批准
        when(orders.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(order(OrderStatus.CANCELLED)));

        var result = flow.confirmReturn(SESSION);

        assertThat(result).hasValueSatisfying(r -> assertThat(r.paid()).isFalse());
        verify(paypal, never()).capture(anyString());
        verify(orders, never()).markPaid(anyString(), anyString());
    }

    @Test
    void returningToPendingOrderCaptures() {
        when(paypal.retrieve(SESSION)).thenReturn(state(false, true, false));
        when(paypal.capture(SESSION)).thenReturn(state(true, false, false));
        when(orders.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(order(OrderStatus.PENDING_PAYMENT)));
        when(orders.markPaid(ORDER_NO, SESSION)).thenReturn(Optional.of(order(OrderStatus.PAID)));

        var result = flow.confirmReturn(SESSION);

        assertThat(result).hasValueSatisfying(r -> assertThat(r.paid()).isTrue());
        verify(orders).markPaid(ORDER_NO, SESSION);
    }

    @Test
    void pendingCaptureIsNotMarkedPaidOnReturn() {
        when(paypal.retrieve(SESSION)).thenReturn(state(false, true, false));
        when(paypal.capture(SESSION)).thenReturn(state(false, false, true));
        when(orders.findByOrderNo(ORDER_NO)).thenReturn(Optional.of(order(OrderStatus.PENDING_PAYMENT)));

        var result = flow.confirmReturn(SESSION);

        assertThat(result).hasValueSatisfying(r -> assertThat(r.paid()).isFalse());
        verify(orders, never()).markPaid(anyString(), anyString());
    }

    @Test
    void adminCannotCancelWhilePaymentIsPending() {
        when(paypal.retrieve(SESSION)).thenReturn(state(false, false, true));

        assertThatThrownBy(() -> flow.beforeAdminCancel(order(OrderStatus.PENDING_PAYMENT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("处理中");
    }

    @Test
    void reconciliationOnlyQueriesAndNeverCapturesCancelledOrders() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        PaymentReconciliationRepository issues = mock(PaymentReconciliationRepository.class);
        when(issues.save(any(PaymentReconciliationIssue.class))).thenAnswer(i -> i.getArgument(0));
        CustomerOrder cancelled = order(OrderStatus.CANCELLED);
        when(orderRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .thenReturn(List.of(cancelled));
        when(paypal.isEnabledFor(any())).thenReturn(true);
        when(paypal.retrieve(SESSION)).thenReturn(state(false, true, false)); // 已批准、未扣款

        var service = new PaymentReconciliationService(orderRepository, orders,
                new PaymentGatewayRegistry(List.of(paypal)), issues);
        var summary = service.reconcileDate(LocalDate.now().minusDays(1));

        verify(paypal, never()).capture(anyString());
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.discrepancies()).isZero();
    }
}
