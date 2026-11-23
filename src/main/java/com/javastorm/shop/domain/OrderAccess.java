package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class OrderAccess {
  @Id public String id;
  public Long orderId;
  public String codeHash;
  public int attempts;
  public boolean consumed;
  public LocalDateTime expiresAt;
}
