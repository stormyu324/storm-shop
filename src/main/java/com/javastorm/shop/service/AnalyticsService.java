package com.javastorm.shop.service;

import com.javastorm.shop.domain.*;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.repository.OrderRepository;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {
  private final OrderRepository orders;

  public AnalyticsService(OrderRepository orders) {
    this.orders = orders;
  }

  public record Daily(LocalDate date, long orders, BigDecimal paid, BigDecimal refunded) {}

  public record TopProduct(Long productId, String title, long quantity) {}

  public record Summary(
      Currency currency,
      long orders,
      long paidOrders,
      BigDecimal paid,
      BigDecimal refunded,
      BigDecimal net,
      BigDecimal averageOrder,
      BigDecimal paymentConversion,
      List<Daily> daily,
      List<TopProduct> topProducts) {}

  /**
   * 是否算作已收款：在线支付以付款时间为准；线下确认的订单（没有在线支付）发货、完成或退款后才算收款，
   * 否则默认的人民币线下流程收入永远显示为 0。
   */
  static boolean received(CustomerOrder o) {
    if (o.getPaidAt() != null) return true;
    return o.getPaymentSessionId() == null
        && java.util.Set.of(OrderStatus.SHIPPED, OrderStatus.COMPLETED, OrderStatus.REFUNDED).contains(o.getStatus());
  }

  @Transactional(readOnly = true)
  public List<Summary> report(LocalDate start, LocalDate end) {
    if (start == null
        || end == null
        || end.isBefore(start)
        || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 365)
      throw new BusinessException("日期范围须在 366 天内");
    var all =
        orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    List<Summary> report = new ArrayList<>();
    for (Currency currency : Currency.values()) {
      var list = all.stream().filter(o -> o.getCurrency() == currency).toList();
      var paid = list.stream().filter(AnalyticsService::received).toList();
      BigDecimal gross =
          paid.stream().map(CustomerOrder::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal refunded =
          paid.stream()
              .filter(o -> o.getStatus() == OrderStatus.REFUNDED)
              .map(CustomerOrder::getTotal)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      List<Daily> daily = new ArrayList<>();
      for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
        LocalDate date = day;
        var rows = list.stream().filter(o -> o.getCreatedAt().toLocalDate().equals(date)).toList();
        var dayPaid = rows.stream().filter(AnalyticsService::received).toList();
        daily.add(
            new Daily(
                day,
                rows.size(),
                dayPaid.stream()
                    .map(CustomerOrder::getTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add),
                dayPaid.stream()
                    .filter(o -> o.getStatus() == OrderStatus.REFUNDED)
                    .map(CustomerOrder::getTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
      }
      Map<Long, TopProduct> top = new HashMap<>();
      paid.stream()
          .filter(o -> o.getStatus() != OrderStatus.REFUNDED)
          .flatMap(o -> o.getItems().stream())
          .forEach(
              i -> {
                var previous = top.get(i.getProductId());
                top.put(
                    i.getProductId(),
                    new TopProduct(
                        i.getProductId(),
                        i.getProductName(),
                        i.getQuantity() + (previous == null ? 0 : previous.quantity())));
              });
      report.add(
          new Summary(
              currency,
              list.size(),
              paid.size(),
              gross,
              refunded,
              gross.subtract(refunded),
              paid.isEmpty()
                  ? BigDecimal.ZERO
                  : gross.divide(BigDecimal.valueOf(paid.size()), 2, RoundingMode.HALF_UP),
              list.isEmpty()
                  ? BigDecimal.ZERO
                  : BigDecimal.valueOf(paid.size() * 100L)
                      .divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP),
              daily,
              top.values().stream()
                  .sorted(Comparator.comparingLong(TopProduct::quantity).reversed())
                  .limit(10)
                  .toList()));
    }
    return report;
  }
}
