package com.javastorm.shop.api.admin;

import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ProductExportService;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.service.SiteUrls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 平台导出页：跨境资料完整度。表格下载仍用 /admin/export/amazon.csv 和 /admin/export/products.csv（同一个登录会话）。 */
@RestController
@RequestMapping("/api/admin/export")
@Tag(name = "后台 - 平台导出")
public class AdminExportApi {

    private final ProductService productService;
    private final ProductExportService exportService;
    private final ListingAiService ai;
    private final SiteUrls siteUrls;

    public AdminExportApi(ProductService productService, ProductExportService exportService, ListingAiService ai,
                          SiteUrls siteUrls) {
        this.productService = productService;
        this.exportService = exportService;
        this.ai = ai;
        this.siteUrls = siteUrls;
    }

    public record Incomplete(Long id, String name, String sku, String missing) {
    }

    public record Readiness(int total, int missingTitleEn, int missingBrand, int missingBarcode, int missingImage,
                            int missingWeight, int autoUsdPrice, List<Incomplete> incomplete,
                            String imageBaseUrl, boolean imageBaseUrlIsLocal, boolean aiEnabled) {
    }

    @GetMapping("/readiness")
    @Operation(summary = "资料完整度", description = "status 不传表示全部状态；incomplete 最多 30 个")
    public Readiness readiness(@RequestParam(required = false) ProductStatus status,
                               @RequestParam(required = false) Long category) {
        List<Product> list = productService.list(status, category);
        ProductExportService.Readiness r = exportService.check(list);
        String base = siteUrls.baseUrl();
        return new Readiness(r.total(), r.missingTitleEn(), r.missingBrand(), r.missingBarcode(), r.missingImage(),
                r.missingWeight(), r.autoUsdPrice(),
                r.incomplete().stream().map(p -> new Incomplete(p.getId(), p.getName(), p.getSku(),
                        ProductExportService.missingFields(p))).toList(),
                base, SiteUrls.isLocal(base), ai.isEnabled());
    }
}
