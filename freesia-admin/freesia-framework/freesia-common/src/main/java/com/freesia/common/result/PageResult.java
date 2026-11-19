package com.freesia.common.result;

import java.util.List;

/** Page-number pagination for ordinary admin lists; {@code total} is an exact count. */
public record PageResult<T>(List<T> records, long current, long size, long total) {
    public PageResult {
        records = List.copyOf(records);
    }

    public static <T> PageResult<T> empty(long current, long size) {
        return new PageResult<>(List.of(), current, size, 0);
    }
}
