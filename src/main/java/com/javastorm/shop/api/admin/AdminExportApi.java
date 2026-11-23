package com.javastorm.shop.api.admin;

import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ProductExportService;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.service.SiteUrls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 平台导出：跨境资料完整度检查，下载亚马逊 / 通用资料表格。 */
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

    @GetMapping("/amazon.csv")
    @Operation(summary = "下载亚马逊表格", description = "列名按亚马逊库存文件字段名，价格为美元；status 不传表示全部状态")
    public ResponseEntity<byte[]> amazon(@RequestParam(required = false) ProductStatus status,
                                         @RequestParam(required = false) Long category) {
        return Csv.download("amazon-" + LocalDate.now() + ".csv",
                exportService.amazon(productService.list(status, category), siteUrls.baseUrl()));
    }

    @GetMapping("/products.csv")
    @Operation(summary = "下载通用资料表", description = "列和批量导入一致，可改完再导回；也可复制到 SHEIN 等平台的模板")
    public ResponseEntity<byte[]> generic(@RequestParam(required = false) ProductStatus status,
                                          @RequestParam(required = false) Long category) {
        return Csv.download("商品资料-" + LocalDate.now() + ".csv",
                exportService.generic(productService.list(status, category), siteUrls.baseUrl()));
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
