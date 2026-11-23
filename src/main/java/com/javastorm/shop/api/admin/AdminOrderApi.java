package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.AdminDtos.AdminOrder;
import com.javastorm.shop.api.AdminDtos.StatusChange;
import com.javastorm.shop.api.ApiExceptionHandler.NotFoundException;
import com.javastorm.shop.api.ApiMapper;
import com.javastorm.shop.api.PageDto;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.payment.PaymentFlow;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "后台 - 订单")
public class AdminOrderApi {

    private final OrderRepository orders;
    private final OrderService orderService;
    private final PaymentFlow paymentFlow;
    private final ApiMapper mapper;

    public AdminOrderApi(OrderRepository orders, OrderService orderService, PaymentFlow paymentFlow, ApiMapper mapper) {
        this.orders = orders;
        this.orderService = orderService;
        this.paymentFlow = paymentFlow;
        this.mapper = mapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "订单列表", description = "按下单时间倒序")
    public PageDto<AdminOrder> list(@RequestParam(required = false) OrderStatus status,
                                    @Parameter(description = "true 时只看待发货（待处理 + 已付款）") @RequestParam(defaultValue = "false") boolean toFulfil,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("id").descending());
        var result = toFulfil ? orders.findByStatusIn(OrderStatus.toFulfil(), pageable)
                : status != null ? orders.findByStatus(status, pageable) : orders.findAll(pageable);
        return PageDto.of(result, mapper::adminOrder);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    @Operation(summary = "订单详情")
    public AdminOrder get(@PathVariable Long id) {
        return mapper.adminOrder(find(id));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "修改订单状态", description = "只能改成订单 nextStatuses 里的状态；取消订单会退回库存")
    public AdminOrder changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChange req) {
        CustomerOrder order = find(id);
        if (req.status() == OrderStatus.CANCELLED) {
            paymentFlow.beforeAdminCancel(order);
        }
        orderService.changeStatus(id, req.status());
        return mapper.adminOrder(find(id));
    }

    private CustomerOrder find(Long id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("订单不存在"));
    }
}
