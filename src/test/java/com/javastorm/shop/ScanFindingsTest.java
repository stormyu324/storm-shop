package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderMail;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.AfterSaleRepository;
import com.javastorm.shop.repository.OrderMailRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderCareService;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ProductImportService;
import com.javastorm.shop.service.ShippingSettingsService;
import com.javastorm.shop.service.ShippingSettingsService.Fees;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** 代码扫描（Antigravity）报告的问题，修复前都能用这里的场景复现。 */
@SpringBootTest(properties = {"shop.mail.from=shop@example.invalid", "shop.refunds.initial-delay-ms=3600000"})
@ActiveProfiles("test")
class ScanFindingsTest {

    @Autowired ProductRepository products;
    @Autowired OrderRepository orders;
    @Autowired OrderMailRepository mails;
    @Autowired OrderService orderService;
    @Autowired OrderCareService care;
    @Autowired AfterSaleRepository afterSales;
    @Autowired ProductImportService importer;
    @Autowired ShippingSettingsService shippingSettings;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean StripePaymentService stripe;

    Product product;

    @BeforeEach
    void setUp() {
        mails.deleteAll();
        orders.deleteAll();
        product = new Product();
        product.setName("热销杯子");
        product.setSku("SCAN-" + System.nanoTime());
        product.setPrice(new BigDecimal("10"));
        product.setStock(50);
        product.setStatus(ProductStatus.ON_SALE);
        product = products.save(product);
    }

    private CustomerOrder unpaidOrder(int quantity) {
        Cart cart = new Cart();
        cart.add(product.getId(), quantity);
        return orderService.placeOrder(cart, CodexFixesTest.form("buyer@example.com"), Currency.USD, true);
    }

    @Test
    void cancellingRestocksEvenIfAnotherOrderChangedTheProductMeanwhile() {
        CustomerOrder order = unpaidOrder(3);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(47);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.executeWithoutResult(status -> {
            // 取消开始时商品已在本事务里读出（旧版本号）……
            products.findById(product.getId()).orElseThrow();
            // ……此时另一位顾客下单扣了 2 件并先提交
            concurrent.executeWithoutResult(other -> {
                Product p = products.findById(product.getId()).orElseThrow();
                p.setStock(p.getStock() - 2);
            });
            orderService.cancelUnpaid(order.getOrderNo());
        });

        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(48);
    }

    @Test
    void changingTrackingAfterShipmentNotifiesCustomerAgain() {
        Cart cart = new Cart();
        cart.add(product.getId(), 1);
        CustomerOrder order = orderService.placeOrder(cart, CodexFixesTest.form("buyer@example.com"), Currency.CNY, false);

        care.ship(order.getId(), "顺丰", "SF001", null);
        care.ship(order.getId(), "顺丰", "SF001", null);
        assertThat(shippingMails(order)).hasSize(1);

        care.ship(order.getId(), "顺丰", "SF002", "https://example.invalid/SF002");
        List<OrderMail> sent = shippingMails(order);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getBody()).contains("SF002").contains("https://example.invalid/SF002");
        assertThat(orders.findById(order.getId()).orElseThrow().getTrackingNumber()).isEqualTo("SF002");
    }

    private List<OrderMail> shippingMails(CustomerOrder order) {
        return mails.findAll().stream()
                .filter(m -> m.getEventKey().startsWith(order.getId() + ":SHIPPED")
                        || m.getEventKey().startsWith(order.getId() + ":TRACKING_UPDATED"))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    @Test
    void stripeRefundCallHoldsNoDatabaseLocks() {
        Cart cart = new Cart();
        cart.add(product.getId(), 2);
        CustomerOrder order = orderService.placeOrder(cart, CodexFixesTest.form("buyer@example.com"), Currency.USD, true);
        order.setPaymentSessionId("cs_test_scan");
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orders.save(order);
        Long caseId = care.request(order.getId(), "不想要了").id();
        care.decide(order.getId(), caseId, "APPROVE", "同意");

        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<Exception> lockErrors = new ArrayList<>();
        when(stripe.refund(any(), any(), any(), isNull())).thenAnswer(call -> {
            // Stripe 响应期间，后台对这个订单和售后单的操作不应被锁住
            try {
                other.executeWithoutResult(status -> {
                    orders.findForUpdate(order.getId()).orElseThrow();
                    afterSales.lock(caseId).orElseThrow();
                });
            } catch (Exception e) {
                lockErrors.add(e);
            }
            return new StripePaymentService.RefundState("re_scan", "succeeded");
        });

        care.processRefunds();

        assertThat(lockErrors).isEmpty();
        assertThat(afterSales.findById(caseId).orElseThrow().getState()).isEqualTo("SUCCEEDED");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void importAcceptsDollarPricesAndReportsHugeAmountsAsRowErrors() {
        String sku = "IMP-" + System.nanoTime();
        String csv = "名称,SKU,售价\n"
                + "美元商品," + sku + "-A,$12.99\n"
                + "价格太大," + sku + "-B,123456789012\n"
                + "普通商品," + sku + "-C,¥35\n";

        var result = importer.importCsv(csv.getBytes(StandardCharsets.UTF_8), ProductStatus.ON_SALE);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("过大"));
        assertThat(products.findBySku(sku + "-A").orElseThrow().getPrice()).isEqualByComparingTo("12.99");
        assertThat(products.findBySku(sku + "-C")).isPresent();
    }

    @Test
    void enabledShippingRuleRequiresBaseFee() {
        assertThatThrownBy(() -> shippingSettings.saveDefault(Currency.USD, true, false, new Fees(null, 1000, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("首重运费");
        assertThatThrownBy(() -> shippingSettings.saveZone(Currency.USD, null, "北美", "US",
                new Fees(null, 1000, null, null)))
                .isInstanceOf(BusinessException.class);
        // 停用的默认规则不会被用来计算，可以不填
        shippingSettings.saveDefault(Currency.USD, false, false, new Fees(null, 1000, null, null));
    }

    @Test
    void importReportsHugeDimensionsAsRowErrors() {
        String sku = "DIM-" + System.nanoTime();
        String csv = "名称,SKU,售价,长(cm),宽(cm),高(cm)\n"
                + "正常尺寸," + sku + "-OK,35,30.5,20,10\n"
                + "尺寸超限," + sku + "-BIG,35,10000000,20,10\n";

        var result = importer.importCsv(csv.getBytes(StandardCharsets.UTF_8), ProductStatus.ON_SALE);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("过大"));
        assertThat(products.findBySku(sku + "-OK")).isPresent();
    }
}
