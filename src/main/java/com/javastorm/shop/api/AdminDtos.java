package com.javastorm.shop.api;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.web.form.ProductForm;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 后台接口的数据格式。 */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record SessionInfo(boolean authenticated, String username,
                              @Schema(description = "CSRF 令牌：修改类请求放在请求头 X-XSRF-TOKEN") String csrfToken, List<String> roles) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CategoryRef(Long id, String name) {
    }

    public record AdminProduct(
            Long id, String sku, String name, CategoryRef category,
            BigDecimal price, BigDecimal originalPrice, int stock, String description,
            ProductStatus status, String statusLabel, List<String> imageUrls,
            String parentSku, String titleEn, String descriptionEn, List<String> bulletPointsEn,
            String brand, String color, String size, String material,
            Integer weightGrams, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
            String barcode, BigDecimal priceUsd,
            @Schema(description = "修改时原样传回，用来发现期间是否被订单等改动过") long version,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    @Schema(description = "新建或修改商品；只有 name、price 必填，sku 留空自动生成")
    public record ProductRequest(
            String name, String sku, Long categoryId, BigDecimal price, BigDecimal originalPrice, Integer stock,
            String description, ProductStatus status, List<String> imageUrls,
            String parentSku, String titleEn, String descriptionEn, List<String> bulletPointsEn,
            String brand, String color, String size, String material,
            Integer weightGrams, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
            String barcode, BigDecimal priceUsd,
            @Schema(description = "修改时必填：读取商品时得到的 version") Long version) {

        public ProductForm toForm(Long id) {
            ProductForm f = new ProductForm();
            f.setId(id);
            f.setVersion(version);
            f.setName(name);
            f.setSku(sku);
            f.setCategoryId(categoryId);
            f.setPrice(price);
            f.setOriginalPrice(originalPrice);
            f.setStock(stock == null ? 0 : stock);
            f.setDescription(description);
            f.setStatus(status == null ? ProductStatus.ON_SALE : status);
            f.setImageUrlsText(imageUrls == null ? "" : String.join("\n", imageUrls));
            f.setParentSku(parentSku);
            f.setTitleEn(titleEn);
            f.setDescriptionEn(descriptionEn);
            f.setBulletPointsEn(bulletPointsEn == null ? null : String.join("\n", bulletPointsEn));
            f.setBrand(brand);
            f.setColor(color);
            f.setSize(size);
            f.setMaterial(material);
            f.setWeightGrams(weightGrams);
            f.setLengthCm(lengthCm);
            f.setWidthCm(widthCm);
            f.setHeightCm(heightCm);
            f.setBarcode(barcode == null ? "" : barcode);
            f.setPriceUsd(priceUsd);
            return f;
        }
    }

    public record BatchRequest(@NotEmpty List<Long> ids,
                               @NotNull @Schema(description = "ON_SALE / OFF_SHELF / DRAFT 改状态，DELETE 删除") String action) {
    }

    public record BatchResult(int affected) {
    }

    public record ImageUpload(List<String> urls) {
    }

    /** @param id 订单明细 id（退货入库时用）；returnedQuantity 已退回入库的数量 */
    public record OrderLine(Long id, Long productId, String productName, String sku, BigDecimal unitPrice, int quantity,
                            BigDecimal subtotal, int returnedQuantity) {
    }

    public record AdminOrder(
            Long id, String orderNo, OrderStatus status, String statusLabel,
            @Schema(description = "后台可以改成的状态") List<OrderStatus> nextStatuses,
            Currency currency, BigDecimal itemsTotal, BigDecimal shippingFee, BigDecimal total,
            String customerName, String phone, String country, String address, String note,
            List<OrderLine> items, LocalDateTime createdAt, LocalDateTime paidAt,
            String email, String carrier, String trackingNumber, String trackingUrl, LocalDateTime shippedAt,
            BigDecimal discountAmount, String couponCode, boolean canConfirmOfflinePayment,
            @Schema(description = "累计已退款金额") BigDecimal refundedAmount,
            @Schema(description = "还能退款的金额") BigDecimal refundableAmount) {
    }

    public record StatusChange(@NotNull OrderStatus status) {
    }

    public record CategoryRequest(@NotBlank String name, String nameEn, int sortOrder) {
    }

    public record AdminCategory(Long id, String name, String nameEn, int sortOrder, long productCount) {
    }

    public record Fees(BigDecimal baseFee, Integer includedGrams, BigDecimal extraPerKg, BigDecimal freeThreshold) {
    }

    public record DefaultRule(boolean enabled,
                              @Schema(description = "只配送到分组里的国家") boolean zonesOnly,
                              BigDecimal baseFee, int includedGrams, BigDecimal extraPerKg, BigDecimal freeThreshold) {
    }

    public record Zone(Long id, String name, List<String> countries, BigDecimal baseFee, int includedGrams,
                       BigDecimal extraPerKg, BigDecimal freeThreshold) {
    }

    public record ZoneRequest(@NotBlank String name,
                              @NotEmpty @Schema(example = "[\"US\", \"CA\"]") List<String> countries,
                              BigDecimal baseFee, Integer includedGrams, BigDecimal extraPerKg, BigDecimal freeThreshold) {
    }

    public record ShippingSettings(Currency currency, DefaultRule defaultRule, List<Zone> zones) {
    }

    /** 编辑页上还没保存的表单内容，用来生成英文资料。 */
    public record DraftRequest(String name, Long categoryId, String description, String brand, String color,
                               String size, String material, Integer weightGrams, String lengthCm, String widthCm,
                               String heightCm, String titleEn) {
    }

    public record EnglishDraft(String titleEn, List<String> bulletPointsEn, String descriptionEn) {
    }
}
