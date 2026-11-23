package com.javastorm.shop.api;

import com.javastorm.shop.domain.*;
import com.javastorm.shop.repository.*;
import com.javastorm.shop.service.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/member")
public class MemberApi {
  private final MemberService members;
  private final OrderRepository orders;
  private final ProductRepository products;
  private final ApiMapper mapper;
  private final VariantService variants;

  public MemberApi(
      MemberService members,
      OrderRepository orders,
      ProductRepository products,
      ApiMapper mapper,
      VariantService variants) {
    this.members = members;
    this.orders = orders;
    this.products = products;
    this.mapper = mapper;
    this.variants = variants;
  }

  public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

  public record Verification(
      @NotBlank @Size(max = 40) String challenge,
      @NotNull @Pattern(regexp = "[0-9]{6}") String code) {}

  public record Address(
      @NotBlank @Size(max = 50) String name,
      @NotBlank @Pattern(regexp = "^[0-9+()\\- ]{6,30}$") String phone,
      @NotBlank @Size(min = 2, max = 2) String country,
      @NotBlank @Size(max = 300) String address,
      boolean preferred,
      boolean active) {}

  public record Favorite(boolean active) {}

  @GetMapping("/session")
  public Map<String, Object> session(HttpSession session, CsrfToken token) {
    var member = members.current(session);
    return Map.of(
        "authenticated",
        member != null,
        "email",
        member == null ? "" : member.email,
        "csrf",
        token.getToken());
  }

  @PostMapping("/code")
  public Map<String, String> code(
      @Valid @RequestBody EmailRequest request, HttpServletRequest http) {
    String id = members.requestCode(request.email(), http.getRemoteAddr());
    http.getSession().setAttribute("memberChallenge", id);
    return Map.of("challenge", id);
  }

  @PostMapping("/verify")
  public Map<String, Boolean> verify(
      @Valid @RequestBody Verification req, HttpServletRequest http) {
    var session = http.getSession();
    if (!req.challenge().equals(session.getAttribute("memberChallenge")))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    Long id;
    try {
      id = members.verify(req.challenge(), req.code());
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      id = members.verify(req.challenge(), req.code());
    }
    if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
    http.changeSessionId();
    session.setAttribute("memberId", id);
    session.setAttribute("memberExpiry", Instant.now().plusSeconds(86400));
    session.removeAttribute("memberChallenge");
    return Map.of("authenticated", true);
  }

  @DeleteMapping("/session")
  public void logout(HttpSession session) {
    session.removeAttribute("memberId");
    session.removeAttribute("memberExpiry");
    session.removeAttribute("customerOrderId");
    session.removeAttribute("customerOrderExpiry");
  }

  @GetMapping("/addresses")
  public List<MemberAddress> addresses(HttpSession session) {
    return members.addresses(members.require(session).id);
  }

  @PostMapping("/addresses")
  public MemberAddress create(@Valid @RequestBody Address req, HttpSession session) {
    return save(null, req, session);
  }

  @PutMapping("/addresses/{id}")
  public MemberAddress save(
      @PathVariable(required = false) Long id,
      @Valid @RequestBody Address req,
      HttpSession session) {
    return members.saveAddress(
        members.require(session).id,
        id,
        req.name(),
        req.phone(),
        req.country(),
        req.address(),
        req.preferred(),
        req.active());
  }

  @GetMapping("/orders")
  public PageDto<AdminDtos.AdminOrder> orders(
      HttpSession session, @RequestParam(defaultValue = "0") int page) {
    var m = members.require(session);
    return PageDto.of(
        orders.findByEmailIgnoreCase(
            m.email, PageRequest.of(Math.max(0, page), 20, Sort.by("id").descending())),
        mapper::adminOrder);
  }

  @PostMapping("/orders/{id}/access")
  public void accessOrder(@PathVariable Long id, HttpSession session) {
    var member = members.require(session);
    var order =
        orders
            .findById(id)
            .filter(o -> member.email.equalsIgnoreCase(o.getEmail()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    session.setAttribute("customerOrderId", order.getId());
    session.setAttribute("customerOrderExpiry", Instant.now().plusSeconds(1800));
  }

  @GetMapping("/favorites")
  public List<StoreDtos.ProductSummary> favorites(HttpSession session) {
    var list =
        products.findAllById(members.favorites(members.require(session).id)).stream()
            .filter(Product::isOnSale)
            .toList();
    var currency = com.javastorm.shop.config.LocaleConfig.currentCurrency();
    var families = variants.families(list, currency);
    return list.stream()
        .map(
            p ->
                mapper.summary(
                    p, currency, com.javastorm.shop.config.LocaleConfig.isEnglish(), families))
        .toList();
  }

  @PutMapping("/favorites/{productId}")
  public void favorite(
      @PathVariable Long productId, @RequestBody Favorite req, HttpSession session) {
    members.favorite(members.require(session).id, productId, req.active());
  }
}
