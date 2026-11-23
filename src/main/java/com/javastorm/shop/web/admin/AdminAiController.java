package com.javastorm.shop.web.admin;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ListingAiService.EnglishListing;
import com.javastorm.shop.service.ListingAiService.ListingInput;
import com.javastorm.shop.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** AI 生成英文资料：编辑页单个生成（返回 JSON 填入表单），导出页批量补全。 */
@Controller
public class AdminAiController {

    /** 每次批量补全最多处理的商品数，避免一次请求等待太久。 */
    static final int BATCH_LIMIT = 20;
    private static final int PARALLEL = 4;

    private final ListingAiService ai;
    private final ProductService productService;
    private final CategoryRepository categories;

    public AdminAiController(ListingAiService ai, ProductService productService, CategoryRepository categories) {
        this.ai = ai;
        this.productService = productService;
        this.categories = categories;
    }

    /** 编辑页上的表单内容（还没保存）。 */
    public record DraftRequest(String name, Long categoryId, String description, String brand, String color,
                               String size, String material, Integer weightGrams, String lengthCm, String widthCm,
                               String heightCm, String titleEn) {
    }

    @PostMapping("/admin/products/ai/english")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateForDraft(@RequestBody DraftRequest req) {
        if (!StringUtils.hasText(req.name())) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先填写商品名称"));
        }
        String category = req.categoryId() == null ? null
                : categories.findById(req.categoryId()).map(Category::getName).orElse(null);
        String dims = dimensions(req.lengthCm(), req.widthCm(), req.heightCm());
        try {
            EnglishListing l = ai.generate(new ListingInput(req.name(), category, req.description(), req.brand(),
                    req.color(), req.size(), req.material(), req.weightGrams(), dims, req.titleEn()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("titleEn", l.title());
            body.put("bulletPointsEn", String.join("\n", l.bulletPoints()));
            body.put("descriptionEn", l.description() == null ? "" : l.description());
            return ResponseEntity.ok(body);
        } catch (BusinessException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        }
    }

    /** 批量补全：为缺英文标题的商品生成英文资料，只填空着的字段。 */
    @PostMapping("/admin/export/ai-fill")
    public String fillMissing(@RequestParam(defaultValue = "ON_SALE") String status,
                              @RequestParam(required = false) Long category, RedirectAttributes flash) {
        String back = UriComponentsBuilder.fromPath("/admin/export")
                .queryParam("status", status).queryParamIfPresent("category", java.util.Optional.ofNullable(category))
                .build().toUriString();
        if (!ai.isEnabled()) {
            flash.addFlashAttribute("error", "还没有配置 AI：请设置环境变量 ANTHROPIC_API_KEY 后重启");
            return "redirect:" + back;
        }
        ProductStatus st = parseStatus(status);
        List<Product> missing = productService.list(st, category).stream()
                .filter(p -> !StringUtils.hasText(p.getTitleEn()))
                .toList();
        if (missing.isEmpty()) {
            flash.addFlashAttribute("message", "筛选范围内的商品都已有英文标题");
            return "redirect:" + back;
        }
        List<Product> batch = missing.subList(0, Math.min(BATCH_LIMIT, missing.size()));

        int done = 0;
        List<String> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            List<Future<EnglishListing>> futures = new ArrayList<>();
            for (Product p : batch) {
                ListingInput input = new ListingInput(p.getName(),
                        p.getCategory() == null ? null : p.getCategory().getName(), p.getDescription(),
                        p.getBrand(), p.getColor(), p.getSize(), p.getMaterial(), p.getWeightGrams(),
                        dimensions(str(p.getLengthCm()), str(p.getWidthCm()), str(p.getHeightCm())), null);
                futures.add(pool.submit(() -> ai.generate(input)));
            }
            for (int i = 0; i < batch.size(); i++) {
                Product p = batch.get(i);
                try {
                    if (productService.fillEnglishIfEmpty(p.getId(), futures.get(i).get())) {
                        done++;
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() instanceof BusinessException be ? be : e;
                    failures.add("「" + p.getName() + "」" + (cause instanceof BusinessException ? cause.getMessage() : "生成失败"));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        StringBuilder msg = new StringBuilder("已为 " + done + " 个商品生成英文资料");
        int remaining = missing.size() - batch.size();
        if (remaining > 0) {
            msg.append("，还有 ").append(remaining).append(" 个，可以再点一次继续");
        }
        msg.append("。建议抽查几个，确认无误后再导出。");
        flash.addFlashAttribute("message", msg.toString());
        if (!failures.isEmpty()) {
            flash.addFlashAttribute("error", failures.size() + " 个失败：" + String.join("；", failures.subList(0, Math.min(3, failures.size())))
                    + (failures.size() > 3 ? " 等" : ""));
        }
        return "redirect:" + back;
    }

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

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String dimensions(String l, String w, String h) {
        if (!StringUtils.hasText(l) && !StringUtils.hasText(w) && !StringUtils.hasText(h)) {
            return null;
        }
        return (StringUtils.hasText(l) ? l : "?") + " x " + (StringUtils.hasText(w) ? w : "?") + " x "
                + (StringUtils.hasText(h) ? h : "?");
    }
}
