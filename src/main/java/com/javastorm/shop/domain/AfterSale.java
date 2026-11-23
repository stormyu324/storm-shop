package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class AfterSale {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public Long orderId;

  @Column(length = 1000)
  public String reason;

  @Column(length = 1000)
  public String decisionNote;

  public String state = "REQUESTED";
  public String refundId;
  public int attempts;
  public int refundGeneration;
  public boolean providerFailed;
  public LocalDateTime nextAttempt = LocalDateTime.now();
  public LocalDateTime createdAt = LocalDateTime.now();
  public LocalDateTime updatedAt = LocalDateTime.now();
}
