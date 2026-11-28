package com.javastorm.shop.api.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import com.javastorm.shop.service.OrderCareService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** 退回入库的商品：订单明细 id 和数量。 */
    public record RestockLine(@NotNull Long itemId, @Min(0) int quantity) {}

    /**
     * @param action        APPROVE 批准 / REJECT 驳回 / RECEIVE 确认收到退货 / CONFIRM_MANUAL 确认已线下退款
     * @param amount        批准时的退款金额，不填表示退剩余全部（可部分退款）
     * @param requireReturn 批准时是否要求顾客先寄回商品
     * @param restock       批准（无需退货）或确认收到退货时，要重新入库的商品和数量
     */
    public record Decision(@NotBlank String action, @Size(max = 1000) String note,
                           @DecimalMin(value = "0.01") BigDecimal amount, boolean requireReturn,
                           @Size(max = 100) List<@Valid RestockLine> restock) {}

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
        Map<Long, Integer> restock = new LinkedHashMap<>();
        if (req.restock() != null) {
            req.restock().forEach(l -> restock.merge(l.itemId(), l.quantity(), Integer::sum));
        }
        care.decide(orderId, id, req.action(), req.note(), req.amount(), req.requireReturn(), restock);
    }
}
