package com.javastorm.shop.payment;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.payment.StripePaymentService.WebhookEvent;
import com.javastorm.shop.service.BusinessException;
import com.stripe.net.Webhook;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用本地假服务器代替 Stripe API，检查发出的参数；webhook 用 SDK 生成真实签名来校验。 */
class StripePaymentServiceTest {

    static final String WEBHOOK_SECRET = "whsec_test_secret";

    HttpServer server;
    final AtomicReference<String> lastForm = new AtomicReference<>();
    final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/checkout/sessions", exchange -> {
            lastPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            lastForm.set(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
            boolean retrieve = "GET".equals(exchange.getRequestMethod());
            String json = """
                    {"id":"cs_test_1","object":"checkout.session","url":"https://checkout.stripe.com/c/pay/cs_test_1",
                     "status":"%s","payment_status":"%s","client_reference_id":"ORDER-1","metadata":{"order_no":"ORDER-1"}}"""
                    .formatted(retrieve ? "complete" : "open", retrieve ? "paid" : "unpaid");
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    StripePaymentService service() {
        return new StripePaymentService("sk_test_123", WEBHOOK_SECRET, "USD",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void createsCheckoutWithOrderLinesAndShipping() {
        Product p = new Product();
        p.setSku("TEE-W-M");
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("ORDER-1");
        order.setCurrency(Currency.USD);
        order.addItem(new OrderItem(p, "Cotton Tee - White, M", new BigDecimal("12.99"), 2));
        order.addShippingFee(new BigDecimal("4.50"));

        var checkout = service().createCheckout(order, "https://shop.example.com", true);

        assertThat(checkout.sessionId()).isEqualTo("cs_test_1");
        assertThat(checkout.url()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        String form = lastForm.get();
        assertThat(form).contains("mode=payment")
                .contains("client_reference_id=ORDER-1")
                .contains("line_items[0][price_data][currency]=usd")
                .contains("line_items[0][price_data][unit_amount]=1299")
                .contains("line_items[0][quantity]=2")
                .contains("line_items[0][price_data][product_data][name]=Cotton Tee - White, M (TEE-W-M)")
                .contains("line_items[1][price_data][unit_amount]=450")
                .contains("line_items[1][price_data][product_data][name]=Shipping")
                .contains("success_url=https://shop.example.com/checkout/paid?session_id={CHECKOUT_SESSION_ID}")
                .contains("cancel_url=https://shop.example.com/checkout/cancel")
                .contains("expires_at=")
                .contains("payment_intent_data[metadata][order_no]=ORDER-1");
    }

    @Test
    void retrievesPaidState() {
        var state = service().retrieve("cs_test_1");
        assertThat(lastPath.get()).isEqualTo("GET /v1/checkout/sessions/cs_test_1");
        assertThat(state.paid()).isTrue();
        assertThat(state.open()).isFalse();
        assertThat(state.orderNo()).isEqualTo("ORDER-1");
    }

    @Test
    void verifiesWebhookSignature() throws Exception {
        String payload = """
                {"id":"evt_1","object":"event","type":"checkout.session.completed","data":{"object":
                {"id":"cs_test_1","object":"checkout.session","client_reference_id":"ORDER-1",
                 "status":"complete","payment_status":"paid"}}}""";
        String header = Webhook.Signature.generateSignatureHeader(payload, WEBHOOK_SECRET);

        WebhookEvent event = service().parseWebhook(payload, header);
        assertThat(event.type()).isEqualTo("checkout.session.completed");
        assertThat(event.session().orderNo()).isEqualTo("ORDER-1");
        assertThat(event.session().paid()).isTrue();

        String forged = Webhook.Signature.generateSignatureHeader(payload, "whsec_wrong");
        assertThatThrownBy(() -> service().parseWebhook(payload, forged)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().parseWebhook(payload.replace("paid", "unpaid"), header))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void currencySwitchAndMinorUnits() {
        StripePaymentService s = service();
        assertThat(s.isEnabledFor(Currency.USD)).isTrue();
        assertThat(s.isEnabledFor(Currency.CNY)).isFalse();
        assertThat(new StripePaymentService("", "", "USD,CNY", "").isEnabledFor(Currency.USD)).isFalse();
        assertThat(StripePaymentService.toMinorUnits(new BigDecimal("8.3"))).isEqualTo(830);
        assertThat(StripePaymentService.toMinorUnits(new BigDecimal("199.995"))).isEqualTo(20000);
    }
}
