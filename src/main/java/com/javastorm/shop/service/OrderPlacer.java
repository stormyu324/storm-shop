package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.web.form.CheckoutForm;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 下单入口（网页和接口共用）。多人同时购买同一商品时，后提交的订单会遇到版本冲突，
 * 这里用最新库存重新下单：库存还够就成功，不够就得到“库存不足”的正常提示。
 */
@Component
public class OrderPlacer {

    static final int MAX_ATTEMPTS = 3;

    private final OrderService orderService;

    public OrderPlacer(OrderService orderService) {
        this.orderService = orderService;
    }

    public CustomerOrder place(Cart cart, CheckoutForm form, Currency currency, boolean awaitingPayment) {
        for (int attempt = 1; ; attempt++) {
            try {
                return orderService.placeOrder(cart, form, currency, awaitingPayment);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new BusinessException("error.order.busy", "下单人数较多，请稍后再试");
                }
            } catch (DataIntegrityViolationException e) {
                // 并发下单时两单碰巧生成了同一个订单号：整单已回滚（库存、优惠券都没动），换个订单号重试
                if (!isDuplicateOrderNo(e)) {
                    throw e;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new BusinessException("error.order.busy", "下单人数较多，请稍后再试");
                }
            }
        }
    }

    /** 只重试订单号唯一约束冲突；其他数据完整性错误照常抛出。H2 和 MySQL 的报错里都会带上列名或约束名。 */
    static boolean isDuplicateOrderNo(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("order_no")) {
                return true;
            }
        }
        return false;
    }
}
