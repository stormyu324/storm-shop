package com.javastorm.shop.service;

import com.javastorm.shop.domain.OrderItem;
import java.util.Map;
import java.math.BigDecimal;
import com.javastorm.shop.domain.AfterSale;
import com.javastorm.shop.domain.AfterSaleHistory;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.payment.PaymentGateway;
import com.javastorm.shop.payment.PaymentGatewayRegistry;
import com.javastorm.shop.repository.AfterSaleHistoryRepository;
import com.javastorm.shop.repository.AfterSaleRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
import java.util.UUID;

@Service
public class OrderCareService {
    private final OrderRepository orders;
    private final CouponService coupons;
    private final AfterSaleRepository cases;
    private final ProductRepository products;
    private final OrderMailService mail;
    private final PaymentGatewayRegistry gateways;
    private final TransactionTemplate transactions;
    private final AfterSaleHistoryRepository history;

    public OrderCareService(
            OrderRepository orders,
            AfterSaleRepository cases,
            ProductRepository products,
            OrderMailService mail,
            PaymentGatewayRegistry gateways,
            PlatformTransactionManager manager,
            AfterSaleHistoryRepository history,
            CouponService coupons) {
        this.orders = orders;
        this.coupons = coupons;
        this.history = history;
        this.cases = cases;
        this.products = products;
        this.mail = mail;
        this.gateways = gateways;
        transactions = new TransactionTemplate(manager);
    }

    public record HistoryView(String state, String note, LocalDateTime createdAt) {}

