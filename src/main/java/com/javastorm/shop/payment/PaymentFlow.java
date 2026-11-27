package com.javastorm.shop.payment;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.payment.StripePaymentService.SessionState;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 把订单和 Stripe 付款串起来：开始付款、付款成功、顾客取消、webhook 通知、超时清理。
 * 所有入口都是幂等的，webhook 和返回页谁先到都没关系。
 */
@Service
public class PaymentFlow {

    private static final Logger log = LoggerFactory.getLogger(PaymentFlow.class);

    /** 付款页 30 分钟过期；再留一些余量给 webhook，之后由定时任务兜底取消。 */
    static final long STALE_AFTER_MINUTES = 45;

    private final PaymentGatewayRegistry gateways;
    private final OrderService orders;

    public PaymentFlow(PaymentGatewayRegistry gateways, OrderService orders) {
        this.gateways = gateways;
        this.orders = orders;
    }

    /** 为刚创建的待付款订单打开默认支付通道（Stripe 优先）；失败时取消订单、退回库存，并把商品放回购物车。 */
    public String startPayment(CustomerOrder order, Cart cart, String baseUrl, boolean english) {
        return startPayment(order, cart, baseUrl, english, null);
    }

    /** 为刚创建的待付款订单打开指定支付通道（stripe / paypal）；失败时取消订单并恢复购物车。 */
    public String startPayment(CustomerOrder order, Cart cart, String baseUrl, boolean english, String provider) {
        try {
            PaymentGateway gateway = gateways.resolve(provider, order.getCurrency());
            PaymentGateway.CheckoutResult checkout = gateway.createCheckout(order, baseUrl, english);
            orders.attachPaymentSession(order.getId(), checkout.sessionId());
            return checkout.url();
        } catch (BusinessException e) {
            restoreToCart(cart, orders.cancelUnpaid(order.getOrderNo()));
            throw e;
        }
    }

    /**
     * 顾客付款后跳回本站：按会话前缀路由至对应支付网关确认。
     * 需要服务端扣款的通道（PayPal）只有在本地订单仍为“待付款”、且就是这个付款会话时才扣款，
     * 已取消的订单即使顾客事后在 PayPal 上点了批准也不会被扣钱。
     */
    public Optional<PaidResult> confirmReturn(String sessionId) {
        PaymentGateway gateway = gateways.forSession(sessionId)
                .orElseThrow(() -> new BusinessException("无法识别的支付会话"));
        PaymentGateway.GatewaySessionState state = gateway.retrieve(sessionId);
        if (state.orderNo() == null) {
            return Optional.empty();
        }
        CustomerOrder local = orders.findByOrderNo(state.orderNo()).orElse(null);
        if (local == null) {
            return Optional.empty();
        }
        if (!state.paid() && local.getStatus() == OrderStatus.PENDING_PAYMENT
                && sessionId.equals(local.getPaymentSessionId())) {
            state = gateway.capture(sessionId);
        }
        PaymentGateway.GatewaySessionState result = state;
        Optional<CustomerOrder> order = result.paid()
                ? orders.markPaid(result.orderNo(), result.sessionId())
                : Optional.of(local);
        return order.map(o -> new PaidResult(o, result.paid()));
    }

    public record PaidResult(CustomerOrder order, boolean paid) {
    }

    public Optional<CustomerOrder> confirmReturnByOrder(String orderNo) {
        return orders.findByOrderNo(orderNo);
    }

