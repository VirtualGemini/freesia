package com.freesia.common.result;

import java.util.List;

/**
 * Keyset pagination for large tables. There is no total: counting defeats the purpose.
 * {@code nextCursor} is opaque to clients and null on the last page.
 */
public record CursorPage<T>(List<T> records, String nextCursor, boolean hasMore) {
    public CursorPage {
        records = List.copyOf(records);
    }

    public static <T> CursorPage<T> last(List<T> records) {
        return new CursorPage<>(records, null, false);
    }
}
