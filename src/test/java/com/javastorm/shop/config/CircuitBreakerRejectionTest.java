package com.javastorm.shop.config;

import com.javastorm.shop.service.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 付款被拒等“服务正常、只是拒绝了请求”的结果不能把熔断器打开，否则会挡住所有顾客结账。 */
class CircuitBreakerRejectionTest {

    @Test
    void rejectionsDoNotOpenTheBreaker() {
        ExternalCircuitBreakers breakers = new ExternalCircuitBreakers();
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> breakers.execute("paypal", () -> {
                throw new RemoteRejectedException("error.payment.rejected", "被拒");
            }, "error.payment.unavailable", "不可用")).isInstanceOf(RemoteRejectedException.class);
        }
        assertThat(breakers.state("paypal")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakers.execute("paypal", () -> "ok", "error.payment.unavailable", "不可用")).isEqualTo("ok");
    }

    @Test
    void realOutagesStillOpenTheBreaker() {
        ExternalCircuitBreakers breakers = new ExternalCircuitBreakers();
        for (int i = 0; i < 10; i++) {
            try {
                breakers.execute("paypal", () -> {
                    throw new BusinessException("error.payment.unavailable", "PayPal 不可用");
                }, "error.payment.unavailable", "不可用");
            } catch (BusinessException ignored) {
                // 预期失败
            }
        }
        assertThat(breakers.state("paypal")).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
