package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class StaffAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(unique = true, nullable = false, length = 80)
  public String username;

  @com.fasterxml.jackson.annotation.JsonIgnore public String passwordHash;
  public String role;
  public boolean enabled = true;
  public int credentialVersion;
  public LocalDateTime createdAt = LocalDateTime.now();
}
