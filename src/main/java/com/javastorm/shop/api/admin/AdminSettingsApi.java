package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.AdminDtos.AdminCategory;
import com.javastorm.shop.api.AdminDtos.CategoryRequest;
import com.javastorm.shop.api.AdminDtos.DefaultRule;
import com.javastorm.shop.api.AdminDtos.ShippingSettings;
import com.javastorm.shop.api.AdminDtos.Zone;
import com.javastorm.shop.api.AdminDtos.ZoneRequest;
import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.domain.ShippingZone;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.CategoryService;
import com.javastorm.shop.service.DashboardService;
import com.javastorm.shop.service.ShippingSettingsService;
import com.javastorm.shop.service.ShippingSettingsService.Fees;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/** 后台：数据概览、分类、运费。 */
@RestController
@RequestMapping("/api/admin")
public class AdminSettingsApi {

    private final DashboardService dashboard;
    private final CategoryService categoryService;
    private final ProductRepository products;
    private final ShippingSettingsService shipping;

    public AdminSettingsApi(DashboardService dashboard, CategoryService categoryService, ProductRepository products,
                            ShippingSettingsService shipping) {
        this.dashboard = dashboard;
        this.categoryService = categoryService;
        this.products = products;
        this.shipping = shipping;
    }

    @GetMapping("/dashboard")
    @Tag(name = "后台 - 概览")
    @Operation(summary = "数据概览", description = "在售 / 草稿 / 下架商品数、库存紧张商品数、待发货和待付款订单数")
    public DashboardService.Stats dashboard() {
        return dashboard.stats();
    }

    // ---------- 分类 ----------

    @GetMapping("/categories")
    @Tag(name = "后台 - 分类")
    @Operation(summary = "分类列表（含商品数）")
    public List<AdminCategory> categories() {
        return categoryService.listWithCounts().entrySet().stream().map(e -> category(e.getKey(), e.getValue())).toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Tag(name = "后台 - 分类")
    @Operation(summary = "新建分类")
    public AdminCategory createCategory(@Valid @RequestBody CategoryRequest req) {
        return category(categoryService.create(req.name(), req.nameEn(), req.sortOrder()), 0);
    }

    @PutMapping("/categories/{id}")
    @Tag(name = "后台 - 分类")
    @Operation(summary = "修改分类")
    public AdminCategory updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        Category c = categoryService.update(id, req.name(), req.nameEn(), req.sortOrder());
        return category(c, products.countByCategory(c));
    }

    @DeleteMapping("/categories/{id}")
    @Tag(name = "后台 - 分类")
    @Operation(summary = "删除分类", description = "分类下还有商品时不能删除（422）")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static AdminCategory category(Category c, long count) {
        return new AdminCategory(c.getId(), c.getName(), c.getNameEn(), c.getSortOrder(), count);
    }

    // ---------- 运费 ----------

    @GetMapping("/shipping")
    @Tag(name = "后台 - 运费")
    @Operation(summary = "运费设置", description = "人民币（中文前台）和美元（英文前台）各一套：默认规则 + 国家分组")
    public List<ShippingSettings> shippingSettings() {
        return Arrays.stream(Currency.values()).map(this::settings).toList();
    }

    @PutMapping("/shipping/{currency}/default")
    @Tag(name = "后台 - 运费")
    @Operation(summary = "保存默认运费（其他国家）")
    public ShippingSettings saveDefault(@PathVariable Currency currency, @RequestBody DefaultRule req) {
        shipping.saveDefault(currency, req.enabled(), req.zonesOnly(),
                new Fees(req.baseFee(), req.includedGrams(), req.extraPerKg(), req.freeThreshold()));
        return settings(currency);
    }

    @PostMapping("/shipping/{currency}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    @Tag(name = "后台 - 运费")
    @Operation(summary = "添加国家分组", description = "一个国家只能属于一个分组")
    public Zone createZone(@PathVariable Currency currency, @Valid @RequestBody ZoneRequest req) {
        return zone(shipping.saveZone(currency, null, req.name(), String.join(",", req.countries()), fees(req)));
    }

    @PutMapping("/shipping/{currency}/zones/{id}")
    @Tag(name = "后台 - 运费")
    @Operation(summary = "修改国家分组")
    public Zone updateZone(@PathVariable Currency currency, @PathVariable Long id, @Valid @RequestBody ZoneRequest req) {
        return zone(shipping.saveZone(currency, id, req.name(), String.join(",", req.countries()), fees(req)));
    }

    @DeleteMapping("/shipping/zones/{id}")
    @Tag(name = "后台 - 运费")
    @Operation(summary = "删除国家分组")
    public ResponseEntity<Void> deleteZone(@PathVariable Long id) {
        shipping.deleteZone(id);
        return ResponseEntity.noContent().build();
    }

    private ShippingSettings settings(Currency c) {
        ShippingRule r = shipping.rule(c);
        return new ShippingSettings(c, new DefaultRule(r.isEnabled(), r.isZonesOnly(), r.getBaseFee(),
                r.getIncludedGrams(), r.getExtraPerKg(), r.getFreeThreshold()),
                shipping.zones(c).stream().map(AdminSettingsApi::zone).toList());
    }

    private static Zone zone(ShippingZone z) {
        return new Zone(z.getId(), z.getName(), z.getCountryList(), z.getBaseFee(), z.getIncludedGrams(),
                z.getExtraPerKg(), z.getFreeThreshold());
    }

    private static Fees fees(ZoneRequest r) {
        return new Fees(r.baseFee(), r.includedGrams() == null ? 1000 : r.includedGrams(), r.extraPerKg(), r.freeThreshold());
    }
}
