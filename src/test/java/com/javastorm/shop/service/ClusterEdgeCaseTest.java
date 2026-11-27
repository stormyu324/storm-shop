package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterEdgeCaseTest {

    @Test
    void orderLockOutlivesSlowestPaymentPageCreation() {
        // Stripe 单次请求最长：连接 30 秒 + 读取 80 秒（SDK 默认不自动重试）
        assertThat(OrderSubmissionGuard.LOCK_TTL).isGreaterThan(Duration.ofSeconds(110));
    }

    @Test
    void storedImageTypeComesFromExtensionNotUploader() {
        assertThat(ImageStorageService.contentTypeFor("jpg")).isEqualTo("image/jpeg");
        assertThat(ImageStorageService.contentTypeFor("jpeg")).isEqualTo("image/jpeg");
        assertThat(ImageStorageService.contentTypeFor("png")).isEqualTo("image/png");
        assertThat(ImageStorageService.contentTypeFor("gif")).isEqualTo("image/gif");
        assertThat(ImageStorageService.contentTypeFor("webp")).isEqualTo("image/webp");
        assertThatThrownBy(() -> ImageStorageService.contentTypeFor("svg")).isInstanceOf(BusinessException.class);
    }

    @Test
    void numericLookingExportCellsAreKeptAsTextInExcel() {
        assertThat(OrderFulfillmentService.excelText("202611271521231855")).isEqualTo("\t202611271521231855");
        assertThat(OrderFulfillmentService.excelText("+86 138-0000-0000")).isEqualTo("\t+86 138-0000-0000");
        assertThat(OrderFulfillmentService.excelText("0755 1234567")).isEqualTo("\t0755 1234567");
        // 非数字内容仍按公式注入规则处理
        assertThat(OrderFulfillmentService.excelText("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(OrderFulfillmentService.excelText("1Z999AA10123456784")).isEqualTo("1Z999AA10123456784");
        assertThat(OrderFulfillmentService.excelText(null)).isEmpty();
    }

    @Test
    void csvImportClearsCategoryCache() throws Exception {
        CacheEvict evict = ProductImportService.class
                .getMethod("importCsv", byte[].class, com.javastorm.shop.domain.ProductStatus.class)
                .getAnnotation(CacheEvict.class);
        assertThat(evict).isNotNull();
        assertThat(evict.value()).containsExactly("categories");
        assertThat(evict.allEntries()).isTrue();
    }
}
