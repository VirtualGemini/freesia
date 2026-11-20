package com.freesia.framework.data.cursor;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {
    record Row(Instant occurredAt, UUID id) {}

    private static final Instant T = Instant.parse("2026-11-18T03:04:05.123456Z");
    private static final UUID FIRST = UUID.fromString("019a90f1-0000-7000-8000-000000000003");
    private static final UUID SECOND = UUID.fromString("019a90f1-0000-7000-8000-000000000002");
    private static final UUID THIRD = UUID.fromString("019a90f1-0000-7000-8000-000000000001");

    @Test
    void encodingKeepsMicrosecondPrecisionAndUuid() {
        var cursor = new Cursor(T, FIRST);

        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not base64 !", "MS4y", "YS5iLmM", "MS4yLjMuNA"})
    void foreignInputIsABadRequest(String forged) {
        assertThatThrownBy(() -> Cursor.decode(forged))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.2.42",
            "1.2.-7",
            "1.2.b62ABC",
            "1.2.1-1-1-1-1",
            "1.2.019a90f1-0000-7000-8000-00000000000g",
            "1.2.019A90F1-0000-7000-8000-000000000003",
            "1.2.019a90f1-0000-7000-8000-000000000003.",
            "1.-1.019a90f1-0000-7000-8000-000000000003",
            "1.1000000000.019a90f1-0000-7000-8000-000000000003",
            "+1.2.019a90f1-0000-7000-8000-000000000003",
            "01.2.019a90f1-0000-7000-8000-000000000003",
            "9223372036854775807.2.019a90f1-0000-7000-8000-000000000003"
    })
    void legacyIdsAndNonCanonicalPositionsAreBadRequests(String raw) {
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> Cursor.decode(forged))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST));
    }

    @Test
    void theExtraRowOnlySignalsAnotherPage() {
        var rows = List.of(new Row(T, FIRST), new Row(T, SECOND), new Row(T, THIRD));

        var page = CursorPages.of(rows, 2, row -> new Cursor(row.occurredAt(), row.id()));

        assertThat(page.records()).containsExactly(new Row(T, FIRST), new Row(T, SECOND));
        assertThat(page.hasMore()).isTrue();
        assertThat(Cursor.decode(page.nextCursor())).isEqualTo(new Cursor(T, SECOND));
    }

    @Test
    void aPageThatFitsIsTheLastOne() {
        var page = CursorPages.of(List.of(new Row(T, THIRD)), 2, row -> new Cursor(row.occurredAt(), row.id()));

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
