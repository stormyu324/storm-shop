package com.javastorm.shop.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.javastorm.shop.config.ExternalCircuitBreakers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 Claude 根据中文商品资料生成英文标题、5 条卖点和英文描述（亚马逊 / 独立站风格）。
 * 需要设置环境变量 ANTHROPIC_API_KEY；没设置时功能自动关闭，页面上按钮显示为不可用。
 */
@Service
public class ListingAiService {

    private static final Logger log = LoggerFactory.getLogger(ListingAiService.class);

    static final int MAX_TITLE = 200;
    static final int MAX_BULLET = 250;
    static final int MAX_DESCRIPTION = 2000;

    static final String SYSTEM_PROMPT = """
            You write English product listings for a Chinese cross-border e-commerce seller. The listings are used \
            on the seller's own English storefront and pasted into Amazon and SHEIN upload templates, so they must \
            read naturally to US shoppers and follow common marketplace rules.

            Write three things from the product data the user sends:

            1. title: at most 200 characters. Pattern: brand (if given) + product type + 2-3 key features or \
            attributes + material, color and size when given. Title Case, no ALL CAPS words, no emojis, no \
            promotional phrases (best, hot sale, free shipping, 100% guaranteed), no prices.
            2. bulletPoints: exactly 5 items, each at most 250 characters. Start each with a short benefit header \
            in capital letters and a colon (e.g. "SOFT & BREATHABLE: ..."), then one or two plain sentences. Cover \
            what the product is, materials, use cases, sizing or dimensions, and care or what is included, as far \
            as the data supports.
            3. description: 2 or 3 short plain-text paragraphs, at most 1500 characters, no HTML or markdown.

            Accuracy matters more than persuasion. Use only facts present in the product data: translate the \
            Chinese faithfully, and do not invent certifications, specifications, measurements, materials, \
            warranties or medical or safety claims. When the data is thin, keep the copy general rather than \
            making details up. Metric units may be followed by imperial in parentheses.""";

    /** 生成结果，字段说明会作为 JSON Schema 描述一起发给模型。 */
    public record EnglishListing(
            @JsonPropertyDescription("English product title, at most 200 characters") String title,
            @JsonPropertyDescription("Exactly 5 bullet points, each starting with a CAPITALIZED header and a colon")
            List<String> bulletPoints,
            @JsonPropertyDescription("Plain-text English description, 2-3 short paragraphs") String description) {
    }

    /** 发给模型的中文商品资料，空字段会被省略。 */
    public record ListingInput(String name, String category, String description, String brand, String color,
                               String size, String material, Integer weightGrams, String dimensionsCm,
                               String existingTitleEn) {
    }

    private final AnthropicClient client;
    private final String model;
    private final ExternalCircuitBreakers breakers;

    @Autowired
    public ListingAiService(@Value("${shop.ai.api-key:}") String apiKey,
                            @Value("${shop.ai.model:claude-opus-5-5}") String model,
                            @Value("${shop.ai.base-url:}") String baseUrl,
                            ExternalCircuitBreakers breakers) {
        this.model = model;
        this.breakers = breakers == null ? new ExternalCircuitBreakers() : breakers;
        if (StringUtils.hasText(apiKey)) {
            AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey.trim())
                    .timeout(Duration.ofMinutes(3));
            if (StringUtils.hasText(baseUrl)) {
                builder.baseUrl(baseUrl.trim());
            }
            this.client = builder.build();
        } else {
            this.client = null;
        }
    }

    public ListingAiService(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, new ExternalCircuitBreakers());
    }

    public boolean isEnabled() {
        return client != null;
    }

    public EnglishListing generate(ListingInput input) {
        if (client == null) {
            throw new BusinessException("还没有配置 AI：请设置环境变量 ANTHROPIC_API_KEY 后重启");
        }
        StructuredMessageCreateParams<EnglishListing> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT)
                .addUserMessage(userPrompt(input))
                // 模型安全策略拒绝时，由服务端按拒绝类别自动换用推荐的备用模型重试
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .outputConfig(EnglishListing.class)
                .build();
        StructuredMessage<EnglishListing> response = breakers.execute("claude", () -> {
            try {
                return client.messages().create(params);
            } catch (UnauthorizedException e) {
                throw new BusinessException("AI 调用失败：ANTHROPIC_API_KEY 无效，请检查后重启");
            } catch (RateLimitException e) {
                throw new BusinessException("AI 调用太频繁，请稍等一分钟再试");
            } catch (AnthropicServiceException e) {
                log.warn("Claude API 调用失败 status={}", e.statusCode(), e);
                throw new BusinessException("AI 服务暂时不可用（" + e.statusCode() + "），请稍后再试");
            } catch (AnthropicException e) {
                log.warn("Claude API 调用失败", e);
                throw new BusinessException("连接 AI 服务失败，请检查网络后再试");
            }
        }, "error.ai.unavailable", "AI 服务暂时不可用，请稍后再试");
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new BusinessException("AI 拒绝为这个商品生成内容，请手动填写");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new BusinessException("AI 生成的内容过长被截断，请再试一次");
        }
        EnglishListing raw = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new BusinessException("AI 没有返回内容，请再试一次"));
        return clean(raw);
    }

    static String userPrompt(ListingInput in) {
        StringBuilder sb = new StringBuilder("Product data (Chinese fields come from the seller):\n");
        line(sb, "Product name", in.name());
        line(sb, "Category", in.category());
        line(sb, "Brand", in.brand());
        line(sb, "Color", in.color());
        line(sb, "Size", in.size());
        line(sb, "Material", in.material());
        line(sb, "Weight", in.weightGrams() == null ? null : in.weightGrams() + " g");
        line(sb, "Package dimensions (L x W x H)", in.dimensionsCm() == null ? null : in.dimensionsCm() + " cm");
        line(sb, "Current English title (improve it, keep its facts)", in.existingTitleEn());
        line(sb, "Description", in.description());
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (StringUtils.hasText(value)) {
            sb.append("- ").append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    /** 防御性整理：去掉空白和多余条目，按字段长度上限截断，保证能直接存进数据库。 */
    static EnglishListing clean(EnglishListing raw) {
        String title = truncate(raw.title(), MAX_TITLE);
        List<String> bullets = new ArrayList<>();
        if (raw.bulletPoints() != null) {
            for (String b : raw.bulletPoints()) {
                String t = b == null ? "" : b.replaceAll("\\s+", " ").trim();
                if (!t.isEmpty() && bullets.size() < 5) {
                    bullets.add(truncate(t, MAX_BULLET));
                }
            }
        }
        String description = truncate(raw.description(), MAX_DESCRIPTION);
        if (!StringUtils.hasText(title)) {
            throw new BusinessException("AI 没有生成标题，请再试一次");
        }
        return new EnglishListing(title, bullets, description);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max).trim();
    }
}
