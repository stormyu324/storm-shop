package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class AfterSaleHistory {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long caseId;
  public String state;

  @Column(length = 1000)
  public String note;

  public LocalDateTime createdAt = LocalDateTime.now();
}
