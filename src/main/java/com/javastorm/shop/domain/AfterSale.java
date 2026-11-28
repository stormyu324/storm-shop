package com.javastorm.shop.domain;

import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/** 售后申请（整单退款）：状态 REQUESTED → PROCESSING / MANUAL_REQUIRED → SUCCEEDED，或 REJECTED / FAILED。 */
@Entity
public class AfterSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(length = 1000)
    private String reason;

    @Column(length = 1000)
    private String decisionNote;

    private String state = "REQUESTED";

    private String refundId;

    private int attempts;

    private int refundGeneration;

    private boolean providerFailed;

    private LocalDateTime nextAttempt = LocalDateTime.now();

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 本次退款金额；为空表示退订单剩余可退的全部金额。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal refundAmount;

    /** 需要顾客先把商品寄回，收到后再退款。 */
    private boolean returnRequired;

    @Column(length = 100)
    private String returnCarrier;

    @Column(length = 100)
    private String returnTracking;

    /** 商家确认收到退货的时间。 */
    private LocalDateTime returnedAt;

    /** 还在处理中（没有驳回、也没有退款完成）。同一订单同时只能有一个处理中的售后单。 */
    public boolean isOpen() {
        return !"REJECTED".equals(state) && !"SUCCEEDED".equals(state);
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public boolean isReturnRequired() {
        return returnRequired;
    }

    public void setReturnRequired(boolean returnRequired) {
        this.returnRequired = returnRequired;
    }

    public String getReturnCarrier() {
        return returnCarrier;
    }

    public void setReturnCarrier(String returnCarrier) {
        this.returnCarrier = returnCarrier;
    }

    public String getReturnTracking() {
        return returnTracking;
    }

    public void setReturnTracking(String returnTracking) {
        this.returnTracking = returnTracking;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getRefundId() {
        return refundId;
    }

    public void setRefundId(String refundId) {
        this.refundId = refundId;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getRefundGeneration() {
        return refundGeneration;
    }

    public void setRefundGeneration(int refundGeneration) {
        this.refundGeneration = refundGeneration;
    }

    public boolean isProviderFailed() {
        return providerFailed;
    }

    public void setProviderFailed(boolean providerFailed) {
        this.providerFailed = providerFailed;
    }

    public LocalDateTime getNextAttempt() {
        return nextAttempt;
    }

    public void setNextAttempt(LocalDateTime nextAttempt) {
        this.nextAttempt = nextAttempt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
