package com.freesia.framework.data.cursor;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Position in a list ordered by {@code (time DESC, id DESC)}. Query the next page with
 * {@code WHERE (occurred_at, id) < (:time, :id) ORDER BY occurred_at DESC, id DESC LIMIT :limit + 1}.
 * The encoded form is opaque to clients; it is a position, not a secret, and grants no access.
 */
public record Cursor(Instant time, long id) {
    public String encode() {
        String raw = time.getEpochSecond() + "." + time.getNano() + "." + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws ApiException with {@code BAD_REQUEST} for anything this class did not produce */
    public static Cursor decode(String encoded) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("\\.");
            if (parts.length == 3) {
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Integer.parseInt(parts[1])), Long.parseLong(parts[2]));
            }
        } catch (RuntimeException invalid) {
            throw new ApiException(invalid, CommonErrorCode.BAD_REQUEST);
        }
        throw new ApiException(CommonErrorCode.BAD_REQUEST);
    }
}
