package com.javastorm.shop.service;

import com.javastorm.shop.domain.AfterSale;
import com.javastorm.shop.domain.AfterSaleHistory;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.AfterSaleHistoryRepository;
import com.javastorm.shop.repository.AfterSaleRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class OrderCareService {
    private final OrderRepository orders;
    private final CouponService coupons;
    private final AfterSaleRepository cases;
    private final ProductRepository products;
    private final OrderMailService mail;
    private final StripePaymentService stripe;
    private final TransactionTemplate transactions;
    private final AfterSaleHistoryRepository history;

    public OrderCareService(
            OrderRepository orders,
            AfterSaleRepository cases,
            ProductRepository products,
            OrderMailService mail,
            StripePaymentService stripe,
            PlatformTransactionManager manager,
            AfterSaleHistoryRepository history,
            CouponService coupons) {
        this.orders = orders;
        this.coupons = coupons;
        this.history = history;
        this.cases = cases;
        this.products = products;
        this.mail = mail;
        this.stripe = stripe;
        transactions = new TransactionTemplate(manager);
    }

    public record HistoryView(String state, String note, LocalDateTime createdAt) {}

    public record CaseView(
            Long id,
            String reason,
            String state,
            String decisionNote,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<HistoryView> history) {}

    public List<CaseView> cases(Long orderId) {
        return cases.findByOrderIdOrderByIdDesc(orderId).stream()
                .map(
                        a ->
                                new CaseView(
                                        a.getId(),
                                        a.getReason(),
                                        a.getState(),
                                        a.getDecisionNote(),
                                        a.getCreatedAt(),
                                        a.getUpdatedAt(),
                                        history.findByCaseIdOrderByIdAsc(a.getId()).stream()
                                                .map(h -> new HistoryView(h.getState(), h.getNote(), h.getCreatedAt()))
                                                .toList()))
                .toList();
    }

    private CustomerOrder locked(Long id) {
        return orders.findForUpdate(id).orElseThrow(() -> new BusinessException("订单不存在"));
    }

    private void record(AfterSale a) {
        AfterSaleHistory event = new AfterSaleHistory();
        event.setCaseId(a.getId());
        event.setState(a.getState());
        event.setNote(a.getDecisionNote());
        history.save(event);
    }

    private boolean active(AfterSale a) {
        return !"REJECTED".equals(a.getState());
    }

    @Transactional
    public void ship(Long id, String carrier, String number, String url) {
        CustomerOrder order = locked(id);
        if (!Set.of(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.SHIPPED)
                .contains(order.getStatus())) {
            throw new BusinessException("当前订单状态不允许发货");
        }
        if (cases.findByOrderIdOrderByIdDesc(id).stream().anyMatch(this::active)) {
            throw new BusinessException("订单有待处理售后，请先处理售后");
        }
        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new BusinessException("物流链接须为有效 HTTPS 地址");
            }
        }
        String link = url == null || url.isBlank() ? null : url.trim();
        boolean first = order.getShippedAt() == null;
        boolean changed = !Objects.equals(order.getCarrier(), carrier.trim())
                || !Objects.equals(order.getTrackingNumber(), number.trim())
                || !Objects.equals(order.getTrackingUrl(), link);
        order.setCarrier(carrier.trim());
        order.setTrackingNumber(number.trim());
        order.setTrackingUrl(link);
        order.setStatus(OrderStatus.SHIPPED);
        String detail = carrier.trim() + " / " + number.trim() + (link == null ? "" : "\n" + link);
        if (first) {
            order.setShippedAt(LocalDateTime.now());
            mail.order(order, "SHIPPED", detail);
        } else if (changed) {
            // 已发货后改了物流信息：再通知一次（重复保存相同内容不重发）
            mail.order(order, "TRACKING_UPDATED", String.valueOf(System.currentTimeMillis()),
                    "物流信息已更新 / Tracking updated:\n" + detail);
        }
    }

    @Transactional
    public CaseView request(Long id, String reason) {
        CustomerOrder order = locked(id);
        if (!Set.of(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED)
                .contains(order.getStatus())) {
            throw new BusinessException("当前订单状态不允许申请售后");
        }
        if (cases.findByOrderIdOrderByIdDesc(id).stream().anyMatch(this::active)) {
            throw new BusinessException("该订单已有售后申请");
        }
        AfterSale a = new AfterSale();
        a.setOrderId(id);
        a.setReason(reason.trim());
        cases.save(a);
        record(a);
        mail.order(order, "AFTER_SALE_" + a.getId(), "已收到售后申请 / After-sales request received.");
        return cases(id).get(0);
    }

    @Transactional
    public void decide(Long orderId, Long id, String action, String note) {
        CustomerOrder order = locked(orderId);
        AfterSale a =
                cases
                        .lock(id)
                        .filter(c -> c.getOrderId().equals(orderId))
                        .orElseThrow(() -> new BusinessException("售后申请不存在"));
        if ("REJECT".equals(action)) {
            if (!"REQUESTED".equals(a.getState())) {
                throw new BusinessException("当前申请不能驳回");
            }
            if (note == null || note.isBlank()) {
                throw new BusinessException("请填写驳回原因");
            }
            a.setState("REJECTED");
        } else if ("APPROVE".equals(action)) {
            if (!Set.of("REQUESTED", "FAILED").contains(a.getState())) {
                throw new BusinessException("当前申请不能批准");
            }
            if (order.getStatus() == OrderStatus.REFUNDED) {
                throw new BusinessException("订单已退款");
            }
            a.setState(order.getPaymentSessionId() == null ? "MANUAL_REQUIRED" : "PROCESSING");
            if (a.isProviderFailed()) {
                a.setRefundGeneration(a.getRefundGeneration() + 1);
                a.setRefundId(null);
                a.setProviderFailed(false);
            }
            a.setAttempts(0);
            a.setNextAttempt(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        } else if ("CONFIRM_MANUAL".equals(action)) {
            if (!"MANUAL_REQUIRED".equals(a.getState()) || note == null || note.isBlank()) {
                throw new BusinessException("请核实线下退款并填写退款凭据");
            }
            finish(order, a);
        } else {
            throw new BusinessException("未知审核操作");
        }
        a.setDecisionNote(note);
        a.setUpdatedAt(LocalDateTime.now());
        record(a);
        mail.order(
                order,
                "AFTER_SALE_" + a.getId() + "_" + a.getState(),
                "售后进度 / After-sales: " + a.getState() + (note == null ? "" : "\n" + note));
    }

    private void finish(CustomerOrder order, AfterSale a) {
        if (order.getStatus() != OrderStatus.REFUNDED) {
            // Unshipped orders can return inventory immediately. Physical returns require inspection.
            if (order.getShippedAt() == null
                    && !Set.of(OrderStatus.CANCELLED, OrderStatus.SHIPPED, OrderStatus.COMPLETED)
                            .contains(order.getStatus())) {
                order.getItems().forEach(i -> {
                    if (i.getProductId() != null) {
                        products.restock(i.getProductId(), i.getQuantity());
                    }
                });
            }
            coupons.release(order);
            order.setStatus(OrderStatus.REFUNDED);
        }
        a.setState("SUCCEEDED");
        a.setUpdatedAt(LocalDateTime.now());
        mail.order(order, "REFUNDED", "退款已完成 / Refund completed.");
    }

    /** 退款任务的快照：在数据库锁之外调用 Stripe 所需的信息。order 已脱离事务，只读其中的普通字段。 */
    private record RefundJob(Long caseId, Long orderId, int generation, String key, String refundId, CustomerOrder order) {
    }

    /**
     * 自动退款分三步，调用 Stripe 时不占数据库锁：先加锁确认仍待处理并把下次处理时间推后（相当于领取任务）后立即提交，
     * 再在事务外调用 Stripe，最后重新加锁记录结果。Stripe 有幂等键，重复请求不会重复退款。
     */
    @Scheduled(fixedDelay = 60000, initialDelayString = "${shop.refunds.initial-delay-ms:30000}")
    public void processRefunds() {
        for (AfterSale candidate
                : cases.findTop20ByStateAndNextAttemptBeforeOrderByIdAsc("PROCESSING", LocalDateTime.now())) {
            RefundJob job = transactions.execute(status -> claim(candidate.getOrderId(), candidate.getId()));
            if (job == null) {
                continue;
            }
            StripePaymentService.RefundState result = null;
            try {
                result = stripe.refund(job.order(), job.key(), job.refundId());
            } catch (BusinessException e) {
                // 结果不明确（网络、服务异常），下一步按失败计次，稍后重试前先核对
            }
            StripePaymentService.RefundState outcome = result;
            transactions.executeWithoutResult(status -> apply(job, outcome));
        }
    }

    private RefundJob claim(Long orderId, Long caseId) {
        CustomerOrder order = locked(orderId);
        AfterSale a = cases.lock(caseId).orElseThrow();
        if (!"PROCESSING".equals(a.getState()) || a.getNextAttempt().isAfter(LocalDateTime.now())) {
            return null;
        }
        // 处理期间其他轮次不再领取；第三步会按结果重新设置
        a.setNextAttempt(LocalDateTime.now().plusMinutes(10));
        String key = a.getRefundGeneration() == 0
                ? a.getId().toString()
                : a.getId() + "-retry-" + a.getRefundGeneration();
        return new RefundJob(a.getId(), order.getId(), a.getRefundGeneration(), key, a.getRefundId(), order);
    }

    /** result 为 null 表示调用 Stripe 失败、结果不明确。 */
    private void apply(RefundJob job, StripePaymentService.RefundState result) {
        CustomerOrder order = locked(job.orderId());
        AfterSale a = cases.lock(job.caseId()).orElseThrow();
        if (!"PROCESSING".equals(a.getState()) || a.getRefundGeneration() != job.generation()) {
            return;
        }
        if (result != null) {
            a.setRefundId(result.id());
            if ("succeeded".equals(result.status())) {
                finish(order, a);
            } else if (Set.of("failed", "canceled").contains(result.status())) {
                a.setState("FAILED");
                a.setProviderFailed(true);
                a.setDecisionNote("支付平台退款失败，请核查后重试");
            }
            a.setNextAttempt(LocalDateTime.now().plusMinutes(5));
        } else {
            a.setAttempts(a.getAttempts() + 1);
            if (a.getAttempts() >= 8) {
                a.setState("FAILED");
                a.setDecisionNote("退款处理异常，请核查支付平台后重试");
            }
            a.setNextAttempt(LocalDateTime.now().plusMinutes(10));
        }
        a.setUpdatedAt(LocalDateTime.now());
        if (!"PROCESSING".equals(a.getState())) {
            record(a);
            mail.order(order, "AFTER_SALE_" + a.getId() + "_" + a.getState(), "售后进度 / After-sales: " + a.getState());
        }
    }
}
