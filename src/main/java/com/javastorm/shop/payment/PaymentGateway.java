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

    /** 查询支付状态；对需要捕获扣款的通道（如 PayPal APPROVED）自动完成捕获。 */
    GatewaySessionState retrieveOrCapture(String sessionId);

    /** 主动使未付款的支付会话失效（不支持的通道可留空）。 */
    default void expire(String sessionId) {
    }

    RefundResult refund(CustomerOrder order, String requestId, String refundId);

    record CheckoutResult(String sessionId, String url) {
    }

    record GatewaySessionState(String sessionId, String orderNo, boolean paid, boolean open) {
    }

    record RefundResult(String id, String status) {
    }
}
