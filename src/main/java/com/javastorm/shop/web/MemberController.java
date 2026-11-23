package com.javastorm.shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MemberController {
  @GetMapping("/account")
  public String account() {
    return "store/account";
  }
}
