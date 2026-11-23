package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class MemberChallenge {
  @Id public String id;
  public String email;
  public String codeHash;
  public int attempts;
  public boolean used;
  public LocalDateTime expiresAt;
}
