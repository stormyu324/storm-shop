package com.javastorm.shop.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 业务监控指标（通过 /actuator/prometheus 给 Prometheus 抓取，告警规则见 deploy/monitoring/alerts.yml）。
 * <ul>
 *   <li>计数：下单、付款确认、创建付款页失败；</li>
 *   <li>积压（抓取时实时查询数据库）：待发邮件、发送失败的邮件、未处理的对账异常、退款失败、待付款订单；</li>
 *   <li>外部服务熔断器状态、最近一次备份距今多久。</li>
 * </ul>
 */
@Component
public class ShopMetrics {

    private static final Logger log = LoggerFactory.getLogger(ShopMetrics.class);

    private final MeterRegistry registry;
    // Micrometer 对指标的数据来源只保持弱引用，必须由这里持有，否则被回收后指标变成 NaN
    private final JdbcTemplate jdbc;
    private final ExternalCircuitBreakers breakers;
    private final Path backupStatus;

    @Autowired
    public ShopMetrics(MeterRegistry registry, JdbcTemplate jdbc, ExternalCircuitBreakers breakers,
                       @Value("${shop.backup.status-file:}") String backupStatusFile) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.breakers = breakers;
        count(jdbc, "shop.mail.backlog", "待发送的通知邮件（含重试中）",
                "select count(*) from order_mail where sent_at is null and attempts < 8");
        count(jdbc, "shop.mail.failed", "多次重试仍发送失败、已放弃的邮件",
                "select count(*) from order_mail where sent_at is null and attempts >= 8");
        count(jdbc, "shop.reconciliation.unresolved", "未处理的支付对账异常",
                "select count(*) from payment_reconciliation_issue where resolved = false");
        count(jdbc, "shop.refunds.failed", "退款失败或需要人工线下退款的售后单",
                "select count(*) from after_sale where state in ('FAILED', 'MANUAL_REQUIRED')");
        count(jdbc, "shop.orders.pending_payment", "待付款订单",
                "select count(*) from customer_order where status = 'PENDING_PAYMENT'");
        for (String name : List.of("stripe", "paypal", "claude")) {
            Gauge.builder("shop.circuit_breaker.open", breakers, b -> b.isOpen(name) ? 1 : 0)
                    .description("外部服务熔断器是否打开（1=打开，调用被暂停）")
                    .tag("service", name)
                    .register(registry);
        }
        this.backupStatus = backupStatusFile == null || backupStatusFile.isBlank()
                ? null : Path.of(backupStatusFile.trim());
        if (backupStatus != null) {
            Gauge.builder("shop.backup.last_success_age_seconds", backupStatus, ShopMetrics::ageSeconds)
                    .description("最近一次成功备份距今的秒数（文件不存在时为 NaN）")
                    .register(registry);
        }
    }

    /** 测试或不需要监控时使用：指标只记在内存里。 */
    public static ShopMetrics noop() {
        return new ShopMetrics(new SimpleMeterRegistry());
    }

    private ShopMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.jdbc = null;
        this.breakers = null;
        this.backupStatus = null;
    }

    private void count(JdbcTemplate jdbc, String name, String description, String sql) {
        Gauge.builder(name, jdbc, j -> {
                    try {
                        Long n = j.queryForObject(sql, Long.class);
                        return n == null ? 0 : n;
                    } catch (RuntimeException e) {
                        log.debug("指标 {} 查询失败", name, e);
                        return Double.NaN;
                    }
                })
                .description(description)
                .register(registry);
    }

    private static double ageSeconds(Path file) {
        try {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return Math.max(0, Instant.now().getEpochSecond() - modified.getEpochSecond());
        } catch (IOException e) {
            return Double.NaN;
        }
    }

    /** 下单成功（事务提交后才计数，并发冲突重试失败的不计）。 */
    public void orderPlaced(String currency) {
        afterCommit(() -> Counter.builder("shop.orders.placed").description("成功创建的订单")
                .tag("currency", currency).register(registry).increment());
    }

    /** 在线付款确认到账。 */
    public void paymentConfirmed(String provider) {
        afterCommit(() -> Counter.builder("shop.payments.confirmed").description("在线付款确认到账")
                .tag("provider", provider).register(registry).increment());
    }

    /** 创建付款页失败（顾客下单后没能跳转去付款）。 */
    public void checkoutFailed(String provider) {
        Counter.builder("shop.payments.checkout_failed").description("创建付款页失败")
                .tag("provider", provider == null ? "default" : provider).register(registry).increment();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
