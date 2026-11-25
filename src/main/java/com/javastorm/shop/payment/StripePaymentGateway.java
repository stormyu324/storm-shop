package com.javastorm.shop.payment;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Stripe 通道适配器：委托给现有 {@link StripePaymentService}，保持 100% 向后兼容。 */
@Component
@Order(1)
public class StripePaymentGateway implements PaymentGateway {

    private final StripePaymentService stripe;

    public StripePaymentGateway(StripePaymentService stripe) {
        this.stripe = stripe;
    }

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public boolean isEnabled() {
        return stripe.isEnabled();
    }

    @Override
    public boolean isEnabledFor(Currency currency) {
        return stripe.isEnabledFor(currency);
    }

    @Override
    public boolean ownsSession(String sessionId) {
        return sessionId != null && !sessionId.startsWith(PayPalPaymentGateway.SESSION_PREFIX);
    }

    @Override
    public CheckoutResult createCheckout(CustomerOrder order, String baseUrl, boolean english) {
        StripePaymentService.Checkout checkout = stripe.createCheckout(order, baseUrl, english);
        return new CheckoutResult(checkout.sessionId(), checkout.url());
    }

    @Override
    public GatewaySessionState retrieveOrCapture(String sessionId) {
        StripePaymentService.SessionState state = stripe.retrieve(sessionId);
        return new GatewaySessionState(state.sessionId(), state.orderNo(), state.paid(), state.open());
    }

    @Override
    public void expire(String sessionId) {
        stripe.expire(sessionId);
    }

    @Override
    public RefundResult refund(CustomerOrder order, String requestId, String refundId) {
        StripePaymentService.RefundState state = stripe.refund(order, requestId, refundId);
        return new RefundResult(state.id(), state.status());
    }
}
