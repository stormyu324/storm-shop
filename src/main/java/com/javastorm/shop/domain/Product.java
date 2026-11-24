package com.javastorm.shop.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** 商品编码，批量导入时用它判断是新增还是更新。 */
    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** 划线原价，可为空。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal originalPrice;

    private int stock;

    /**
     * 长文本列的长度故意设为 MySQL VARCHAR 的上限以上，MySQL 会存成 TEXT（否则整行超过 65535 字节无法建表），
     * H2 仍是 VARCHAR。实际字数上限由 ProductForm 和导入校验控制。
     */
    static final int LONG_TEXT = 65536;

    @Column(length = LONG_TEXT)
    private String description;

    // ---------- 跨境 / 平台资料（均为可选） ----------

    /** 款号：同款不同颜色/尺码的商品填同一个款号，导出亚马逊时会生成父子变体。 */
    @Column(length = 64)
    private String parentSku;

    @Column(length = 200)
    private String titleEn;

    @Column(length = LONG_TEXT)
    private String descriptionEn;

    /** 英文卖点，每行一条，最多 5 条。 */
    @Column(length = LONG_TEXT)
    private String bulletPointsEn;

    @Column(length = 100)
    private String brand;

    @Column(length = 50)
    private String color;

    @Column(length = 50)
    private String size;

    @Column(length = 100)
    private String material;

    private Integer weightGrams;

    @Column(precision = 8, scale = 1)
    private BigDecimal lengthCm;

    @Column(precision = 8, scale = 1)
    private BigDecimal widthCm;

    @Column(precision = 8, scale = 1)
    private BigDecimal heightCm;

    /** UPC（12 位）/ EAN（13 位）/ GTIN（14 位）。 */
    @Column(length = 32)
    private String barcode;

    /** 美元售价；为空时按汇率从人民币价格换算。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal priceUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status = ProductStatus.DRAFT;

    /** 第一张图为主图。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_image")
    @OrderColumn(name = "position")
    @Column(name = "url", length = 1000)
    private List<String> imageUrls = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getMainImage() {
        return imageUrls.isEmpty() ? null : imageUrls.get(0);
    }

    public boolean isOnSale() {
        return status == ProductStatus.ON_SALE;
    }

    public boolean hasDiscount() {
        return originalPrice != null && originalPrice.compareTo(price) > 0;
    }

    public String title(boolean english) {
        return english && titleEn != null && !titleEn.isBlank() ? titleEn : name;
    }

    public String description(boolean english) {
        return english && descriptionEn != null && !descriptionEn.isBlank() ? descriptionEn : description;
    }

    public List<String> getBulletPointList() {
        if (bulletPointsEn == null) {
            return List.of();
        }
        return bulletPointsEn.lines().map(String::trim).filter(l -> !l.isEmpty()).limit(5).toList();
    }

    /** 根据条码位数判断类型，供平台导出使用。 */
    public String getBarcodeType() {
        if (barcode == null) {
            return null;
        }
        return switch (barcode.trim().length()) {
            case 12 -> "UPC";
            case 13 -> "EAN";
            case 14 -> "GTIN";
            default -> null;
        };
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(BigDecimal originalPrice) {
        this.originalPrice = originalPrice;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls.clear();
        this.imageUrls.addAll(imageUrls);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
    public String getParentSku() {
        return parentSku;
    }

    public void setParentSku(String parentSku) {
        this.parentSku = parentSku;
    }

    public String getTitleEn() {
        return titleEn;
    }

    public void setTitleEn(String titleEn) {
        this.titleEn = titleEn;
    }

    public String getDescriptionEn() {
        return descriptionEn;
    }

    public void setDescriptionEn(String descriptionEn) {
        this.descriptionEn = descriptionEn;
    }

    public String getBulletPointsEn() {
        return bulletPointsEn;
    }

    public void setBulletPointsEn(String bulletPointsEn) {
        this.bulletPointsEn = bulletPointsEn;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public Integer getWeightGrams() {
        return weightGrams;
    }

    public void setWeightGrams(Integer weightGrams) {
        this.weightGrams = weightGrams;
    }

    public BigDecimal getLengthCm() {
        return lengthCm;
    }

    public void setLengthCm(BigDecimal lengthCm) {
        this.lengthCm = lengthCm;
    }

    public BigDecimal getWidthCm() {
        return widthCm;
    }

    public void setWidthCm(BigDecimal widthCm) {
        this.widthCm = widthCm;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(BigDecimal heightCm) {
        this.heightCm = heightCm;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public BigDecimal getPriceUsd() {
        return priceUsd;
    }

    public void setPriceUsd(BigDecimal priceUsd) {
        this.priceUsd = priceUsd;
    }
}
