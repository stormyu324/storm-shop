package com.javastorm.shop.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import java.time.Duration;
import com.javastorm.shop.service.MemberPrivacyService;
import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.MemberAddress;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.MemberService;
import com.javastorm.shop.service.VariantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/member")
public class MemberApi {
    private final MemberService members;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final ApiMapper mapper;
    private final VariantService variants;

    private final MemberPrivacyService privacy;

    /** 注销账户前必须在这么长时间内刚验证过邮箱：防止别人用一台没退出登录的电脑注销你的账户。 */
    static final Duration RECENT_LOGIN = Duration.ofMinutes(30);

    public MemberApi(
            MemberService members,
            OrderRepository orders,
            ProductRepository products,
            ApiMapper mapper,
            VariantService variants,
            MemberPrivacyService privacy) {
        this.privacy = privacy;
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
                member == null ? "" : member.getEmail(),
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
        if (!req.challenge().equals(session.getAttribute("memberChallenge"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Long id;
        try {
            id = members.verify(req.challenge(), req.code());
        } catch (DataIntegrityViolationException e) {
            id = members.verify(req.challenge(), req.code());
        }
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
        }
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
        return members.addresses(members.require(session).getId());
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
                members.require(session).getId(),
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
                        m.getEmail(), PageRequest.of(Math.max(0, page), 20, Sort.by("id").descending())),
                mapper::adminOrder);
    }

    @PostMapping("/orders/{id}/access")
    public void accessOrder(@PathVariable Long id, HttpSession session) {
        var member = members.require(session);
        var order =
                orders
                        .findById(id)
                        .filter(o -> member.getEmail().equalsIgnoreCase(o.getEmail()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        session.setAttribute("customerOrderId", order.getId());
        session.setAttribute("customerOrderExpiry", Instant.now().plusSeconds(1800));
    }

    @GetMapping("/favorites")
    public List<StoreDtos.ProductSummary> favorites(HttpSession session) {
        var list =
                products.findAllById(members.favorites(members.require(session).getId())).stream()
                        .filter(Product::isOnSale)
                        .toList();
        var currency = LocaleConfig.currentCurrency();
        var families = variants.families(list, currency);
        return list.stream()
                .map(
                        p ->
                                mapper.summary(
                                        p, currency, LocaleConfig.isEnglish(), families))
                .toList();
    }

    @PutMapping("/favorites/{productId}")
    public void favorite(
            @PathVariable Long productId, @RequestBody Favorite req, HttpSession session) {
        members.favorite(members.require(session).getId(), productId, req.active());
    }

    /** 下载我的全部个人信息（JSON）。 */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(HttpSession session) {
        var member = members.require(session);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"storm-my-data.json\"")
                .body(privacy.export(member.getId()));
    }

    public record DeleteAccount(@NotBlank String confirm) {}

    /** 注销账户：body 里 confirm 必须是 DELETE，且 30 分钟内刚验证过邮箱。 */
    @DeleteMapping("/account")
    public Map<String, Object> deleteAccount(@Valid @RequestBody DeleteAccount req, HttpServletRequest http) {
        HttpSession session = http.getSession();
        var member = members.require(session);
        if (!"DELETE".equals(req.confirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 DELETE 确认注销");
        }
        Instant expiry = (Instant) session.getAttribute("memberExpiry");
        Instant loggedInAt = expiry.minus(Duration.ofDays(1));
        if (loggedInAt.plus(RECENT_LOGIN).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "为了安全，请重新验证邮箱后再注销 / Please verify your email again");
        }
        int anonymized = privacy.delete(member.getId());
        session.invalidate();
        return Map.of("deleted", true, "anonymizedOrders", anonymized);
    }
}
