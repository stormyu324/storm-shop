package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class OrderMail {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(unique = true, nullable = false, length = 180)
  public String eventKey;

  @Column(nullable = false, length = 254)
  public String recipient;

  public String subject;

  @Column(length = 6000)
  public String body;

  public int attempts;
  public LocalDateTime nextAttempt = LocalDateTime.now();
  public LocalDateTime sentAt;
  public LocalDateTime expiresAt;
}
