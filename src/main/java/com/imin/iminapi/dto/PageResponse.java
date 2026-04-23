package com.imin.iminapi.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, long total, int page, int pageSize) {
    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> map) {
        return new PageResponse<>(source.map(map).getContent(),
                source.getTotalElements(), source.getNumber() + 1, source.getSize());
    }
}
