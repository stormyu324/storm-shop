package com.javastorm.shop.domain;

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