    /**
     * 顾客在付款页点了返回：先让付款页失效，防止之后又被付款；如果其实已经付款成功，就按付款成功处理。
     * @return 已付款时返回 true
     */
    public boolean cancelByCustomer(String orderNo, Cart cart) {
        CustomerOrder order = orders.findByOrderNo(orderNo).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return order != null && order.getStatus() == OrderStatus.PAID;
        }
        String sessionId = order.getPaymentSessionId();
        if (sessionId != null) {
            Optional<PaymentGateway> gateway = enabledGateway(sessionId);
            if (gateway.isEmpty()) {
                // 支付通道已关闭，无法向平台核对是否已付款：不自动取消，留给后台人工处理
                log.warn("订单 {} 的支付通道未启用，顾客取消时无法核对付款状态，订单保持待付款", orderNo);
                return false;
            }
            gateway.get().expire(sessionId);
            PaymentGateway.GatewaySessionState state = gateway.get().retrieve(sessionId);
            if (state.paid()) {
                orders.markPaid(orderNo, state.sessionId());
                return true;
            }
            if (state.pending()) {
                // 钱可能稍后到账，不能取消；由定时任务继续核对
                return false;
            }
        }
        restoreToCart(cart, orders.cancelUnpaid(orderNo));
        return false;
    }

    /** 处理 Stripe webhook。 */
    public void handleWebhook(StripePaymentService.WebhookEvent event) {
        SessionState s = event.session();
        if (s == null || s.orderNo() == null) {
            return;
        }
        switch (event.type()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                if (s.paid()) {
                    orders.markPaid(s.orderNo(), s.sessionId());
                }
            }
            case "checkout.session.expired", "checkout.session.async_payment_failed" -> orders.cancelUnpaid(s.orderNo());
            default -> {
                // 其他事件不需要处理
            }
        }
    }

    /** 后台取消待付款订单前，先让付款页失效；如果其实已付款，改为已付款并提示；付款处理中时不允许取消。 */
    public void beforeAdminCancel(CustomerOrder order) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || order.getPaymentSessionId() == null) {
            return;
        }
        Optional<PaymentGateway> gateway = enabledGateway(order.getPaymentSessionId());
        if (gateway.isEmpty()) {
            return;
        }
        gateway.get().expire(order.getPaymentSessionId());
        PaymentGateway.GatewaySessionState state = gateway.get().retrieve(order.getPaymentSessionId());
        if (state.paid()) {
            orders.markPaid(order.getOrderNo(), state.sessionId());
            throw new BusinessException("这个订单刚刚已经付款成功，已改为“已付款”，不能直接取消；如需取消请先在支付平台后台退款");
        }
        if (state.pending()) {
            throw new BusinessException("这笔付款正在支付平台处理中，暂时不能取消；请稍后再试，或到支付平台后台核查");
        }
    }

    /**
     * 兜底：webhook 没配置或没送达时，定时检查超时未付款的订单（Stripe / PayPal）。
     * PayPal 没有 webhook：顾客批准后没回到本站的订单在这里完成扣款；无法让 PayPal 付款页失效，
     * 所以超时仍未付款的直接在本地取消并退回库存（之后不会再为它扣款）。
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @SchedulerLock(name = "stripe_cleanup_stale_orders", lockAtMostFor = "PT9M")
    public void cleanupStaleOrders() {
        if (!gateways.anyEnabled()) {
            return;
        }
        for (CustomerOrder order : orders.unpaidOlderThan(LocalDateTime.now().minusMinutes(STALE_AFTER_MINUTES))) {
            try {
                String sessionId = order.getPaymentSessionId();
                if (sessionId == null) {
                    orders.cancelUnpaid(order.getOrderNo());
                    continue;
                }
                Optional<PaymentGateway> found = enabledGateway(sessionId);
                if (found.isEmpty()) {
                    continue; // 支付通道已关闭，无法核对，留给后台人工处理
                }
                PaymentGateway gateway = found.get();
                gateway.expire(sessionId);
                PaymentGateway.GatewaySessionState state = gateway.capture(sessionId);
                if (state.paid()) {
                    orders.markPaid(order.getOrderNo(), state.sessionId());
                } else if (state.pending()) {
                    log.info("订单 {} 的付款仍在支付平台处理中，下次再核对", order.getOrderNo());
                } else if (!state.open() || !gateway.expiresRemotely()) {
                    orders.cancelUnpaid(order.getOrderNo());
                }
            } catch (RuntimeException e) {
                log.warn("检查超时订单 {} 失败，下次再试", order.getOrderNo(), e);
            }
        }
    }

    private Optional<PaymentGateway> enabledGateway(String sessionId) {
        return gateways.forSession(sessionId).filter(PaymentGateway::isEnabled);
    }

    private static void restoreToCart(Cart cart, Map<Long, Integer> items) {
        if (cart != null) {
            items.forEach(cart::add);
        }
    }
}
