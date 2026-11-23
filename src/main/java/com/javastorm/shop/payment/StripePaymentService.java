package com.javastorm.shop.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.service.BusinessException;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stripe Checkout 在线支付：下单后跳转到 Stripe 的付款页，付款结果通过 webhook 和返回页确认。
 * 设置 STRIPE_SECRET_KEY 后启用；默认只对美元订单（英文前台）启用，人民币订单仍是商家线下确认。
 */
@Service
public class StripePaymentService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stripe 允许的最短有效期是 30 分钟。 */
    public static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final StripeClient client;
    private final String webhookSecret;
    private final Set<Currency> currencies;

    public StripePaymentService(@Value("${shop.stripe.secret-key:}") String secretKey,
                                @Value("${shop.stripe.webhook-secret:}") String webhookSecret,
                                @Value("${shop.stripe.currencies:USD}") String currencies,
                                @Value("${shop.stripe.api-base:}") String apiBase) {
        if (StringUtils.hasText(secretKey)) {
            StripeClient.StripeClientBuilder builder = StripeClient.builder().setApiKey(secretKey.trim());
            if (StringUtils.hasText(apiBase)) {
                builder.setApiBase(apiBase.trim());
            }
            this.client = builder.build();
        } else {
            this.client = null;
        }
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.currencies = Arrays.stream(currencies.split(","))
                .map(String::trim).filter(StringUtils::hasText)
                .map(c -> Currency.valueOf(c.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled() {
        return client != null;
    }

    /** 这个币种的订单是否走在线支付。 */
    public boolean isEnabledFor(Currency currency) {
        return client != null && currencies.contains(currency);
    }

    public record Checkout(String sessionId, String url) {
    }

    /** Stripe 付款页的状态：paid=已付款；open=还能付款；都为 false 表示已过期或已关闭。 */
    public record SessionState(String sessionId, String orderNo, boolean paid, boolean open) {
    }

    public record WebhookEvent(String type, SessionState session) {
    }

    public record RefundState(String id, String status) {}
    public RefundState refund(CustomerOrder order, String requestId, String refundId) {
        requireEnabled();
        try {
            com.stripe.model.Refund refund;
            if (refundId != null) {
                refund = client.v1().refunds().retrieve(refundId);
            } else {
                Session session = client.v1().checkout().sessions().retrieve(order.getPaymentSessionId());
                if (!order.getOrderNo().equals(session.getClientReferenceId())
                        || !"paid".equals(session.getPaymentStatus()) || session.getPaymentIntent() == null) {
                    throw new BusinessException("支付信息与订单不一致，请核查");
                }
                var params = com.stripe.param.RefundCreateParams.builder()
                        .setPaymentIntent(session.getPaymentIntent()).setAmount(toMinorUnits(order.getTotal()))
                        .putMetadata("after_sale_id", requestId).build();
                var options = com.stripe.net.RequestOptions.builder().setIdempotencyKey("storm-refund-" + order.getOrderNo() + "-" + requestId).build();
                refund = client.v1().refunds().create(params, options);
            }
            return new RefundState(refund.getId(), refund.getStatus());
        } catch (StripeException e) {
            throw new BusinessException("退款服务暂不可用，请稍后重试或在支付平台核查");
        }
    }

    /** 为订单创建 Stripe 付款页，返回要跳转的网址。baseUrl 用来拼返回本站的地址。 */
    public Checkout createCheckout(CustomerOrder order, String baseUrl, boolean english) {
        requireEnabled();
        String currency = order.getCurrency().name().toLowerCase(Locale.ROOT);
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setClientReferenceId(order.getOrderNo())
                .putMetadata("order_no", order.getOrderNo())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("order_no", order.getOrderNo())
                        .build())
                .setSuccessUrl(baseUrl + "/checkout/paid?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/checkout/cancel")
                .setExpiresAt(Instant.now().plus(SESSION_TTL).plusSeconds(60).getEpochSecond())
                .setLocale(english ? SessionCreateParams.Locale.EN : SessionCreateParams.Locale.AUTO);
        if (order.getDiscountAmount().signum()>0) {
            BigDecimal discountedItems=order.getItemsTotal().subtract(order.getDiscountAmount());
            if(discountedItems.signum()>0) params.addLineItem(lineItem(english ? "Discounted items" : "优惠后商品",discountedItems,1,currency));
        } else for (OrderItem item : order.getItems()) {
            params.addLineItem(lineItem(item.getProductName() + (item.getSku() == null ? "" : " (" + item.getSku() + ")"),
                    item.getUnitPrice(), item.getQuantity(), currency));
        }
        if (order.getShippingFee() != null && order.getShippingFee().signum() > 0) {
            params.addLineItem(lineItem(english ? "Shipping" : "运费", order.getShippingFee(), 1, currency));
        }
        try {
            Session session = client.v1().checkout().sessions().create(params.build());
            return new Checkout(session.getId(), session.getUrl());
        } catch (StripeException e) {
            log.warn("创建 Stripe 付款页失败，订单 {}", order.getOrderNo(), e);
            throw new BusinessException("error.payment.unavailable", "在线支付暂时不可用，请稍后再试");
        }
    }

    private static SessionCreateParams.LineItem lineItem(String name, BigDecimal unitPrice, long quantity, String currency) {
        return SessionCreateParams.LineItem.builder()
                .setQuantity(quantity)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency)
                        .setUnitAmount(toMinorUnits(unitPrice))
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(name)
                                .build())
                        .build())
                .build();
    }

    /** 金额转为最小货币单位（美分 / 分）。 */
    static long toMinorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public SessionState retrieve(String sessionId) {
        requireEnabled();
        try {
            return state(client.v1().checkout().sessions().retrieve(sessionId));
        } catch (StripeException e) {
            log.warn("查询 Stripe 付款页失败 {}", sessionId, e);
            throw new BusinessException("error.payment.unavailable", "在线支付暂时不可用，请稍后再试");
        }
    }

    /** 让付款页立即失效（顾客取消或超时）；已经失效的忽略。 */
    public void expire(String sessionId) {
        requireEnabled();
        try {
            client.v1().checkout().sessions().expire(sessionId);
        } catch (InvalidRequestException e) {
            // 已过期、已完成等状态不能再 expire，调用方会重新查询状态
            log.info("Stripe 付款页 {} 无法设为过期：{}", sessionId, e.getMessage());
        } catch (StripeException e) {
            log.warn("让 Stripe 付款页过期失败 {}", sessionId, e);
            throw new BusinessException("error.payment.unavailable", "在线支付暂时不可用，请稍后再试");
        }
    }

    private static SessionState state(Session s) {
        String orderNo = s.getClientReferenceId() != null ? s.getClientReferenceId()
                : s.getMetadata() == null ? null : s.getMetadata().get("order_no");
        return new SessionState(s.getId(), orderNo, "paid".equals(s.getPaymentStatus()), "open".equals(s.getStatus()));
    }

    /** 校验签名并解析 webhook；签名不对时抛出 BusinessException。只处理 Checkout Session 相关事件。 */
    public WebhookEvent parseWebhook(String payload, String signatureHeader) {
        if (!StringUtils.hasText(webhookSecret)) {
            throw new BusinessException("未配置 STRIPE_WEBHOOK_SECRET");
        }
        try {
            Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new BusinessException("Stripe webhook 签名校验失败");
        }
        // 签名通过后直接读需要的几个字段，不依赖 SDK 与账户 API 版本是否一致
        try {
            JsonNode root = JSON.readTree(payload);
            String type = root.path("type").asText();
            JsonNode obj = root.path("data").path("object");
            if (!"checkout.session".equals(obj.path("object").asText())) {
                return new WebhookEvent(type, null);
            }
            String orderNo = obj.hasNonNull("client_reference_id") ? obj.path("client_reference_id").asText()
                    : obj.path("metadata").path("order_no").asText(null);
            return new WebhookEvent(type, new SessionState(obj.path("id").asText(), orderNo,
                    "paid".equals(obj.path("payment_status").asText()), "open".equals(obj.path("status").asText())));
        } catch (IOException e) {
            throw new BusinessException("Stripe webhook 内容无法解析");
        }
    }

    private void requireEnabled() {
        if (client == null) {
            throw new BusinessException("未配置 STRIPE_SECRET_KEY");
        }
    }
}
