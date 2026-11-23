package com.javastorm.shop.api;

import com.javastorm.shop.api.StoreDtos.ProductSummary;
import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.service.RateLimiter;
import com.javastorm.shop.service.RecommendationService;
import com.javastorm.shop.service.VariantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** AI 导购：根据顾客的需求或正在看的商品，推荐店里的商品。 */
@RestController
@RequestMapping("/api/recommendations")
@Tag(name = "商城", description = "公开接口：商品、分类、运费试算、下单")
@Validated
public class RecommendationApi {

    private final RecommendationService recommendations;
    private final RateLimiter limiter;
    private final VariantService variants;
    private final ApiMapper mapper;
    private final MessageSource messages;

    public RecommendationApi(RecommendationService recommendations, RateLimiter limiter, VariantService variants,
                             ApiMapper mapper, MessageSource messages) {
        this.recommendations = recommendations;
        this.limiter = limiter;
        this.variants = variants;
        this.mapper = mapper;
        this.messages = messages;
    }

    public record RecommendRequest(
            @Size(max = 300) @Schema(description = "顾客的需求，例如“送妈妈的生日礼物，200 元以内”", example = "送妈妈的生日礼物，200 元以内") String query,
            @Schema(description = "顾客正在看的商品：推荐和它搭配的其他商品") Long productId,
            Currency currency) {
    }

    public record RecommendedItem(ProductSummary product, String reason) {
    }

    public record Recommendation(String reply, List<RecommendedItem> items) {
    }

    @GetMapping("/status")
    @Operation(summary = "AI 导购是否开启")
    public Map<String, Boolean> status() {
        return Map.of("enabled", recommendations.isEnabled());
    }

    @PostMapping
    @Operation(summary = "AI 推荐商品", description = "query 和 productId 至少填一个；只推荐在售商品，最多 4 个。"
            + "通常需要几秒到十几秒。每个访客 10 分钟内最多 10 次，全站每天有调用上限，超出返回 429。")
    public ResponseEntity<?> recommend(@RequestBody RecommendRequest req, HttpServletRequest http) {
        String limited = limiter.tryAcquire(http.getRemoteAddr());
        if (limited != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiError.of(messages.getMessage(limited, null, LocaleContextHolder.getLocale())));
        }
        Currency currency = StoreApiController.currency(req.currency());
        boolean en = LocaleConfig.isEnglish();
        var result = recommendations.recommend(req.query(), req.productId(), currency, en);
        List<Product> picked = result.picks().stream().map(RecommendationService.Pick::product).toList();
        var families = variants.families(picked, currency);
        return ResponseEntity.ok(new Recommendation(result.reply(), result.picks().stream()
                .map(p -> new RecommendedItem(mapper.summary(p.product(), currency, en, families), p.reason()))
                .toList()));
    }
}
