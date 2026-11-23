package com.javastorm.shop.web.admin;

import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ProductExportService;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.service.SiteUrls;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 导出商品到亚马逊 / SHEIN 等平台的批量上架表格。 */
@Controller
@RequestMapping("/admin/export")
public class AdminExportController {

    private final ProductService productService;
    private final ProductExportService exportService;
    private final CategoryRepository categories;
    private final SiteUrls siteUrls;
    private final ListingAiService listingAi;

    public AdminExportController(ProductService productService, ProductExportService exportService,
                                 CategoryRepository categories, ListingAiService listingAi, SiteUrls siteUrls) {
        this.productService = productService;
        this.exportService = exportService;
        this.categories = categories;
        this.listingAi = listingAi;
        this.siteUrls = siteUrls;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "ON_SALE") String status,
                       @RequestParam(required = false) Long category, Model model) {
        List<Product> list = productService.list(parseStatus(status), category);
        String baseUrl = baseUrl();
        model.addAttribute("readiness", exportService.check(list));
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("categories", categories.findAllByOrderBySortOrderAscIdAsc());
        model.addAttribute("status", status);
        model.addAttribute("category", category);
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("localBaseUrl", SiteUrls.isLocal(baseUrl));
        model.addAttribute("baseUrlConfigured", siteUrls.isConfigured());
        model.addAttribute("aiEnabled", listingAi.isEnabled());
        return "admin/export";
    }

    @GetMapping("/amazon.csv")
    public ResponseEntity<byte[]> amazon(@RequestParam(defaultValue = "ON_SALE") String status,
                                         @RequestParam(required = false) Long category) {
        List<Product> list = productService.list(parseStatus(status), category);
        return download("amazon-" + LocalDate.now() + ".csv", exportService.amazon(list, baseUrl()));
    }

    @GetMapping("/products.csv")
    public ResponseEntity<byte[]> generic(@RequestParam(defaultValue = "ON_SALE") String status,
                                          @RequestParam(required = false) Long category) {
        List<Product> list = productService.list(parseStatus(status), category);
        return download("商品资料-" + LocalDate.now() + ".csv", exportService.generic(list, baseUrl()));
    }

    /** 空字符串表示全部状态；无法识别的值按全部状态处理。 */
    private static ProductStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return ProductStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String baseUrl() {
        return siteUrls.baseUrl();
    }

    private static ResponseEntity<byte[]> download(String filename, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
