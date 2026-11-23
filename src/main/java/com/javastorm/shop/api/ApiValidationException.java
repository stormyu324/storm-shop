package com.javastorm.shop.api;

import java.util.Map;

/** 请求内容校验不通过。 */
public class ApiValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public ApiValidationException(Map<String, String> fields) {
        super("提交的内容有误，请检查");
        this.fields = fields;
    }

    public Map<String, String> getFields() {
        return fields;
    }
}
