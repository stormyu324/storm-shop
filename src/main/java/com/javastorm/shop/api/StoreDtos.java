package com.javastorm.shop.api;

import com.javastorm.shop.domain.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 公开接口（商城前台）的数据格式。 */
public final class StoreDtos {

    private StoreDtos() {
    }

    public record CategoryDto(Long id, String name) {
    }

    public record CountryDto(String code, String name) {
    }

    @Schema(description = "商品列表里的一项；同一款号的多个规格只显示一个")
    public record ProductSummary(
            Long id, String sku, String title, String mainImage,
            BigDecimal price, BigDecimal originalPrice, Currency currency,
            @Schema(description = "同款有多个规格且价格不同时为 true，price 是最低价（显示为“xx 起”）") boolean priceFrom,
            @Schema(description = "同款规格数，没有款号时为 1") int variantCount,
            boolean inStock, Long categoryId) {
    }

    public record VariantOption(String label, Long productId, boolean selected, boolean available) {
    }

    public record ProductDetail(
            Long id, String sku, String title, String description, List<String> bulletPoints,
            List<String> images, BigDecimal price, BigDecimal originalPrice, Currency currency,
            int stock, String brand, String color, String size, String material,
            CategoryDto category,
            @Schema(description = "可选颜色，点选后跳到对应规格（productId）") List<VariantOption> colors,
            @Schema(description = "可选尺码") List<VariantOption> sizes) {
    }

    public record CartItem(@NotNull Long productId, @Min(1) @Max(99) int quantity) {
    }

    public record QuoteRequest(
            @Schema(description = "不传时按语言：中文 CNY，英文 USD") Currency currency,
            @Schema(description = "寄往国家，ISO 两位代码，如 US", example = "US") String country,
            @NotEmpty @Size(max = 50) List<@Valid CartItem> items, @jakarta.validation.constraints.Email @Size(max = 254) String email) {
    }

    public record QuoteLine(Long productId, String title, int quantity, BigDecimal unitPrice, BigDecimal subtotal,
                            boolean available) {
    }

    public record Quote(List<QuoteLine> lines, BigDecimal subtotal, BigDecimal shipping, BigDecimal total,
                        Currency currency, String country,
                        @Schema(description = "false 表示不配送到这个国家") boolean deliverable,
                        @Schema(description = "还差多少可以包邮，没有包邮门槛或已包邮时为 null") BigDecimal freeShippingRemaining) {
    }

    public record OrderRequest(
            Currency currency,
            @NotNull String customerName, @NotNull String phone, @NotNull String address, String note,
            @NotNull @Schema(example = "US") String country,
            @NotEmpty @Size(max = 50) List<@Valid CartItem> items, @jakarta.validation.constraints.Email @Size(max = 254) String email) {
    }

    public record OrderCreated(
            String orderNo, String status, Currency currency,
            BigDecimal itemsTotal, BigDecimal shippingFee, BigDecimal total,
            @Schema(description = "在线支付时跳转到这个 Stripe 付款页；线下确认的订单为 null") String paymentUrl) {
    }
}
