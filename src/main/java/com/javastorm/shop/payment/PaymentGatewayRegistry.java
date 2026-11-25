package com.javastorm.shop.payment;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.service.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 支付网关路由器：按币种、通道名或 paymentSessionId 路由到对应的 {@link PaymentGateway}。 */
@Component
public class PaymentGatewayRegistry {

    private final List<PaymentGateway> gateways;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        this.gateways = gateways;
    }

    public List<String> enabledProvidersFor(Currency currency) {
        return gateways.stream()
                .filter(g -> g.isEnabledFor(currency))
                .map(PaymentGateway::provider)
                .toList();
    }

    public boolean anyEnabledFor(Currency currency) {
        return gateways.stream().anyMatch(g -> g.isEnabledFor(currency));
    }

    public PaymentGateway resolve(String provider, Currency currency) {
        if (provider == null || provider.isBlank()) {
            return gateways.stream()
                    .filter(g -> g.isEnabledFor(currency))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("当前币种未开通在线支付"));
        }
        String target = provider.trim().toLowerCase(Locale.ROOT);
        return gateways.stream()
                .filter(g -> g.provider().equalsIgnoreCase(target) && g.isEnabledFor(currency))
                .findFirst()
                .orElseThrow(() -> new BusinessException("不支持或未启用支付方式：" + provider));
    }

    public Optional<PaymentGateway> forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return gateways.stream()
                .filter(g -> g.ownsSession(sessionId))
                .findFirst();
    }
}
