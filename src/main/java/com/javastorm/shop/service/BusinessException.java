package com.javastorm.shop.service;

/**
 * 面向用户的业务错误，消息会直接展示在页面上。
 * 前台用到的错误带上 messages*.properties 里的 code，可按顾客选择的语言显示。
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final transient Object[] args;

    public BusinessException(String message) {
        this(null, message);
    }

    public BusinessException(String code, String defaultMessage, Object... args) {
        super(defaultMessage);
        this.code = code;
        this.args = args;
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }
}
