package com.javastorm.shop.domain;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class MemberAddress {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long memberId;
  public String name;
  public String phone;
  public String country;

  @Column(length = 300)
  public String address;

  public boolean preferred;
  public boolean active = true;
}
