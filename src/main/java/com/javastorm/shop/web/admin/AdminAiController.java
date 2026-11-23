package com.javastorm.shop.web.admin;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ListingAiService.EnglishListing;
import com.javastorm.shop.service.ListingAiService.ListingInput;
import com.javastorm.shop.service.EnglishFillService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AI 生成英文资料：编辑页单个生成（返回 JSON 填入表单），导出页批量补全。 */
@Controller
public class AdminAiController {

    private final ListingAiService ai;
    private final EnglishFillService fill;
    private final CategoryRepository categories;

    public AdminAiController(ListingAiService ai, EnglishFillService fill, CategoryRepository categories) {
        this.ai = ai;
        this.fill = fill;
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
        String dims = EnglishFillService.dimensions(req.lengthCm(), req.widthCm(), req.heightCm());
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
        EnglishFillService.Result r;
        try {
            r = fill.fillMissing(parseStatus(status), category);
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:" + back;
        }
        if (r.filled() == 0 && r.failures().isEmpty() && r.remaining() == 0) {
            flash.addFlashAttribute("message", "筛选范围内的商品都已有英文标题");
            return "redirect:" + back;
        }
        StringBuilder msg = new StringBuilder("已为 " + r.filled() + " 个商品生成英文资料");
        if (r.remaining() > 0) {
            msg.append("，还有 ").append(r.remaining()).append(" 个，可以再点一次继续");
        }
        msg.append("。建议抽查几个，确认无误后再导出。");
        flash.addFlashAttribute("message", msg.toString());
        List<String> failures = r.failures();
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
}
