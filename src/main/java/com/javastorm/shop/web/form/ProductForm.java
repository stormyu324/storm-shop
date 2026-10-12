package com.javastorm.shop.web.form;

import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class ProductForm {

    private Long id;

    @NotBlank(message = "请填写商品名称")
    @Size(max = 200, message = "名称最多 200 字")
    private String name;

    /** 留空则自动生成。 */
    @Size(max = 64, message = "SKU 最多 64 个字符")
    private String sku;

    private Long categoryId;

    @NotNull(message = "请填写售价")
    @DecimalMin(value = "0.00", message = "售价不能为负数")
    private BigDecimal price;

    @DecimalMin(value = "0.00", message = "原价不能为负数")
    private BigDecimal originalPrice;

    @Min(value = 0, message = "库存不能为负数")
    private int stock;

    @Size(max = 10000, message = "描述最多 10000 字")
    private String description;

    @NotNull
    private ProductStatus status = ProductStatus.ON_SALE;

    // ---------- 跨境 / 平台资料 ----------
    @Size(max = 64, message = "款号最多 64 个字符")
    private String parentSku;

    @Size(max = 200, message = "英文标题最多 200 个字符")
    private String titleEn;

    @Size(max = 10000, message = "英文描述最多 10000 个字符")
    private String descriptionEn;

    @Size(max = 2500, message = "英文卖点最多 2500 个字符")
    private String bulletPointsEn;

    @Size(max = 100, message = "品牌最多 100 个字符")
    private String brand;

    @Size(max = 50, message = "颜色最多 50 个字符")
    private String color;

    @Size(max = 50, message = "尺码最多 50 个字符")
    private String size;

    @Size(max = 100, message = "材质最多 100 个字符")
    private String material;

    @Min(value = 0, message = "重量不能为负数")
    private Integer weightGrams;

    @DecimalMin(value = "0.0", message = "尺寸不能为负数")
    private BigDecimal lengthCm;

    @DecimalMin(value = "0.0", message = "尺寸不能为负数")
    private BigDecimal widthCm;

    @DecimalMin(value = "0.0", message = "尺寸不能为负数")
    private BigDecimal heightCm;

    @Pattern(regexp = "^$|^\\d{12,14}$", message = "条码应为 12 位 UPC、13 位 EAN 或 14 位 GTIN 数字")
    private String barcode;

    @DecimalMin(value = "0.00", message = "美元售价不能为负数")
    private BigDecimal priceUsd;

    /** 图片地址，每行一个，第一行为主图。上传的新图片会追加到末尾。 */
    private String imageUrlsText = "";

    public static ProductForm from(Product p) {
        ProductForm f = new ProductForm();
        f.id = p.getId();
        f.name = p.getName();
        f.sku = p.getSku();
        f.categoryId = p.getCategory() == null ? null : p.getCategory().getId();
        f.price = p.getPrice();
        f.originalPrice = p.getOriginalPrice();
        f.stock = p.getStock();
        f.description = p.getDescription();
        f.status = p.getStatus();
        f.imageUrlsText = String.join("\n", p.getImageUrls());
        f.parentSku = p.getParentSku();
        f.titleEn = p.getTitleEn();
        f.descriptionEn = p.getDescriptionEn();
        f.bulletPointsEn = p.getBulletPointsEn();
        f.brand = p.getBrand();
        f.color = p.getColor();
        f.size = p.getSize();
        f.material = p.getMaterial();
        f.weightGrams = p.getWeightGrams();
        f.lengthCm = p.getLengthCm();
        f.widthCm = p.getWidthCm();
        f.heightCm = p.getHeightCm();
        f.barcode = p.getBarcode();
        f.priceUsd = p.getPriceUsd();
        return f;
    }

    public List<String> imageUrlList() {
        if (imageUrlsText == null) {
            return List.of();
        }
        return Arrays.stream(imageUrlsText.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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

    public String getImageUrlsText() {
        return imageUrlsText;
    }

    public void setImageUrlsText(String imageUrlsText) {
        this.imageUrlsText = imageUrlsText;
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
