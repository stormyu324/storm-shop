package com.javastorm.shop.service;

import com.javastorm.shop.domain.CustomerOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 线下收款（不走 Stripe）的订单下单时就扣了库存。超过设定时间仍未确认收款的，自动取消并退回库存，
 * 避免有人批量下单不付款把库存占光。时间由 OFFLINE_ORDER_CANCEL_HOURS 设置，0 表示不自动取消。
 */
@Component
public class OfflineOrderCleanup {

    private static final Logger log = LoggerFactory.getLogger(OfflineOrderCleanup.class);

    private final OrderService orders;
    private final long cancelAfterHours;

    public OfflineOrderCleanup(OrderService orders,
                               @Value("${shop.offline-orders.cancel-after-hours:72}") long cancelAfterHours) {
        this.orders = orders;
        this.cancelAfterHours = cancelAfterHours;
    }

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "${shop.offline-orders.initial-delay:PT5M}")
    public void cancelStale() {
        if (cancelAfterHours <= 0) {
            return;
        }
        for (CustomerOrder order : orders.unpaidOfflineOlderThan(LocalDateTime.now().minusHours(cancelAfterHours))) {
            try {
                if (orders.cancelStaleOffline(order.getId())) {
                    log.info("订单 {} 超过 {} 小时未收款，已自动取消并退回库存", order.getOrderNo(), cancelAfterHours);
                }
            } catch (RuntimeException e) {
                log.warn("自动取消超时订单 {} 失败，下次再试", order.getOrderNo(), e);
            }
        }
    }
}
