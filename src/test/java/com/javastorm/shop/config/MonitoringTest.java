package com.javastorm.shop.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 监控：Prometheus 用令牌抓取指标，业务积压指标和备份时间指标正确。 */
@SpringBootTest(properties = "shop.metrics.token=test-token-for-prometheus")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class MonitoringTest {

    @Autowired MockMvc mvc;

    @Test
    void prometheusCanScrapeWithTokenOnly() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-token-for-prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("shop_mail_backlog")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("shop_reconciliation_unresolved")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("shop_circuit_breaker_open")));
        // 令牌只能看指标，不能访问其他管理端点
        mvc.perform(get("/actuator/info").header("Authorization", "Bearer test-token-for-prometheus"))
                .andExpect(status().isForbidden());
    }

    @Test
    void backlogGaugesQueryTheDatabaseAndBackupAgeSurvivesGc() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
        Path status = Files.createTempFile("backup-ok", ".txt");
        Files.setLastModifiedTime(status, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 正式运行时 ShopMetrics 由 Spring 持有；这里同样保持引用
        ShopMetrics metrics = new ShopMetrics(registry, jdbc, new ExternalCircuitBreakers(), status.toString());
        // Micrometer 只弱引用数据来源：垃圾回收后指标仍要有值
        System.gc();

        assertThat(registry.get("shop.mail.backlog").gauge().value()).isEqualTo(7);
        assertThat(registry.get("shop.refunds.failed").gauge().value()).isEqualTo(7);
        double age = registry.get("shop.backup.last_success_age_seconds").gauge().value();
        assertThat(age).isBetween(7_000d, 7_400d);
        assertThat(registry.get("shop.circuit_breaker.open").tag("service", "stripe").gauge().value()).isZero();
        java.lang.ref.Reference.reachabilityFence(metrics);
    }

    @Test
    void ordersAreCountedOnlyAfterCommit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShopMetrics metrics = new ShopMetrics(registry, mock(JdbcTemplate.class), new ExternalCircuitBreakers(), "");
        metrics.orderPlaced("USD");
        metrics.paymentConfirmed("paypal");
        metrics.checkoutFailed(null);
        assertThat(registry.get("shop.orders.placed").tag("currency", "USD").counter().count()).isEqualTo(1);
        assertThat(registry.get("shop.payments.confirmed").tag("provider", "paypal").counter().count()).isEqualTo(1);
        assertThat(registry.get("shop.payments.checkout_failed").tag("provider", "default").counter().count()).isEqualTo(1);
        assertThat(registry.find("shop.backup.last_success_age_seconds").gauge()).isNull();
    }
}
