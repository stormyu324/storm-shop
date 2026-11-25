package com.javastorm.shop.config;

import com.javastorm.shop.service.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 外部依赖（Stripe / PayPal / Claude AI）熔断保护中心：
 * 连续失败超过阈值后快速熔断 30 秒，防止第三方接口抖动拖垮主站 Tomcat 线程池。
 */
@Component
public class ExternalCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public ExternalCircuitBreakers() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    public <T> T execute(String name, Supplier<T> action, String unavailableCode, String unavailableMessage) {
        CircuitBreaker cb = registry.circuitBreaker(name);
        try {
            return cb.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            throw new BusinessException(unavailableCode, unavailableMessage);
        }
    }

    public CircuitBreaker.State state(String name) {
        return registry.circuitBreaker(name).getState();
    }
}
