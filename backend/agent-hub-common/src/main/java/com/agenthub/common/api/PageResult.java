package com.agenthub.common.api;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int limit, int offset) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        total = Math.max(0L, total);
        limit = Math.max(0, limit);
        offset = Math.max(0, offset);
    }

    public static <T> PageResult<T> of(List<T> items, long total, int limit, int offset) {
        return new PageResult<>(items, total, limit, offset);
    }
}
