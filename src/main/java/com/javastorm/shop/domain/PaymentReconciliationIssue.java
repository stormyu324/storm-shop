package com.javastorm.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日支付对账发现的异常或自动修复记录，供财务与管理员跟进核销。 */
@Entity
public class PaymentReconciliationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 32)
    private String orderNo;

    @Column(length = 255)
    private String paymentSessionId;

    @Column(nullable = false, length = 40)
    private String issueType;

    @Column(length = 1000)
    private String detail;

    private boolean resolved;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;

    public Long getId() {
        return id;
    }

    public LocalDate getRunDate() {
        return runDate;
    }

    public void setRunDate(LocalDate runDate) {
        this.runDate = runDate;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getPaymentSessionId() {
        return paymentSessionId;
    }

    public void setPaymentSessionId(String paymentSessionId) {
        this.paymentSessionId = paymentSessionId;
    }

    public String getIssueType() {
        return issueType;
    }

    public void setIssueType(String issueType) {
        this.issueType = issueType;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
