package com.javastorm.shop.domain;

import java.util.List;

public enum OrderStatus {
    PLACED("待处理"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 允许改成的下一个状态：待处理 → 已发货 / 已取消；已发货 → 已完成 / 已取消（退货）；
     * 已完成、已取消为最终状态。
     */
    public List<OrderStatus> getNextStatuses() {
        return switch (this) {
            case PLACED -> List.of(SHIPPED, CANCELLED);
            case SHIPPED -> List.of(COMPLETED, CANCELLED);
            case COMPLETED, CANCELLED -> List.of();
        };
    }

    public boolean canChangeTo(OrderStatus next) {
        return getNextStatuses().contains(next);
    }

    public boolean isFinal() {
        return getNextStatuses().isEmpty();
    }
}
