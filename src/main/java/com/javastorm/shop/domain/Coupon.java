package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class Coupon {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(unique = true, nullable = false, length = 40)
  public String code;

  public String kind;

  @Column(name = "discount_value", precision = 12, scale = 2)
  public java.math.BigDecimal value;

  @Column(precision = 12, scale = 2)
  public java.math.BigDecimal minimum;

  @Enumerated(EnumType.STRING)
  public Currency currency;

  public LocalDateTime startsAt;
  public LocalDateTime endsAt;
  public boolean enabled = true;
  public int usageLimit;
  public int usedCount;
}
