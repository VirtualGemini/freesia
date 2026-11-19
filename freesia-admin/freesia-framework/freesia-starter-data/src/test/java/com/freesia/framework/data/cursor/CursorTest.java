package com.freesia.framework.data.cursor;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {
    record Row(Instant occurredAt, long id) {}

    private static final Instant T = Instant.parse("2026-11-18T03:04:05.123456Z");

    @Test
    void encodingKeepsMicrosecondPrecisionAndNegativeIds() {
        var cursor = new Cursor(T, -7);

        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64 !", "MS4y", "YS5iLmM", "MS4yLjMuNA"})
    void foreignInputIsABadRequest(String forged) {
        assertThatThrownBy(() -> Cursor.decode(forged))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST));
    }

    @Test
    void theExtraRowOnlySignalsAnotherPage() {
        var rows = List.of(new Row(T, 3), new Row(T, 2), new Row(T, 1));

        var page = CursorPages.of(rows, 2, row -> new Cursor(row.occurredAt(), row.id()));

        assertThat(page.records()).containsExactly(new Row(T, 3), new Row(T, 2));
        assertThat(page.hasMore()).isTrue();
        assertThat(Cursor.decode(page.nextCursor())).isEqualTo(new Cursor(T, 2));
    }

    @Test
    void aPageThatFitsIsTheLastOne() {
        var page = CursorPages.of(List.of(new Row(T, 1)), 2, row -> new Cursor(row.occurredAt(), row.id()));

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
