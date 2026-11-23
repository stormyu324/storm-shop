package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.AdminDtos.EnglishDraft;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.service.EnglishFillService;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ListingAiService.ListingInput;
import com.javastorm.shop.web.admin.AdminAiController.DraftRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 后台：AI 生成英文资料（需要配置 ANTHROPIC_API_KEY）。 */
@RestController
@RequestMapping("/api/admin/ai")
@Tag(name = "后台 - AI")
public class AdminAiApi {

    private final ListingAiService ai;
    private final EnglishFillService fill;
    private final CategoryRepository categories;

    public AdminAiApi(ListingAiService ai, EnglishFillService fill, CategoryRepository categories) {
        this.ai = ai;
        this.fill = fill;
        this.categories = categories;
    }

    @GetMapping("/status")
    @Operation(summary = "AI 是否可用")
    public Map<String, Boolean> status() {
        return Map.of("enabled", ai.isEnabled());
    }

    @PostMapping("/english-draft")
    @Operation(summary = "为编辑中的商品生成英文标题、卖点和描述", description = "不会保存，结果填回表单由用户确认；通常需要 10～30 秒")
    public EnglishDraft draft(@RequestBody DraftRequest req) {
        String category = req.categoryId() == null ? null
                : categories.findById(req.categoryId()).map(c -> c.getName()).orElse(null);
        var l = ai.generate(new ListingInput(req.name(), category, req.description(), req.brand(), req.color(),
                req.size(), req.material(), req.weightGrams(),
                EnglishFillService.dimensions(req.lengthCm(), req.widthCm(), req.heightCm()), req.titleEn()));
        return new EnglishDraft(l.title(), l.bulletPoints(), l.description());
    }

    @PostMapping("/english-fill")
    @Operation(summary = "批量补全缺英文标题的商品", description = "只填空着的字段，每次最多 20 个；remaining 大于 0 时可以再调用一次")
    public EnglishFillService.Result fillMissing(@RequestParam(required = false) ProductStatus status,
                                                 @RequestParam(required = false) Long category) {
        return fill.fillMissing(status, category);
    }
}
