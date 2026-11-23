package com.javastorm.shop.service;

import com.javastorm.shop.domain.*;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.*;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
      AfterSaleHistoryRepository history, CouponService coupons) {
    this.coupons=coupons;
    this.history = history;
    this.orders = orders;
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
                    a.id,
                    a.reason,
                    a.state,
                    a.decisionNote,
                    a.createdAt,
                    a.updatedAt,
                    history.findByCaseIdOrderByIdAsc(a.id).stream()
                        .map(h -> new HistoryView(h.state, h.note, h.createdAt))
                        .toList()))
        .toList();
  }

  private CustomerOrder locked(Long id) {
    return orders.findForUpdate(id).orElseThrow(() -> new BusinessException("订单不存在"));
  }

  private void record(AfterSale a) {
    AfterSaleHistory event = new AfterSaleHistory();
    event.caseId = a.id;
    event.state = a.state;
    event.note = a.decisionNote;
    history.save(event);
  }

  private boolean active(AfterSale a) {
    return !"REJECTED".equals(a.state);
  }

  @Transactional
  public void ship(Long id, String carrier, String number, String url) {
    CustomerOrder order = locked(id);
    if (!Set.of(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.SHIPPED)
        .contains(order.getStatus())) throw new BusinessException("当前订单状态不允许发货");
    if (cases.findByOrderIdOrderByIdDesc(id).stream().anyMatch(this::active))
      throw new BusinessException("订单有待处理售后，请先处理售后");
    if (url != null && !url.isBlank()) {
      try {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new BusinessException("物流链接须为有效 HTTPS 地址");
      }
    }
    boolean first = order.getShippedAt() == null;
    order.setCarrier(carrier.trim());
    order.setTrackingNumber(number.trim());
    order.setTrackingUrl(url);
    order.setStatus(OrderStatus.SHIPPED);
    if (first) order.setShippedAt(LocalDateTime.now());
    mail.order(order, "SHIPPED", carrier + " / " + number + (url == null ? "" : "\n" + url));
  }

  @Transactional
  public CaseView request(Long id, String reason) {
    CustomerOrder order = locked(id);
    if (!Set.of(OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED)
        .contains(order.getStatus())) throw new BusinessException("当前订单状态不允许申请售后");
    if (cases.findByOrderIdOrderByIdDesc(id).stream().anyMatch(this::active))
      throw new BusinessException("该订单已有售后申请");
    AfterSale a = new AfterSale();
    a.orderId = id;
    a.reason = reason.trim();
    cases.save(a);
    record(a);
    mail.order(order, "AFTER_SALE_" + a.id, "已收到售后申请 / After-sales request received.");
    return cases(id).get(0);
  }

  @Transactional
  public void decide(Long orderId, Long id, String action, String note) {
    CustomerOrder order = locked(orderId);
    AfterSale a =
        cases
            .lock(id)
            .filter(c -> c.orderId.equals(orderId))
            .orElseThrow(() -> new BusinessException("售后申请不存在"));
    if ("REJECT".equals(action)) {
      if (!"REQUESTED".equals(a.state)) throw new BusinessException("当前申请不能驳回");
      if (note == null || note.isBlank()) throw new BusinessException("请填写驳回原因");
      a.state = "REJECTED";
    } else if ("APPROVE".equals(action)) {
      if (!Set.of("REQUESTED", "FAILED").contains(a.state)) throw new BusinessException("当前申请不能批准");
      if (order.getStatus() == OrderStatus.REFUNDED) throw new BusinessException("订单已退款");
      a.state = order.getPaymentSessionId() == null ? "MANUAL_REQUIRED" : "PROCESSING";
      if (a.providerFailed) {
        a.refundGeneration++;
        a.refundId = null;
        a.providerFailed = false;
      }
      a.attempts = 0;
      a.nextAttempt = LocalDateTime.now();
    } else if ("CONFIRM_MANUAL".equals(action)) {
      if (!"MANUAL_REQUIRED".equals(a.state) || note == null || note.isBlank())
        throw new BusinessException("请核实线下退款并填写退款凭据");
      finish(order, a);
    } else throw new BusinessException("未知审核操作");
    a.decisionNote = note;
    a.updatedAt = LocalDateTime.now();
    record(a);
    mail.order(
        order,
        "AFTER_SALE_" + a.id + "_" + a.state,
        "售后进度 / After-sales: " + a.state + (note == null ? "" : "\n" + note));
  }

  private void finish(CustomerOrder order, AfterSale a) {
    if (order.getStatus() != OrderStatus.REFUNDED) {
      // Unshipped orders can return inventory immediately. Physical returns require inspection.
      if (order.getShippedAt() == null
          && !Set.of(OrderStatus.CANCELLED, OrderStatus.SHIPPED, OrderStatus.COMPLETED)
              .contains(order.getStatus())) {
        order
            .getItems()
            .forEach(
                i -> {
                  if (i.getProductId() != null)
                    products
                        .findById(i.getProductId())
                        .ifPresent(p -> p.setStock(p.getStock() + i.getQuantity()));
                });
      }
      coupons.release(order);
      order.setStatus(OrderStatus.REFUNDED);
    }
    a.state = "SUCCEEDED";
    a.updatedAt = LocalDateTime.now();
    mail.order(order, "REFUNDED", "退款已完成 / Refund completed.");
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 30000)
  public void processRefunds() {
    for (var candidate :
        cases.findTop20ByStateAndNextAttemptBeforeOrderByIdAsc("PROCESSING", LocalDateTime.now())) {
      transactions.executeWithoutResult(
          status -> {
            CustomerOrder order = locked(candidate.orderId);
            AfterSale a = cases.lock(candidate.id).orElseThrow();
            if (!"PROCESSING".equals(a.state) || a.nextAttempt.isAfter(LocalDateTime.now())) return;
            String previousState = a.state;
            try {
              var result = stripe.refund(order, a.refundGeneration == 0 ? a.id.toString() : a.id + "-retry-" + a.refundGeneration, a.refundId);
              a.refundId = result.id();
              if ("succeeded".equals(result.status())) finish(order, a);
              else if (Set.of("failed", "canceled").contains(result.status())) {
                a.state = "FAILED";
                      a.providerFailed = true;
                a.decisionNote = "支付平台退款失败，请核查后重试";
              }
              a.nextAttempt = LocalDateTime.now().plusMinutes(5);
            } catch (BusinessException e) {
              a.attempts++;
              // Reconcile before retrying after an ambiguous provider failure.
              if (a.attempts >= 8) {
                a.state = "FAILED";
                a.decisionNote = "退款处理异常，请核查支付平台后重试";
              }
              a.nextAttempt = LocalDateTime.now().plusMinutes(10);
            }
            a.updatedAt = LocalDateTime.now();
            if (!previousState.equals(a.state)) {
              record(a);
              mail.order(
                  order, "AFTER_SALE_" + a.id + "_" + a.state, "售后进度 / After-sales: " + a.state);
            }
          });
    }
  }
}
