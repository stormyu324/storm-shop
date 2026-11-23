package com.javastorm.shop.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** 分页结果：page 从 0 开始。 */
public record PageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageDto<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageDto<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
