package com.javastorm.shop.config;

import com.javastorm.shop.service.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 TOTP 双因素验证码与 Resilience4j 外部依赖熔断器。 */
class TotpAndCircuitBreakerTest {

    @Test
    void totpVerifiesCurrentAndAdjacentStepsAndRejectsWrongCode() {
        String secret = "JBSWY3DPEHPK3PXP";
        Instant now = Instant.parse("2026-11-25T10:00:00Z");
        String current = TotpVerifier.generate(secret, now);
        String previous = TotpVerifier.generate(secret, now.minusSeconds(30));

        assertThat(current).hasSize(6);
        assertThat(TotpVerifier.verify(secret, current, now)).isTrue();
        assertThat(TotpVerifier.verify(secret, previous, now)).isTrue();
        assertThat(TotpVerifier.verify(secret, "000000", now)).isFalse();
        assertThat(TotpVerifier.verify(secret, "abc", now)).isFalse();
    }

    @Test
    void circuitBreakerOpensAfterFailuresAndFailsFast() {
        ExternalCircuitBreakers breakers = new ExternalCircuitBreakers();
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breakers.execute("test-upstream", () -> {
                calls.incrementAndGet();
                throw new RuntimeException("upstream down");
            }, "error.upstream", "上游服务不可用"))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(breakers.state("test-upstream")).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = calls.get();

        // 熔断开启后应直接快速失败，不再调用上游
        assertThatThrownBy(() -> breakers.execute("test-upstream", () -> {
            calls.incrementAndGet();
            return "ok";
        }, "error.upstream", "上游服务不可用"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("上游服务不可用");
        assertThat(calls.get()).isEqualTo(callsWhenOpened);
    }
}
