package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.web.form.CheckoutForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ProductRepository products;
    private final OrderRepository orders;
    private final Pricing pricing;

    public OrderService(ProductRepository products, OrderRepository orders, Pricing pricing) {
        this.products = products;
        this.orders = orders;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public CartView view(Cart cart, Currency currency) {
        List<CartView.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> e : cart.getItems().entrySet()) {
            Product p = products.findById(e.getKey()).orElse(null);
            if (p == null) {
                continue;
            }
            CartView.Line line = new CartView.Line(p, e.getValue(), pricing.price(p, currency));
            lines.add(line);
            total = total.add(line.getSubtotal());
        }
        return new CartView(lines, total, currency);
    }

    /** 下单并扣减库存；任一商品不可购买时整单失败。金额按 currency 结算。 */
    @Transactional
    public CustomerOrder placeOrder(Cart cart, CheckoutForm form, Currency currency) {
        return placeOrder(cart, form, currency, false);
    }

    /**
     * @param awaitingPayment true 时订单为“待付款”，等在线支付完成后变为“已付款”；
     *                        库存在下单时就扣减（锁定），超时未付会自动取消并退回库存
     */
    @Transactional
    public CustomerOrder placeOrder(Cart cart, CheckoutForm form, Currency currency, boolean awaitingPayment) {
        if (cart.isEmpty()) {
            throw new BusinessException("error.cart.empty", "购物车是空的");
        }
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(generateOrderNo());
        order.setCustomerName(form.getCustomerName().trim());
        order.setPhone(form.getPhone().trim());
        order.setAddress(form.getAddress().trim());
        order.setNote(form.getNote());
        order.setCurrency(currency);
        if (awaitingPayment) {
            order.setStatus(OrderStatus.PENDING_PAYMENT);
        }
        boolean english = currency == Currency.USD;

        for (Map.Entry<Long, Integer> e : cart.getItems().entrySet()) {
            Product p = products.findById(e.getKey())
                    .orElseThrow(() -> new BusinessException("error.cart.stale", "购物车中有商品已被删除，请刷新购物车"));
            int qty = e.getValue();
            String title = p.title(english);
            if (!p.isOnSale()) {
                throw new BusinessException("error.product.offShelf", "「" + p.getName() + "」已下架", title);
            }
            if (p.getStock() < qty) {
                throw new BusinessException("error.product.lowStock",
                        "「" + p.getName() + "」库存不足，仅剩 " + p.getStock() + " 件", title, p.getStock());
            }
            p.setStock(p.getStock() - qty);
            order.addItem(new OrderItem(p, title, pricing.price(p, currency), qty));
        }
        CustomerOrder saved = orders.save(order);
        // 事务提交成功后才清空购物车；提交失败（例如并发抢购导致版本冲突）时顾客的购物车保持不变
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cart.clear();
            }
        });
        return saved;
    }

    /** 时间 + 4 位随机数；同一秒内重复时重新生成。 */
    private String generateOrderNo() {
        String orderNo;
        do {
            orderNo = LocalDateTime.now().format(ORDER_NO_TIME)
                    + String.format(Locale.ROOT, "%04d", ThreadLocalRandom.current().nextInt(10000));
        } while (orders.existsByOrderNo(orderNo));
        return orderNo;
    }

    @Transactional
    public void attachPaymentSession(Long orderId, String sessionId) {
        orders.findById(orderId).ifPresent(o -> o.setPaymentSessionId(sessionId));
    }

    /**
     * 在线支付成功：待付款 → 已付款。重复通知（webhook 和返回页都会调用）直接忽略。
     * 已取消的订单又收到付款（极少见，例如超时取消的同时付款成功）时只记录日志，需要商家在 Stripe 后台退款。
     */
    @Transactional
    public Optional<CustomerOrder> markPaid(String orderNo, String sessionId) {
        Optional<CustomerOrder> found = orders.findByOrderNo(orderNo);
        found.ifPresent(order -> {
            if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                order.setStatus(OrderStatus.PAID);
                order.setPaidAt(LocalDateTime.now());
                order.setPaymentSessionId(sessionId);
            } else if (order.getStatus() == OrderStatus.CANCELLED && order.getPaidAt() == null) {
                log.error("订单 {} 已取消但收到了付款（Stripe session {}），请在 Stripe 后台退款", orderNo, sessionId);
            }
        });
        return found;
    }

    /** 取消未付款的订单并退回库存；返回订单里的商品，方便放回购物车。不是待付款状态时什么也不做。 */
    @Transactional
    public Map<Long, Integer> cancelUnpaid(String orderNo) {
        CustomerOrder order = orders.findByOrderNo(orderNo).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return Map.of();
        }
        Map<Long, Integer> items = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() != null) {
                items.merge(item.getProductId(), item.getQuantity(), Integer::sum);
                products.findById(item.getProductId()).ifPresent(p -> p.setStock(p.getStock() + item.getQuantity()));
            }
        }
        order.setStatus(OrderStatus.CANCELLED);
        return items;
    }

    @Transactional(readOnly = true)
    public List<CustomerOrder> unpaidOlderThan(LocalDateTime before) {
        return orders.findByStatusAndCreatedAtBefore(OrderStatus.PENDING_PAYMENT, before);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerOrder> findByOrderNo(String orderNo) {
        return orders.findByOrderNo(orderNo);
    }

    /** 修改订单状态；取消订单时把库存加回去。 */
    @Transactional
    public void changeStatus(Long orderId, OrderStatus status) {
        CustomerOrder order = orders.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (order.getStatus() == status) {
            return;
        }
        if (!order.getStatus().canChangeTo(status)) {
            throw new BusinessException("订单「" + order.getStatus().getLabel() + "」不能改为「" + status.getLabel() + "」");
        }
        if (status == OrderStatus.CANCELLED) {
            for (OrderItem item : order.getItems()) {
                if (item.getProductId() != null) {
                    products.findById(item.getProductId())
                            .ifPresent(p -> p.setStock(p.getStock() + item.getQuantity()));
                }
            }
        }
        order.setStatus(status);
    }
}
