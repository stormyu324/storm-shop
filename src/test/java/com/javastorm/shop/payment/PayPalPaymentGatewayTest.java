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

/** 通过本地 Mock HTTP 服务完整验证 PayPal Orders v2：鉴权缓存、建单、回跳捕获扣款与退款。 */
class PayPalPaymentGatewayTest {

    private HttpServer server;
    private AtomicInteger tokenCalls;
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
                reply(exchange, 201, """
                        {
                          "id": "PP-ORDER-100",
                          "status": "COMPLETED",
                          "purchase_units": [{"reference_id": "ORD-2026-0001"}]
                        }
                        """);
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

        // 2) 顾客批准回跳 -> 自动捕获扣款并返回 paid=true
        PaymentGateway.GatewaySessionState state = gateway.retrieveOrCapture(checkout.sessionId());
        assertThat(state.paid()).isTrue();
        assertThat(state.orderNo()).isEqualTo("ORD-2026-0001");

        // 3) 退款
        order.setPaymentSessionId(checkout.sessionId());
        PaymentGateway.RefundResult refund = gateway.refund(order, "case-1", null);
        assertThat(refund.id()).isEqualTo("REF-888");
        assertThat(refund.status()).isEqualTo("succeeded");

        // OAuth2 令牌应只获取一次并命中缓存
        assertThat(tokenCalls.get()).isEqualTo(1);
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
