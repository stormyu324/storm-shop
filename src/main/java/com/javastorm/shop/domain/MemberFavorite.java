package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"memberId", "productId"}))
public class MemberFavorite {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long memberId;
  public Long productId;
  public boolean active = true;
}
