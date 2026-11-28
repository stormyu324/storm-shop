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
                // 服务正常、只是拒绝了这次请求（付款被拒、4xx）不算故障，不计入失败率
                .ignoreExceptions(RemoteRejectedException.class)
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

    /** 熔断器是否打开（调用被暂停）；还没调用过的服务视为关闭。 */
    public boolean isOpen(String name) {
        return registry.find(name).map(cb -> cb.getState() == CircuitBreaker.State.OPEN
                || cb.getState() == CircuitBreaker.State.FORCED_OPEN).orElse(false);
    }

    public CircuitBreaker.State state(String name) {
        return registry.circuitBreaker(name).getState();
    }
}
