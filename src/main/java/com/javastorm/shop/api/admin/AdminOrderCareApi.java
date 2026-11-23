package com.javastorm.shop.api.admin;

import com.javastorm.shop.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/orders/{orderId}")
public class AdminOrderCareApi {
  private final OrderCareService care;

  public AdminOrderCareApi(OrderCareService care) {
    this.care = care;
  }

  public record Shipping(
      @NotBlank @Size(max = 100) String carrier,
      @NotBlank @Size(max = 100) String trackingNumber,
      @Size(max = 254) String trackingUrl) {}

  public record Decision(@NotBlank String action, @Size(max = 1000) String note) {}

  public record Reason(@NotBlank @Size(max = 1000) String reason) {}

  @PostMapping("/shipping")
  public void ship(@PathVariable Long orderId, @Valid @RequestBody Shipping req) {
    care.ship(orderId, req.carrier(), req.trackingNumber(), req.trackingUrl());
  }

  @GetMapping("/after-sales")
  public List<OrderCareService.CaseView> cases(@PathVariable Long orderId) {
    return care.cases(orderId);
  }

  @PostMapping("/after-sales")
  public OrderCareService.CaseView request(
      @PathVariable Long orderId, @Valid @RequestBody Reason req) {
    return care.request(orderId, req.reason());
  }

  @PostMapping("/after-sales/{id}")
  public void decide(
      @PathVariable Long orderId, @PathVariable Long id, @Valid @RequestBody Decision req) {
    care.decide(orderId, id, req.action(), req.note());
  }
}
