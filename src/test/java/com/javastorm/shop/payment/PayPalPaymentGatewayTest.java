package com.javastorm.shop.payment;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.domain.Product;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通过本地 Mock HTTP 服务完整验证 PayPal Orders v2：鉴权缓存、建单、回跳捕获扣款与退款。 */
class PayPalPaymentGatewayTest {

    private HttpServer server;
    private AtomicInteger tokenCalls;
    private final AtomicInteger captureCalls = new AtomicInteger();
    private volatile String captureStatus = "COMPLETED";
    private PayPalPaymentGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        tokenCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/v1/oauth2/token", exchange -> {
            tokenCalls.incrementAndGet();
            reply(exchange, 200, "{\"access_token\":\"pp_test_token\",\"expires_in\":3600}");
        });

        server.createContext("/v2/checkout/orders", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("POST".equals(method) && "/v2/checkout/orders".equals(path)) {
                reply(exchange, 201, """
                        {
                          "id": "PP-ORDER-100",
                          "status": "CREATED",
                          "links": [
                            {"rel": "approve", "href": "https://www.sandbox.paypal.com/checkoutnow?token=PP-ORDER-100"}
                          ]
                        }
                        """);
            } else if ("GET".equals(method) && "/v2/checkout/orders/PP-DECLINED".equals(path)) {
                reply(exchange, 200, """
                        {"id": "PP-DECLINED", "status": "APPROVED", "purchase_units": [{"reference_id": "ORD-2026-0002"}]}
                        """);
            } else if ("POST".equals(method) && "/v2/checkout/orders/PP-DECLINED/capture".equals(path)) {
                reply(exchange, 422, """
                        {"name": "UNPROCESSABLE_ENTITY", "details": [{"issue": "INSTRUMENT_DECLINED"}]}
                        """);
            } else if ("GET".equals(method) && "/v2/checkout/orders/PP-ORDER-100".equals(path)) {
                reply(exchange, 200, """
                        {
                          "id": "PP-ORDER-100",
                          "status": "APPROVED",
                          "purchase_units": [
                            {
                              "reference_id": "ORD-2026-0001",
                              "payments": {
                                "captures": [{"id": "CAP-999"}]
                              }
                            }
                          ]
                        }
                        """);
            } else if ("POST".equals(method) && "/v2/checkout/orders/PP-ORDER-100/capture".equals(path)) {
                captureCalls.incrementAndGet();
                reply(exchange, 201, """
                        {
                          "id": "PP-ORDER-100",
                          "status": "COMPLETED",
                          "purchase_units": [{
                            "reference_id": "ORD-2026-0001",
                            "payments": {"captures": [{"id": "CAP-999", "status": "%s"}]}
                          }]
                        }
                        """.formatted(captureStatus));
            } else {
                reply(exchange, 404, "{}");
            }
        });

        server.createContext("/v2/payments/captures/CAP-999/refund", exchange ->
                reply(exchange, 201, "{\"id\":\"REF-888\",\"status\":\"COMPLETED\"}"));

        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        gateway = new PayPalPaymentGateway("client-id", "client-secret", true, "USD", baseUrl);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fullPayPalCheckoutCaptureAndRefundFlowWorks() {
        assertThat(gateway.isEnabled()).isTrue();
        assertThat(gateway.isEnabledFor(Currency.USD)).isTrue();
        assertThat(gateway.isEnabledFor(Currency.CNY)).isFalse();

        CustomerOrder order = sampleOrder();

        // 1) 创建 PayPal 结账页
        PaymentGateway.CheckoutResult checkout = gateway.createCheckout(order, "https://shop.example.com", true);
        assertThat(checkout.sessionId()).isEqualTo("paypal:PP-ORDER-100");
        assertThat(checkout.url()).contains("PP-ORDER-100");
        assertThat(gateway.ownsSession(checkout.sessionId())).isTrue();

        // 2) 只查询：顾客已批准但不扣款
        PaymentGateway.GatewaySessionState queried = gateway.retrieve(checkout.sessionId());
        assertThat(queried.paid()).isFalse();
        assertThat(queried.open()).isTrue();
        assertThat(captureCalls.get()).isZero();

        // 3) 顾客批准回跳 -> 捕获扣款，扣款记录 COMPLETED 才算已付款
        PaymentGateway.GatewaySessionState state = gateway.capture(checkout.sessionId());
        assertThat(captureCalls.get()).isEqualTo(1);
        assertThat(state.paid()).isTrue();
        assertThat(state.orderNo()).isEqualTo("ORD-2026-0001");

        // 4) 退款
        order.setPaymentSessionId(checkout.sessionId());
        PaymentGateway.RefundResult refund = gateway.refund(order, "case-1", null);
        assertThat(refund.id()).isEqualTo("REF-888");
        assertThat(refund.status()).isEqualTo("succeeded");

        // OAuth2 令牌应只获取一次并命中缓存
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void pendingCaptureIsNotTreatedAsPaid() {
        captureStatus = "PENDING";
        PaymentGateway.GatewaySessionState state = gateway.capture("paypal:PP-ORDER-100");
        assertThat(state.paid()).isFalse();
        assertThat(state.pending()).isTrue();
        assertThat(state.open()).isFalse();
    }

    @Test
    void declinedCaptureIsNeitherPaidNorPending() {
        captureStatus = "DECLINED";
        PaymentGateway.GatewaySessionState state = gateway.capture("paypal:PP-ORDER-100");
        assertThat(state.paid()).isFalse();
        assertThat(state.pending()).isFalse();
    }

    @Test
    void expiredPayPalOrderIsClosedAndDoesNotTripCircuitBreaker() {
        // PayPal 上已作废的订单返回 404：视为没付款、也不能再付，不抛异常
        for (int i = 0; i < 20; i++) {
            PaymentGateway.GatewaySessionState state = gateway.retrieve("paypal:GONE-" + i);
            assertThat(state.paid()).isFalse();
            assertThat(state.open()).isFalse();
            assertThat(state.pending()).isFalse();
        }
        // 大量 404 之后熔断器仍是关闭的，正常结账不受影响
        assertThat(gateway.createCheckout(sampleOrder(), "https://shop.example.com", true).sessionId())
                .isEqualTo("paypal:PP-ORDER-100");
    }

    @Test
    void declinedPaymentsDoNotBlockOtherCustomersCheckout() {
        // 多位顾客的付款方式被 PayPal 拒绝（422）：是正常业务结果，不能把熔断器打开
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> gateway.capture("paypal:PP-DECLINED"))
                    .isInstanceOf(com.javastorm.shop.config.RemoteRejectedException.class);
        }
        assertThat(gateway.createCheckout(sampleOrder(), "https://shop.example.com", true).sessionId())
                .isEqualTo("paypal:PP-ORDER-100");
    }

    @Test
    void onlyNonRetryableClientErrorsCountAsRejections() {
        assertThat(PayPalPaymentGateway.isRejection(400)).isTrue();
        assertThat(PayPalPaymentGateway.isRejection(422)).isTrue();
        assertThat(PayPalPaymentGateway.isRejection(408)).isFalse();
        assertThat(PayPalPaymentGateway.isRejection(429)).isFalse();
        assertThat(PayPalPaymentGateway.isRejection(500)).isFalse();
    }

    private static CustomerOrder sampleOrder() {
        Product p = new Product();
        p.setName("Test Item");
        p.setSku("SKU-1");
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("ORD-2026-0001");
        order.setCurrency(Currency.USD);
        order.addItem(new OrderItem(p, "Test Item", new BigDecimal("29.90"), 1));
        return order;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
