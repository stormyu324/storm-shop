package com.javastorm.shop.api;

import org.springframework.web.bind.annotation.PathVariable;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.service.OrderAccessService;
import com.javastorm.shop.service.OrderCareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/customer/orders")
public class CustomerOrderApi {
    private final OrderAccessService access;
    private final OrderRepository orders;
    private final ApiMapper mapper;
    private final OrderCareService care;

    public CustomerOrderApi(
            OrderAccessService access, OrderRepository orders, ApiMapper mapper, OrderCareService care) {
        this.access = access;
        this.orders = orders;
        this.mapper = mapper;
        this.care = care;
    }

    public record Lookup(
            @NotBlank @Size(max = 32) String orderNo, @NotBlank @Email @Size(max = 254) String email) {}

    public record Verification(
            @NotBlank @Size(max = 40) String challenge,
            @Pattern(regexp = "[0-9]{6}") @NotNull String code) {}

    public record Reason(@NotBlank @Size(max = 1000) String reason) {}

    public record ReturnShipment(@NotBlank @Size(max = 100) String carrier,
                                 @NotBlank @Size(max = 100) String trackingNumber) {}

    @GetMapping("/session")
    public Map<String, String> session(CsrfToken csrf) {
        return Map.of("csrf", csrf.getToken());
    }

    @PostMapping("/code")
    public Map<String, String> code(@Valid @RequestBody Lookup req, HttpServletRequest http) {
        http.getSession().removeAttribute("customerOrderId");
        http.getSession().removeAttribute("customerOrderExpiry");
        String id = access.request(req.orderNo(), req.email(), http.getRemoteAddr());
        http.getSession().setAttribute("orderChallenge", id);
        return Map.of(
                "challenge", id, "message", "信息匹配时将收到验证码 / A code will be sent if the details match.");
    }

    @PostMapping("/verify")
    public Map<String, Boolean> verify(
            @Valid @RequestBody Verification req, HttpServletRequest http) {
        HttpSession session = http.getSession();
        if (!req.challenge().equals(session.getAttribute("orderChallenge"))) {
            throw forbidden();
        }
        Long id = access.verify(req.challenge(), req.code());
        if (id == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "验证码无效或已过期 / Invalid or expired code");
        }
        http.changeSessionId();
        session.setAttribute("customerOrderId", id);
        session.setAttribute("customerOrderExpiry", Instant.now().plusSeconds(1800));
        session.removeAttribute("orderChallenge");
        return Map.of("verified", true);
    }

    private Long authorized(HttpSession session) {
        if (!(session.getAttribute("customerOrderId") instanceof Long id)
                || !(session.getAttribute("customerOrderExpiry") instanceof Instant until)
                || until.isBefore(Instant.now())) {
            throw forbidden();
        }
        return id;
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先验证邮箱 / Verify your email first");
    }

    @GetMapping("/current")
    public Map<String, Object> current(HttpSession session) {
        Long id = authorized(session);
        return Map.of(
                "order",
                mapper.adminOrder(orders.findById(id).orElseThrow(this::forbidden)),
                "cases",
                care.cases(id));
    }

    @PostMapping("/current/after-sales")
    public OrderCareService.CaseView request(@Valid @RequestBody Reason req, HttpSession session) {
        return care.request(authorized(session), req.reason());
    }

    /** 商家要求退货时，顾客寄出后填写物流单号。 */
    @PostMapping("/current/after-sales/{caseId}/return")
    public OrderCareService.CaseView submitReturn(@PathVariable Long caseId, @Valid @RequestBody ReturnShipment req,
                                                  HttpSession session) {
        return care.submitReturn(authorized(session), caseId, req.carrier(), req.trackingNumber());
    }

    @DeleteMapping("/session")
    public void logout(HttpSession session) {
        session.removeAttribute("customerOrderId");
        session.removeAttribute("customerOrderExpiry");
    }
}
