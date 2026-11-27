package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderPlacer;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证爆款单品高并发抢购和多商品交叉下单场景下，原子扣库存不超卖、不触发乐观锁冲突、不死锁。 */
@SpringBootTest
@ActiveProfiles("test")
class AtomicStockDeductionTest {

    @Autowired
    ProductRepository products;

    @Autowired
    OrderPlacer orderPlacer;

    @Test
    void concurrentHotSkuOrdersNeverOversellOrFailWithOptimisticLock() throws Exception {
        Product hot = new Product();
        hot.setName("爆款测试商品");
        hot.setSku("HOT-" + UUID.randomUUID().toString().substring(0, 8));
        hot.setPrice(new BigDecimal("99.00"));
        hot.setStock(10);
        hot.setStatus(ProductStatus.ON_SALE);
        hot = products.save(hot);
        Long productId = hot.getId();

        int buyers = 20;
        CountDownLatch ready = new CountDownLatch(buyers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger lowStock = new AtomicInteger();
        AtomicInteger unexpectedErrors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < buyers; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        Cart cart = new Cart();
                        cart.add(productId, 1);
                        orderPlacer.place(cart, checkoutForm("buyer" + idx), Currency.CNY, false);
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        if ("error.product.lowStock".equals(e.getCode())) {
                            lowStock.incrementAndGet();
                        } else {
                            unexpectedErrors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        unexpectedErrors.incrementAndGet();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(lowStock.get()).isEqualTo(10);
        assertThat(unexpectedErrors.get()).isZero();
        assertThat(products.findById(productId).orElseThrow().getStock()).isZero();
    }

    @Test
    void concurrentReverseOrderMultiItemCartsDoNotDeadlock() throws Exception {
        Product a = new Product();
        a.setName("交叉商品A");
        a.setSku("CROSS-A-" + UUID.randomUUID().toString().substring(0, 8));
        a.setPrice(new BigDecimal("50.00"));
        a.setStock(20);
        a.setStatus(ProductStatus.ON_SALE);
        a = products.save(a);

        Product b = new Product();
        b.setName("交叉商品B");
        b.setSku("CROSS-B-" + UUID.randomUUID().toString().substring(0, 8));
        b.setPrice(new BigDecimal("60.00"));
        b.setStock(20);
        b.setStatus(ProductStatus.ON_SALE);
        b = products.save(b);

        Long idA = a.getId();
        Long idB = b.getId();
        int pairs = 10;
        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < pairs * 2; i++) {
                boolean reverse = (i % 2 == 1);
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        Cart cart = new Cart();
                        if (reverse) {
                            cart.add(idB, 1);
                            cart.add(idA, 1);
                        } else {
                            cart.add(idA, 1);
                            cart.add(idB, 1);
                        }
                        orderPlacer.place(cart, checkoutForm("cross" + idx), Currency.CNY, false);
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(pairs * 2);
        assertThat(products.findById(idA).orElseThrow().getStock()).isZero();
        assertThat(products.findById(idB).orElseThrow().getStock()).isZero();
    }

    private static CheckoutForm checkoutForm(String name) {
        CheckoutForm form = new CheckoutForm();
        form.setCustomerName(name);
        form.setPhone("13800138000");
        form.setAddress("测试地址 1 号");
        form.setCountry("CN");
        form.setEmail(name + "@example.com");
        return form;
    }
}
