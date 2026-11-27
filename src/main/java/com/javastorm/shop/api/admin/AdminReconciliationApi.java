package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.PageDto;
import com.javastorm.shop.domain.PaymentReconciliationIssue;
import com.javastorm.shop.payment.PaymentReconciliationService;
import com.javastorm.shop.repository.PaymentReconciliationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/reconciliation")
@Tag(name = "后台 - 支付对账")
public class AdminReconciliationApi {

    private final PaymentReconciliationService reconciliation;
    private final PaymentReconciliationRepository issues;

    public AdminReconciliationApi(PaymentReconciliationService reconciliation,
                                  PaymentReconciliationRepository issues) {
        this.reconciliation = reconciliation;
        this.issues = issues;
    }

    @GetMapping("/issues")
    @Operation(summary = "查询对账异常记录", description = "默认只返回未处理的资金异常")
    public PageDto<PaymentReconciliationIssue> list(@RequestParam(defaultValue = "true") boolean unresolvedOnly,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = unresolvedOnly
                ? issues.findByResolvedFalseOrderByIdDesc(pageable)
                : issues.findAllByOrderByIdDesc(pageable);
        return PageDto.of(result, i -> i);
    }

    @PostMapping("/run")
    @Operation(summary = "手动触发指定日期的 Stripe 对账", description = "不传 date 时默认核对昨天")
    public PaymentReconciliationService.Summary run(@RequestParam(required = false) LocalDate date) {
        return reconciliation.reconcileDate(date == null ? LocalDate.now().minusDays(1) : date);
    }

    @PostMapping("/issues/{id}/resolve")
    @Operation(summary = "标记对账异常已处理")
    public PaymentReconciliationIssue resolve(@PathVariable Long id) {
        return reconciliation.resolve(id);
    }
}
