package com.javastorm.shop.service;

import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 后台数据概览。 */
@Service
public class DashboardService {

    public static final int LOW_STOCK = 5;

    private final ProductRepository products;
    private final OrderRepository orders;

    public DashboardService(ProductRepository products, OrderRepository orders) {
        this.products = products;
        this.orders = orders;
    }

    public record Stats(long onSale, long drafts, long offShelf, long lowStock, int lowStockThreshold,
                        long ordersToFulfil, long awaitingPayment) {
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        return new Stats(
                products.countByStatus(ProductStatus.ON_SALE),
                products.countByStatus(ProductStatus.DRAFT),
                products.countByStatus(ProductStatus.OFF_SHELF),
                products.countByStockLessThanEqualAndStatus(LOW_STOCK, ProductStatus.ON_SALE),
                LOW_STOCK,
                orders.countByStatusIn(OrderStatus.toFulfil()),
                orders.countByStatus(OrderStatus.PENDING_PAYMENT));
    }
}
