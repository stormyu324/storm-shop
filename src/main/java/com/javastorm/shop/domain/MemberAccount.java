package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class MemberAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(unique = true, nullable = false, length = 254)
  public String email;

  public boolean enabled = true;
  public LocalDateTime createdAt = LocalDateTime.now();
}