    /**
     * 售后单状态：REQUESTED 待审核 → （需要退货时）AWAITING_RETURN 等顾客寄回 → RETURN_SHIPPED 顾客已寄出
     * → PROCESSING 自动退款中 / MANUAL_REQUIRED 需人工线下退款 → SUCCEEDED 已退款；
     * 也可能 REJECTED 已驳回、FAILED 退款失败（可重新批准）。
     *
     * @param refundAmount 本次退款金额；为空表示退剩余全部
     */
    public record CaseView(
            Long id,
            String reason,
            String state,
            String decisionNote,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<HistoryView> history,
            BigDecimal refundAmount,
            boolean returnRequired,
            String returnCarrier,
            String returnTracking,
            LocalDateTime returnedAt) {}

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
                                                .toList(),
                                        a.getRefundAmount(),
                                        a.isReturnRequired(),
                                        a.getReturnCarrier(),
                                        a.getReturnTracking(),
                                        a.getReturnedAt()))
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
        return a.isOpen();
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
            // 已发货后改了物流信息：再通知一次（重复保存相同内容不重发；用 UUID 避免同毫秒内多次修改碰撞）
            mail.order(order, "TRACKING_UPDATED", UUID.randomUUID().toString(),
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
        if (order.getRefundableAmount().signum() <= 0) {
            throw new BusinessException("订单金额已全部退款");
        }
        if (cases.findByOrderIdOrderByIdDesc(id).stream().anyMatch(this::active)) {
            throw new BusinessException("该订单已有处理中的售后申请");
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
        decide(orderId, id, action, note, null, false, Map.of());
    }

    /**
     * 商家审核售后单。
     *
     * @param amount        APPROVE 时的退款金额，为空表示退剩余全部；不能超过订单剩余可退金额
     * @param requireReturn APPROVE 时是否要求顾客先寄回商品，收到后（RECEIVE）再退款
     * @param restock       APPROVE（不需退货时）或 RECEIVE 时要重新入库的商品：订单明细 id → 数量
     */
    @Transactional
    public void decide(Long orderId, Long id, String action, String note, BigDecimal amount, boolean requireReturn,
                       Map<Long, Integer> restock) {
        CustomerOrder order = locked(orderId);
        AfterSale a =
                cases
                        .lock(id)
                        .filter(c -> c.getOrderId().equals(orderId))
                        .orElseThrow(() -> new BusinessException("售后申请不存在"));
        String detail = note;
        if ("REJECT".equals(action)) {
            if (!Set.of("REQUESTED", "AWAITING_RETURN", "RETURN_SHIPPED").contains(a.getState())) {
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
            if (order.getStatus() == OrderStatus.REFUNDED || order.getRefundableAmount().signum() <= 0) {
                throw new BusinessException("订单已全部退款");
            }
            if ("REQUESTED".equals(a.getState())) {
                a.setRefundAmount(checkedAmount(order, amount));
            } else if (amount != null) {
                // 退款失败后重新批准时可以改金额
                a.setRefundAmount(checkedAmount(order, amount));
            }
            if ("REQUESTED".equals(a.getState()) && requireReturn) {
                a.setReturnRequired(true);
                a.setState("AWAITING_RETURN");
            } else {
                detail = join(note, restock(order, restock));
                startRefund(order, a);
            }
        } else if ("RECEIVE".equals(action)) {
            if (!Set.of("AWAITING_RETURN", "RETURN_SHIPPED").contains(a.getState())) {
                throw new BusinessException("当前申请不是等待退货状态");
            }
            a.setReturnedAt(LocalDateTime.now());
            detail = join("已收到退货" + (note == null || note.isBlank() ? "" : "：" + note), restock(order, restock));
            startRefund(order, a);
        } else if ("CONFIRM_MANUAL".equals(action)) {
            if (!"MANUAL_REQUIRED".equals(a.getState()) || note == null || note.isBlank()) {
                throw new BusinessException("请核实线下退款并填写退款凭据");
            }
            finish(order, a);
        } else {
            throw new BusinessException("未知审核操作");
        }
        a.setDecisionNote(detail);
        a.setUpdatedAt(LocalDateTime.now());
        record(a);
        mail.order(
                order,
                "AFTER_SALE_" + a.getId() + "_" + a.getState(),
                "售后进度 / After-sales: " + stateText(a) + (detail == null ? "" : "\n" + detail));
    }

    /** 顾客把退货寄出后填写物流单号。 */
    @Transactional
    public CaseView submitReturn(Long orderId, Long caseId, String carrier, String trackingNumber) {
        CustomerOrder order = locked(orderId);
        AfterSale a = cases.lock(caseId)
                .filter(c -> c.getOrderId().equals(orderId))
                .orElseThrow(() -> new BusinessException("售后申请不存在"));
        if (!Set.of("AWAITING_RETURN", "RETURN_SHIPPED").contains(a.getState())) {
            throw new BusinessException("这个售后申请不需要寄回商品");
        }
        a.setReturnCarrier(carrier.trim());
        a.setReturnTracking(trackingNumber.trim());
        a.setState("RETURN_SHIPPED");
        a.setDecisionNote("顾客已寄回：" + carrier.trim() + " / " + trackingNumber.trim());
        a.setUpdatedAt(LocalDateTime.now());
        record(a);
        mail.order(order, "AFTER_SALE_" + a.getId() + "_RETURN_SHIPPED_" + trackingNumber.trim(),
                "已收到您的退货物流信息，商家收到商品后会尽快退款 / Return tracking received: "
                        + carrier.trim() + " " + trackingNumber.trim());
        return cases(orderId).stream().filter(c -> c.id().equals(caseId)).findFirst().orElseThrow();
    }

    private static BigDecimal checkedAmount(CustomerOrder order, BigDecimal amount) {
        BigDecimal refundable = order.getRefundableAmount();
        if (amount == null) {
            return refundable;
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new BusinessException("退款金额必须大于 0，最多两位小数");
        }
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessException("退款金额不能超过剩余可退的 " + refundable.toPlainString());
        }
        return amount;
    }

    /** 进入退款：在线付款的订单自动原路退回，线下收款的等商家线下退款后确认。 */
    private void startRefund(CustomerOrder order, AfterSale a) {
        a.setState(order.getPaymentSessionId() == null ? "MANUAL_REQUIRED" : "PROCESSING");
        if (a.isProviderFailed()) {
            a.setRefundGeneration(a.getRefundGeneration() + 1);
            a.setRefundId(null);
            a.setProviderFailed(false);
        }
        a.setAttempts(0);
        a.setNextAttempt(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** 把退回的商品重新入库（每件商品累计退回数量不超过购买数量）；返回记录用的说明。 */
    private String restock(CustomerOrder order, Map<Long, Integer> restock) {
        if (restock == null || restock.isEmpty()) {
            return null;
        }
        List<String> done = new java.util.ArrayList<>();
        for (Map.Entry<Long, Integer> e : restock.entrySet()) {
            int qty = e.getValue() == null ? 0 : e.getValue();
            if (qty <= 0) {
                continue;
            }
            OrderItem item = order.getItems().stream().filter(i -> i.getId().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new BusinessException("订单里没有这件商品"));
            int left = item.getQuantity() - item.getReturnedQuantity();
            if (qty > left) {
                throw new BusinessException("「" + item.getProductName() + "」最多还能退回入库 " + left + " 件");
            }
            if (item.getProductId() != null) {
                products.restock(item.getProductId(), qty);
            }
            item.setReturnedQuantity(item.getReturnedQuantity() + qty);
            done.add(item.getProductName() + " × " + qty);
        }
        return done.isEmpty() ? null : "已重新入库：" + String.join("、", done);
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return b == null ? a : a + "\n" + b;
    }

    private static String stateText(AfterSale a) {
        return switch (a.getState()) {
            case "AWAITING_RETURN" -> "请寄回商品，并在订单查询页填写退货物流单号 / Please return the item and submit the tracking number";
            case "RETURN_SHIPPED" -> "已收到退货物流信息 / Return in transit";
            case "PROCESSING" -> "退款处理中 / Refund in progress";
            case "MANUAL_REQUIRED" -> "商家将线下退款 / Refund will be made offline";
            case "SUCCEEDED" -> "退款已完成 / Refund completed";
            case "REJECTED" -> "申请未通过 / Request declined";
            default -> a.getState();
        };
    }

    private void finish(CustomerOrder order, AfterSale a) {
        BigDecimal amount = a.getRefundAmount() == null ? order.getRefundableAmount()
                : a.getRefundAmount().min(order.getRefundableAmount());
        order.setRefundedAmount(order.getRefundedAmount().add(amount));
        boolean full = order.getRefundableAmount().signum() <= 0;
        if (full && order.getStatus() != OrderStatus.REFUNDED) {
            // 没发货就全额退款：剩下没退回入库的商品全部回到库存。已发货的要等实物退回后由商家确认入库
            if (order.getShippedAt() == null
                    && !Set.of(OrderStatus.CANCELLED, OrderStatus.SHIPPED, OrderStatus.COMPLETED)
                            .contains(order.getStatus())) {
                order.getItems().forEach(i -> {
                    int left = i.getQuantity() - i.getReturnedQuantity();
                    if (i.getProductId() != null && left > 0) {
                        products.restock(i.getProductId(), left);
                        i.setReturnedQuantity(i.getQuantity());
                    }
                });
            }
            coupons.release(order);
            order.setStatus(OrderStatus.REFUNDED);
        }
        a.setRefundAmount(amount);
        a.setState("SUCCEEDED");
        a.setUpdatedAt(LocalDateTime.now());
        mail.order(order, "REFUNDED_" + a.getId(), (full ? "退款已完成 / Refund completed: " : "部分退款已完成 / Partial refund completed: ")
                + amount.toPlainString() + " " + order.getCurrency());
    }

    /** 退款任务的快照：在数据库锁之外调用 Stripe 所需的信息。order 已脱离事务，只读其中的普通字段。 */
    private record RefundJob(Long caseId, Long orderId, int generation, String key, String refundId, CustomerOrder order,
                             BigDecimal amount) {
    }

    /**
     * 自动退款分三步，调用 Stripe 时不占数据库锁：先加锁确认仍待处理并把下次处理时间推后（相当于领取任务）后立即提交，
     * 再在事务外调用 Stripe，最后重新加锁记录结果。Stripe 有幂等键，重复请求不会重复退款。
     */
    @Scheduled(fixedDelay = 60000, initialDelayString = "${shop.refunds.initial-delay-ms:30000}")
    @SchedulerLock(name = "after_sale_process_refunds", lockAtMostFor = "PT5M")
    public void processRefunds() {
        for (AfterSale candidate
                : cases.findTop20ByStateAndNextAttemptBeforeOrderByIdAsc("PROCESSING", LocalDateTime.now())) {
            RefundJob job = transactions.execute(status -> claim(candidate.getOrderId(), candidate.getId()));
            if (job == null) {
                continue;
            }
            PaymentGateway.RefundResult result = null;
            try {
                PaymentGateway gateway = gateways.forSession(job.order().getPaymentSessionId())
                        .orElseThrow(() -> new BusinessException("无法识别的支付通道"));
                result = gateway.refund(job.order(), job.amount(), job.key(), job.refundId());
            } catch (BusinessException e) {
                // 结果不明确（网络、服务异常），下一步按失败计次，稍后重试前先核对
            }
            PaymentGateway.RefundResult outcome = result;
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
        BigDecimal amount = a.getRefundAmount() == null ? order.getRefundableAmount() : a.getRefundAmount();
        return new RefundJob(a.getId(), order.getId(), a.getRefundGeneration(), key, a.getRefundId(), order, amount);
    }

    /** result 为 null 表示调用支付平台失败、结果不明确。 */
    private void apply(RefundJob job, PaymentGateway.RefundResult result) {
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
