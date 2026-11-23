package com.javastorm.shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CustomerOrderController {
  @GetMapping("/orders/lookup")
  public String lookup() {
    return "store/order-lookup";
  }
}
