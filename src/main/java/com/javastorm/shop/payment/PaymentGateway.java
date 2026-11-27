package com.javastorm.shop.payment;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;

/**
 * 统一在线支付网关抽象：屏蔽 Stripe、PayPal 等不同支付通道的协议差异。
 */
public interface PaymentGateway {

    /** 通道标识（如 stripe / paypal）。 */
    String provider();

    boolean isEnabled();

    boolean isEnabledFor(Currency currency);

    /** 判断某个 paymentSessionId 是否归属本通道。 */
    boolean ownsSession(String sessionId);

    CheckoutResult createCheckout(CustomerOrder order, String baseUrl, boolean english);

    /** 只查询支付状态，绝不触发扣款。对账、取消前核对等场景必须用它。 */
    GatewaySessionState retrieve(String sessionId);

    /**
     * 完成扣款：需要服务端捕获的通道（如 PayPal 顾客已批准但未捕获）在这里真正扣钱；
     * 其他通道等同于 {@link #retrieve}。只能在本地订单仍为“待付款”时调用。
     */
    default GatewaySessionState capture(String sessionId) {
        return retrieve(sessionId);
    }

    /** 主动使未付款的支付会话失效（不支持的通道可留空）。 */
    default void expire(String sessionId) {
    }

    /**
     * {@link #expire} 是否真的能让支付平台上的付款页失效。不能时（如 PayPal），
     * 本地取消后顾客仍可能在平台上点“批准”，但本地不会再为已取消订单扣款。
     */
    default boolean expiresRemotely() {
        return false;
    }

    RefundResult refund(CustomerOrder order, String requestId, String refundId);

    record CheckoutResult(String sessionId, String url) {
    }

    /**
     * @param paid    钱已确认到账
     * @param open    顾客还可以去付款（付款页仍有效）
     * @param pending 已扣款但支付平台仍在处理（如 PayPal 审核中），钱可能稍后到账，不能当已付款发货，也不能直接取消
     */
    record GatewaySessionState(String sessionId, String orderNo, boolean paid, boolean open, boolean pending) {
        public GatewaySessionState(String sessionId, String orderNo, boolean paid, boolean open) {
            this(sessionId, orderNo, paid, open, false);
        }
    }

    record RefundResult(String id, String status) {
    }
}
