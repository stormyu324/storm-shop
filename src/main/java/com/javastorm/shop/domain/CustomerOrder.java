package com.javastorm.shop.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long couponId;
    private String couponCode;
    @Column(precision=12,scale=2) private BigDecimal discountAmount;
    private Boolean couponReleased;
    public Long getCouponId() { return couponId; }
    public String getCouponCode() { return couponCode; }
    public BigDecimal getDiscountAmount() { return discountAmount==null ? BigDecimal.ZERO : discountAmount; }
    public boolean isCouponReleased() { return Boolean.TRUE.equals(couponReleased); }
    public void setCouponReleased(boolean value) { couponReleased=value; }
    public void applyDiscount(Long id,String code,BigDecimal amount) {
        couponId=id;couponCode=code;discountAmount=amount;couponReleased=false;total=total.subtract(amount);
    }

    private String email;
    public String getEmail() { return email; }
    public void setEmail(String value) { this.email = value; }
    private String carrier;
    public String getCarrier() { return carrier; }
    public void setCarrier(String value) { this.carrier = value; }
    private String trackingNumber;
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String value) { this.trackingNumber = value; }
    @Column(length = 1000)
    private String trackingUrl;
    public String getTrackingUrl() { return trackingUrl; }
    public void setTrackingUrl(String value) { this.trackingUrl = value; }
    private java.time.LocalDateTime shippedAt;
    public java.time.LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(java.time.LocalDateTime value) { this.shippedAt = value; }


    @Column(nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(nullable = false, length = 50)
    private String customerName;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(length = 500)
    private String note;

    /** 寄往国家 / 地区（ISO 两位代码）；老订单为空。 */
    @Column(length = 2)
    private String country;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    /** 下单时的结算币种；老订单为空，视为人民币。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PLACED;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    /** Stripe Checkout Session ID，线下确认的订单为空。 */
    @Column(length = 255)
    private String paymentSessionId;

    private LocalDateTime paidAt;

    /** 运费，按下单时的币种；老订单为空，视为 0。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal shippingFee;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void addItem(OrderItem item) {
        items.add(item);
        total = total.add(item.getSubtotal());
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Currency getCurrency() {
        return currency == null ? Currency.CNY : currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public String getPaymentSessionId() {
        return paymentSessionId;
    }

    public void setPaymentSessionId(String paymentSessionId) {
        this.paymentSessionId = paymentSessionId;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public BigDecimal getShippingFee() {
        return shippingFee == null ? BigDecimal.ZERO : shippingFee;
    }

    /** 商品金额（不含运费）。 */
    public BigDecimal getItemsTotal() {
        return total.add(getDiscountAmount()).subtract(getShippingFee());
    }

    /** 设置运费并计入订单总额；只能在下单时调用一次。 */
    public void addShippingFee(BigDecimal fee) {
        this.shippingFee = fee;
        this.total = this.total.add(fee);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
