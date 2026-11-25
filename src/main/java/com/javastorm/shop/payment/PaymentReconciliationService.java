package com.javastorm.shop.payment;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.PaymentReconciliationIssue;
import com.javastorm.shop.payment.StripePaymentService.SessionState;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.PaymentReconciliationRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.OrderService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stripe 每日自动对账服务：核对指定日期所有在线支付订单的本地状态与 Stripe 实际结算状态。
 * <ul>
 *   <li>本地待付款、Stripe 已付款（Webhook 丢包）→ 自动补单为已付款，并记录自动修复审计；</li>
 *   <li>本地已取消、Stripe 已付款 → 记录高危资金异常 {@code CANCELLED_BUT_PAID}，提示财务退款；</li>
 *   <li>本地已付款/已发货/已完成、Stripe 未付款 → 记录异常 {@code PAID_NOT_CAPTURED_IN_STRIPE}；</li>
 *   <li>查询 Stripe 失败 → 记录 {@code STRIPE_QUERY_ERROR} 避免静默漏检。</li>
 * </ul>
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);
    private static final Set<OrderStatus> FULFILLED_OR_PAID = Set.of(
            OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    public static final String AUTO_RECOVERED_PAID = "AUTO_RECOVERED_PAID";
    public static final String CANCELLED_BUT_PAID = "CANCELLED_BUT_PAID";
    public static final String PAID_NOT_CAPTURED = "PAID_NOT_CAPTURED_IN_STRIPE";
    public static final String STRIPE_QUERY_ERROR = "STRIPE_QUERY_ERROR";

    private final OrderRepository orders;
    private final OrderService orderService;
    private final StripePaymentService stripe;
    private final PaymentReconciliationRepository issues;

    public PaymentReconciliationService(OrderRepository orders, OrderService orderService,
                                        StripePaymentService stripe, PaymentReconciliationRepository issues) {
        this.orders = orders;
        this.orderService = orderService;
        this.stripe = stripe;
        this.issues = issues;
    }

    public record Summary(LocalDate date, int checkedOrders, int matched, int autoRecovered, int discrepancies) {
    }

    /** 每日凌晨 02:15 自动核对前一日所有 Stripe 订单，集群通过 ShedLock 保证仅执行一次。 */
    @Scheduled(cron = "${shop.reconciliation.cron:0 15 2 * * *}")
    @SchedulerLock(name = "stripe_daily_reconciliation", lockAtMostFor = "PT30M")
    public void reconcileYesterday() {
        if (!stripe.isEnabled()) {
            return;
        }
        Summary summary = reconcileDate(LocalDate.now().minusDays(1));
        log.info("Stripe 每日对账完成 date={} checked={} matched={} autoRecovered={} discrepancies={}",
                summary.date(), summary.checkedOrders(), summary.matched(),
                summary.autoRecovered(), summary.discrepancies());
    }

    public Summary reconcileDate(LocalDate date) {
        if (!stripe.isEnabled()) {
            throw new BusinessException("Stripe 在线支付未启用，无法执行对账");
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<CustomerOrder> onlineOrders = orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end)
                .stream()
                .filter(o -> o.getPaymentSessionId() != null && !o.getPaymentSessionId().isBlank())
                .toList();

        int matched = 0;
        int autoRecovered = 0;
        int discrepancies = 0;
        List<PaymentReconciliationIssue> created = new ArrayList<>();

        for (CustomerOrder order : onlineOrders) {
            SessionState state;
            try {
                state = stripe.retrieve(order.getPaymentSessionId());
                if (state == null) {
                    throw new BusinessException("Stripe 未返回会话状态");
                }
            } catch (RuntimeException e) {
                discrepancies++;
                recordIfAbsent(date, order, STRIPE_QUERY_ERROR,
                        "查询 Stripe 会话失败：" + e.getMessage(), false, created);
                continue;
            }

            if (order.getStatus() == OrderStatus.PENDING_PAYMENT && state.paid()) {
                orderService.markPaid(order.getOrderNo(), state.sessionId());
                autoRecovered++;
                recordIfAbsent(date, order, AUTO_RECOVERED_PAID,
                        "本地为待付款但 Stripe 已收款，对账任务已自动补单为已付款", true, created);
            } else if (order.getStatus() == OrderStatus.CANCELLED && state.paid()) {
                discrepancies++;
                log.error("对账发现高危异常：订单 {} 本地已取消但 Stripe 已收款（session {}）",
                        order.getOrderNo(), order.getPaymentSessionId());
                recordIfAbsent(date, order, CANCELLED_BUT_PAID,
                        "本地订单已取消但 Stripe 已收款，请立即在 Stripe 后台核查并退款", false, created);
            } else if (FULFILLED_OR_PAID.contains(order.getStatus()) && !state.paid()) {
                discrepancies++;
                log.error("对账发现异常：订单 {} 本地状态 {} 但 Stripe 未显示已收款（session {}）",
                        order.getOrderNo(), order.getStatus(), order.getPaymentSessionId());
                recordIfAbsent(date, order, PAID_NOT_CAPTURED,
                        "本地状态为 " + order.getStatus() + " 但 Stripe 会话未付款，请核查", false, created);
            } else {
                matched++;
            }
        }
        return new Summary(date, onlineOrders.size(), matched, autoRecovered, discrepancies);
    }

    @Transactional
    public PaymentReconciliationIssue resolve(Long issueId) {
        PaymentReconciliationIssue issue = issues.findById(issueId)
                .orElseThrow(() -> new BusinessException("对账记录不存在"));
        if (!issue.isResolved()) {
            issue.setResolved(true);
            issue.setResolvedAt(LocalDateTime.now());
        }
        return issue;
    }

    private void recordIfAbsent(LocalDate date, CustomerOrder order, String type, String detail,
                                boolean autoResolved, List<PaymentReconciliationIssue> created) {
        if (!autoResolved && issues.existsByOrderIdAndIssueTypeAndResolvedFalse(order.getId(), type)) {
            return;
        }
        PaymentReconciliationIssue issue = new PaymentReconciliationIssue();
        issue.setRunDate(date);
        issue.setOrderId(order.getId());
        issue.setOrderNo(order.getOrderNo());
        issue.setPaymentSessionId(order.getPaymentSessionId());
        issue.setIssueType(type);
        issue.setDetail(detail);
        issue.setResolved(autoResolved);
        if (autoResolved) {
            issue.setResolvedAt(LocalDateTime.now());
        }
        created.add(issues.save(issue));
    }
}
