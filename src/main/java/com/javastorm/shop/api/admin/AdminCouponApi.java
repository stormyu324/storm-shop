package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.PageDto;
import com.javastorm.shop.domain.Coupon;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.repository.CouponRepository;
import com.javastorm.shop.service.CouponService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponApi {
    private final CouponService service;
    private final CouponRepository coupons;

    public AdminCouponApi(CouponService service, CouponRepository coupons) {
        this.service = service;
        this.coupons = coupons;
    }

    public record Request(
            @NotBlank @Size(max = 40) String code,
            @NotBlank String kind,
            @NotNull BigDecimal value,
            @NotNull BigDecimal minimum,
            @NotNull Currency currency,
            @NotNull LocalDateTime startsAt,
            @NotNull LocalDateTime endsAt,
            boolean enabled,
            int usageLimit) {}

    @GetMapping
    public PageDto<Coupon> list(@RequestParam(defaultValue = "0") int page) {
        return PageDto.of(
                coupons.findAll(PageRequest.of(Math.max(0, page), 20, Sort.by("id").descending())), c -> c);
    }

    @PostMapping
    public Coupon create(@Valid @RequestBody Request req) {
        return save(null, req);
    }

    @PutMapping("/{id}")
    public Coupon save(@PathVariable(required = false) Long id, @Valid @RequestBody Request req) {
        return service.save(
                id,
                req.code(),
                req.kind(),
                req.value(),
                req.minimum(),
                req.currency(),
                req.startsAt(),
                req.endsAt(),
                req.enabled(),
                req.usageLimit());
    }
}
