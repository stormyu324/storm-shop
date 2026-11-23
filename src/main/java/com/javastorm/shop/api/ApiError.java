package com.javastorm.shop.api;

import java.util.Map;

/** 接口统一的错误格式：message 是给人看的说明，fields 是表单字段的校验错误（字段名 → 说明）。 */
public record ApiError(String message, Map<String, String> fields) {

    public static ApiError of(String message) {
        return new ApiError(message, Map.of());
    }
}
