package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class AuditEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public String actor;

  @Column(length = 300)
  public String action;

  @Column(length = 300)
  public String target;

  public int result;
  public LocalDateTime createdAt = LocalDateTime.now();
}
