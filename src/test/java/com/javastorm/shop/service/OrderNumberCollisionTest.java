package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 压测发现：并发下单时两单偶尔拿到同一个订单号，后写入的那单以前直接返回 500。 */
class OrderNumberCollisionTest {

    private static DataIntegrityViolationException duplicate(String dbMessage) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException(dbMessage));
    }

    @Test
    void duplicateOrderNumberIsRetriedWithANewNumber() {
        OrderService service = mock(OrderService.class);
        CustomerOrder placed = new CustomerOrder();
        when(service.placeOrder(any(), any(), any(), anyBoolean()))
                .thenThrow(duplicate("Duplicate entry '20261128124440123456' for key 'customer_order.order_no'"))
                .thenReturn(placed);

        CustomerOrder result = new OrderPlacer(service).place(new Cart(), new CheckoutForm(), Currency.CNY, false);

        assertThat(result).isSameAs(placed);
        verify(service, times(2)).placeOrder(any(), any(), any(), anyBoolean());
    }

    @Test
    void recognisesH2AndMySqlDuplicateOrderNumberErrors() {
        assertThat(OrderPlacer.isDuplicateOrderNo(duplicate(
                "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_3B ON PUBLIC.CUSTOMER_ORDER(ORDER_NO NULLS FIRST)\""))).isTrue();
        assertThat(OrderPlacer.isDuplicateOrderNo(duplicate(
                "Duplicate entry '1' for key 'customer_order.order_no'"))).isTrue();
        assertThat(OrderPlacer.isDuplicateOrderNo(duplicate(
                "Column 'customer_name' cannot be null"))).isFalse();
    }

    @Test
    void otherIntegrityErrorsAreNotRetried() {
        OrderService service = mock(OrderService.class);
        when(service.placeOrder(any(), any(), any(), anyBoolean()))
                .thenThrow(duplicate("Column 'customer_name' cannot be null"));

        assertThatThrownBy(() -> new OrderPlacer(service).place(new Cart(), new CheckoutForm(), Currency.CNY, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(service, times(1)).placeOrder(any(), any(), any(), anyBoolean());
    }

    @Test
    void givesUpWithAFriendlyMessageAfterRepeatedCollisions() {
        OrderService service = mock(OrderService.class);
        when(service.placeOrder(any(), any(), any(), anyBoolean()))
                .thenThrow(duplicate("Duplicate entry 'x' for key 'customer_order.order_no'"));

        assertThatThrownBy(() -> new OrderPlacer(service).place(new Cart(), new CheckoutForm(), Currency.CNY, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("下单人数较多");
        verify(service, times(OrderPlacer.MAX_ATTEMPTS)).placeOrder(any(), any(), any(), anyBoolean());
    }
}
