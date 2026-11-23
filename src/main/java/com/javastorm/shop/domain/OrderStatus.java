package com.javastorm.shop.domain;

import java.util.List;

public enum OrderStatus {
    PENDING_PAYMENT("待付款"),
    PLACED("待处理"),
    PAID("已付款"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    REFUNDED("已退款"),
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 后台可以手动改成的状态：
     * 待处理（线下确认）/ 已付款 → 已发货 / 已取消；已发货 → 已完成 / 已取消（退货）；
     * 待付款只能取消（付款成功由 Stripe 自动改为已付款）；已完成、已取消为最终状态。
     */
    public List<OrderStatus> getNextStatuses() {
        return switch (this) {
            case PENDING_PAYMENT -> List.of(CANCELLED);
            case PLACED, PAID -> List.of(SHIPPED, CANCELLED);
            case SHIPPED -> List.of(COMPLETED, CANCELLED);
            case COMPLETED, CANCELLED, REFUNDED -> List.of();
        };
    }

    public boolean canChangeTo(OrderStatus next) {
        return getNextStatuses().contains(next);
    }

    public boolean isFinal() {
        return getNextStatuses().isEmpty();
    }

    /** 需要商家处理（发货）的订单。 */
    public static List<OrderStatus> toFulfil() {
        return List.of(PLACED, PAID);
    }
}
