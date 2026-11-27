package com.javastorm.shop.config;

import com.javastorm.shop.service.BusinessException;

/**
 * 外部服务正常工作、只是拒绝了这次请求（如付款被拒、参数不合法、记录不存在，HTTP 4xx）。
 * 这不是外部服务故障，{@link ExternalCircuitBreakers} 不把它计入熔断；
 * 否则短时间内几笔付款被拒，就会让所有顾客的结账被暂停。
 */
public class RemoteRejectedException extends BusinessException {

    public RemoteRejectedException(String code, String defaultMessage, Object... args) {
        super(code, defaultMessage, args);
    }
}
