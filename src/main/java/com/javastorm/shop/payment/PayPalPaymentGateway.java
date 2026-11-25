package com.javastorm.shop.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.config.ExternalCircuitBreakers;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.service.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PayPal Orders v2 在线支付通道（基于 JDK HttpClient，零额外 SDK 依赖）：
 * 支持 OAuth2 令牌缓存、创建结账订单、回跳自动捕获扣款与幂等退款。
 * 设置 {@code PAYPAL_CLIENT_ID} 和 {@code PAYPAL_CLIENT_SECRET} 后自动启用。
 */
@Component
@Order(2)
public class PayPalPaymentGateway implements PaymentGateway {

    public static final String SESSION_PREFIX = "paypal:";

    private static final Logger log = LoggerFactory.getLogger(PayPalPaymentGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String clientId;
    private final String clientSecret;
    private final String apiBase;
    private final Set<Currency> currencies;
    private final HttpClient http;
    private final ExternalCircuitBreakers breakers;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public PayPalPaymentGateway(
            @Value("${shop.paypal.client-id:}") String clientId,
            @Value("${shop.paypal.client-secret:}") String clientSecret,
            @Value("${shop.paypal.sandbox:true}") boolean sandbox,
            @Value("${shop.paypal.currencies:USD}") String currencies,
            @Value("${shop.paypal.api-base:}") String apiBase,
            ExternalCircuitBreakers breakers) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        if (StringUtils.hasText(apiBase)) {
            this.apiBase = stripTrailingSlash(apiBase.trim());
        } else {
            this.apiBase = sandbox ? "https://api-m.sandbox.paypal.com" : "https://api-m.paypal.com";
        }
        this.currencies = Arrays.stream(currencies.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(c -> Currency.valueOf(c.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.breakers = breakers == null ? new ExternalCircuitBreakers() : breakers;
    }

    public PayPalPaymentGateway(String clientId, String clientSecret, boolean sandbox, String currencies, String apiBase) {
        this(clientId, clientSecret, sandbox, currencies, apiBase, new ExternalCircuitBreakers());
    }

    @Override
    public String provider() {
        return "paypal";
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    @Override
    public boolean isEnabledFor(Currency currency) {
        return isEnabled() && currencies.contains(currency);
    }

    @Override
    public boolean ownsSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(SESSION_PREFIX);
    }

    @Override
    public CheckoutResult createCheckout(CustomerOrder order, String baseUrl, boolean english) {
        requireEnabled();
        String total = order.getTotal().setScale(2, RoundingMode.HALF_UP).toPlainString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "CAPTURE");
        body.put("purchase_units", List.of(Map.of(
                "reference_id", order.getOrderNo(),
                "custom_id", order.getOrderNo(),
                "amount", Map.of(
                        "currency_code", order.getCurrency().name(),
                        "value", total
                )
        )));
        body.put("application_context", Map.of(
                "return_url", stripTrailingSlash(baseUrl) + "/checkout/paypal/return",
                "cancel_url", stripTrailingSlash(baseUrl) + "/checkout/cancel",
                "user_action", "PAY_NOW"
        ));

        JsonNode res = postJson("/v2/checkout/orders", body, "storm-pp-create-" + order.getOrderNo());
        String paypalOrderId = res.path("id").asText("");
        String approveUrl = findLink(res, "approve");
        if (paypalOrderId.isBlank() || approveUrl == null) {
            throw new BusinessException("error.payment.unavailable", "创建 PayPal 支付页失败，请稍后重试");
        }
        return new CheckoutResult(SESSION_PREFIX + paypalOrderId, approveUrl);
    }

    @Override
    public GatewaySessionState retrieveOrCapture(String sessionId) {
        requireEnabled();
        String ppId = stripPrefix(sessionId);
        JsonNode orderNode = getJson("/v2/checkout/orders/" + ppId);
        String status = orderNode.path("status").asText("");
        if ("APPROVED".equalsIgnoreCase(status)) {
            orderNode = postJson("/v2/checkout/orders/" + ppId + "/capture", Map.of(), "storm-pp-cap-" + ppId);
            status = orderNode.path("status").asText("");
        }
        String orderNo = extractOrderNo(orderNode);
        boolean paid = "COMPLETED".equalsIgnoreCase(status);
        boolean open = "CREATED".equalsIgnoreCase(status) || "PAYER_ACTION_REQUIRED".equalsIgnoreCase(status)
                || "APPROVED".equalsIgnoreCase(status);
        return new GatewaySessionState(SESSION_PREFIX + ppId, orderNo, paid, open);
    }

    @Override
    public RefundResult refund(CustomerOrder order, String requestId, String refundId) {
        requireEnabled();
        if (refundId != null && !refundId.isBlank()) {
            JsonNode res = getJson("/v2/payments/refunds/" + refundId);
            return new RefundResult(res.path("id").asText(refundId), normalizeRefundStatus(res.path("status").asText("")));
        }
        String ppId = stripPrefix(order.getPaymentSessionId());
        JsonNode orderNode = getJson("/v2/checkout/orders/" + ppId);
        String captureId = extractCaptureId(orderNode);
        if (captureId == null) {
            throw new BusinessException("未找到 PayPal 收款记录，请在 PayPal 后台核查");
        }
        Map<String, Object> body = Map.of(
                "amount", Map.of(
                        "currency_code", order.getCurrency().name(),
                        "value", order.getTotal().setScale(2, RoundingMode.HALF_UP).toPlainString()
                )
        );
        JsonNode res = postJson("/v2/payments/captures/" + captureId + "/refund", body,
                "storm-pp-refund-" + order.getOrderNo() + "-" + requestId);
        return new RefundResult(res.path("id").asText(""), normalizeRefundStatus(res.path("status").asText("")));
    }

    private String accessToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
                return cachedToken;
            }
            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/v1/oauth2/token"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();
            JsonNode res = send(req);
            String token = res.path("access_token").asText("");
            long expiresIn = res.path("expires_in").asLong(300);
            if (token.isBlank()) {
                throw new BusinessException("error.payment.unavailable", "PayPal 鉴权失败");
            }
            cachedToken = token;
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(30, expiresIn - 60));
            return token;
        }
    }

    private JsonNode getJson(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(req);
    }

    private JsonNode postJson(String path, Map<String, Object> payload, String idempotencyKey) {
        try {
            String json = JSON.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                builder.header("PayPal-Request-Id", idempotencyKey);
            }
            return send(builder.build());
        } catch (IOException e) {
            throw new BusinessException("error.payment.unavailable", "序列化 PayPal 请求失败");
        }
    }

    private JsonNode send(HttpRequest req) {
        return breakers.execute("paypal", () -> {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    log.warn("PayPal API 调用失败 status={} uri={} body={}", res.statusCode(), req.uri(), res.body());
                    throw new BusinessException("error.payment.unavailable", "PayPal 支付服务暂时不可用，请稍后再试");
                }
                return JSON.readTree(res.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("error.payment.unavailable", "PayPal 支付请求被中断");
            } catch (IOException e) {
                log.warn("PayPal 网络异常 uri={}", req.uri(), e);
                throw new BusinessException("error.payment.unavailable", "PayPal 支付服务暂时不可用，请稍后再试");
            }
        }, "error.payment.unavailable", "PayPal 支付服务暂时不可用，请稍后再试");
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new BusinessException("PayPal 支付未配置");
        }
    }

    private static String stripPrefix(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        return sessionId.startsWith(SESSION_PREFIX) ? sessionId.substring(SESSION_PREFIX.length()) : sessionId;
    }

    private static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String findLink(JsonNode node, String rel) {
        for (JsonNode link : node.path("links")) {
            if (rel.equalsIgnoreCase(link.path("rel").asText(""))) {
                return link.path("href").asText(null);
            }
        }
        return null;
    }

    private static String extractOrderNo(JsonNode node) {
        JsonNode units = node.path("purchase_units");
        if (units.isArray() && !units.isEmpty()) {
            JsonNode first = units.get(0);
            String ref = first.path("reference_id").asText("");
            if (!ref.isBlank()) {
                return ref;
            }
            String custom = first.path("custom_id").asText("");
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return null;
    }

    private static String extractCaptureId(JsonNode node) {
        JsonNode units = node.path("purchase_units");
        if (units.isArray() && !units.isEmpty()) {
            JsonNode captures = units.get(0).path("payments").path("captures");
            if (captures.isArray() && !captures.isEmpty()) {
                String id = captures.get(0).path("id").asText("");
                return id.isBlank() ? null : id;
            }
        }
        return null;
    }

    private static String normalizeRefundStatus(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "succeeded";
            case "PENDING" -> "pending";
            default -> "failed";
        };
    }
}
