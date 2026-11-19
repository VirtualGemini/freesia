package com.freesia.framework.data.cursor;

import com.freesia.common.result.CursorPage;

import java.util.List;
import java.util.function.Function;

public final class CursorPages {
    private CursorPages() {}

    /**
     * Builds a page from rows fetched with {@code LIMIT limit + 1}: the extra row only proves that
     * another page exists and is not returned.
     */
    public static <T> CursorPage<T> of(List<T> rows, int limit, Function<T, Cursor> position) {
        if (rows.size() <= limit) {
            return CursorPage.last(rows);
        }
        List<T> page = rows.subList(0, limit);
        return new CursorPage<>(page, position.apply(page.getLast()).encode(), true);
    }
}
