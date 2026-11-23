package com.javastorm.shop.api;

import com.javastorm.shop.api.AdminDtos.AdminOrder;
import com.javastorm.shop.api.AdminDtos.AdminProduct;
import com.javastorm.shop.api.AdminDtos.CategoryRef;
import com.javastorm.shop.api.AdminDtos.OrderLine;
import com.javastorm.shop.api.StoreDtos.CategoryDto;
import com.javastorm.shop.api.StoreDtos.ProductDetail;
import com.javastorm.shop.api.StoreDtos.ProductSummary;
import com.javastorm.shop.api.StoreDtos.VariantOption;
import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.service.Pricing;
import com.javastorm.shop.service.VariantService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 实体 → 接口数据格式。 */
@Component
public class ApiMapper {

    private final Pricing pricing;
    private final VariantService variants;

    public ApiMapper(Pricing pricing, VariantService variants) {
        this.pricing = pricing;
        this.variants = variants;
    }

    public CategoryDto category(Category c, boolean english) {
        return c == null ? null : new CategoryDto(c.getId(), c.title(english));
    }

    public ProductSummary summary(Product p, Currency currency, boolean english, Map<String, VariantService.Family> families) {
        VariantService.Family f = p.getParentSku() == null ? null : families.get(p.getParentSku());
        boolean from = f != null && f.priceVaries();
        String title = f != null && f.title(english) != null ? f.title(english) : p.title(english);
        return new ProductSummary(p.getId(), p.getSku(), title, p.getMainImage(),
                from ? f.minPrice() : pricing.price(p, currency),
                from ? null : pricing.originalPrice(p, currency),
                currency, from, f == null ? 1 : f.variantCount(),
                f != null ? f.inStock() : p.getStock() > 0,
                p.getCategory() == null ? null : p.getCategory().getId());
    }

    public ProductDetail detail(Product p, Currency currency, boolean english) {
        VariantService.Variants v = variants.variants(p);
        return new ProductDetail(p.getId(), p.getSku(), p.title(english), p.description(english),
                english ? p.getBulletPointList() : List.of(), List.copyOf(p.getImageUrls()),
                pricing.price(p, currency), pricing.originalPrice(p, currency), currency, p.getStock(),
                p.getBrand(), p.getColor(), p.getSize(), p.getMaterial(), category(p.getCategory(), english),
                v.colors().stream().map(ApiMapper::option).toList(),
                v.sizes().stream().map(ApiMapper::option).toList());
    }

    private static VariantOption option(VariantService.Option o) {
        return new VariantOption(o.label(), o.targetId(), o.selected(), o.available());
    }

    public AdminProduct adminProduct(Product p) {
        return new AdminProduct(p.getId(), p.getSku(), p.getName(),
                p.getCategory() == null ? null : new CategoryRef(p.getCategory().getId(), p.getCategory().getName()),
                p.getPrice(), p.getOriginalPrice(), p.getStock(), p.getDescription(),
                p.getStatus(), p.getStatus().getLabel(), List.copyOf(p.getImageUrls()),
                p.getParentSku(), p.getTitleEn(), p.getDescriptionEn(), p.getBulletPointList(),
                p.getBrand(), p.getColor(), p.getSize(), p.getMaterial(),
                p.getWeightGrams(), p.getLengthCm(), p.getWidthCm(), p.getHeightCm(),
                p.getBarcode(), p.getPriceUsd(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }

    /** 已付款的订单不能直接取消，要走售后退款（OrderService.changeStatus 也会拒绝），按钮就不显示“取消”。 */
    static java.util.List<com.javastorm.shop.domain.OrderStatus> nextStatuses(CustomerOrder o) {
        var next = o.getStatus().getNextStatuses();
        return o.getPaidAt() == null ? next
                : next.stream().filter(s -> s != com.javastorm.shop.domain.OrderStatus.CANCELLED).toList();
    }

    public AdminOrder adminOrder(CustomerOrder o) {
        return new AdminOrder(o.getId(), o.getOrderNo(), o.getStatus(), o.getStatus().getLabel(),
                nextStatuses(o), o.getCurrency(), o.getItemsTotal(), o.getShippingFee(), o.getTotal(),
                o.getCustomerName(), o.getPhone(), o.getCountry(), o.getAddress(), o.getNote(),
                o.getItems().stream().map(i -> new OrderLine(i.getProductId(), i.getProductName(), i.getSku(),
                        i.getUnitPrice(), i.getQuantity(), i.getSubtotal())).toList(),
                o.getCreatedAt(), o.getPaidAt(), o.getEmail(), o.getCarrier(), o.getTrackingNumber(), o.getTrackingUrl(), o.getShippedAt(), o.getDiscountAmount(), o.getCouponCode());
    }
}
