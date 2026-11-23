package com.javastorm.shop.service;

import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.service.ListingAiService.EnglishListing;
import com.javastorm.shop.service.ListingAiService.ListingInput;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 用 AI 批量补全缺英文标题的商品（只填空着的字段），后台页面和后台接口共用。 */
@Service
public class EnglishFillService {

    /** 每次最多处理的商品数，避免一次请求等待太久。 */
    public static final int BATCH_LIMIT = 20;
    private static final int PARALLEL = 4;

    private final ListingAiService ai;
    private final ProductService productService;

    public EnglishFillService(ListingAiService ai, ProductService productService) {
        this.ai = ai;
        this.productService = productService;
    }

    /**
     * @param filled    已生成并保存的商品数
     * @param remaining 本次没处理到、还缺英文标题的商品数
     * @param failures  失败的商品及原因
     */
    public record Result(int filled, int remaining, List<String> failures) {
    }

    public Result fillMissing(ProductStatus status, Long categoryId) {
        if (!ai.isEnabled()) {
            throw new BusinessException("还没有配置 AI：请设置环境变量 ANTHROPIC_API_KEY 后重启");
        }
        List<Product> missing = productService.list(status, categoryId).stream()
                .filter(p -> !StringUtils.hasText(p.getTitleEn()))
                .toList();
        List<Product> batch = missing.subList(0, Math.min(BATCH_LIMIT, missing.size()));
        int done = 0;
        List<String> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            List<Future<EnglishListing>> futures = new ArrayList<>();
            for (Product p : batch) {
                ListingInput input = inputOf(p);
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
                    failures.add("「" + p.getName() + "」"
                            + (cause instanceof BusinessException ? cause.getMessage() : "生成失败"));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(done, missing.size() - batch.size(), failures);
    }

    public static ListingInput inputOf(Product p) {
        return new ListingInput(p.getName(), p.getCategory() == null ? null : p.getCategory().getName(),
                p.getDescription(), p.getBrand(), p.getColor(), p.getSize(), p.getMaterial(), p.getWeightGrams(),
                dimensions(str(p.getLengthCm()), str(p.getWidthCm()), str(p.getHeightCm())), null);
    }

    public static String dimensions(String l, String w, String h) {
        if (!StringUtils.hasText(l) && !StringUtils.hasText(w) && !StringUtils.hasText(h)) {
            return null;
        }
        return (StringUtils.hasText(l) ? l : "?") + " x " + (StringUtils.hasText(w) ? w : "?") + " x "
                + (StringUtils.hasText(h) ? h : "?");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
