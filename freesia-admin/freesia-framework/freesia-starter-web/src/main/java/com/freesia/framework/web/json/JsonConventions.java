package com.freesia.framework.web.json;

import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON rules of the HTTP contract.
 * <ul>
 *   <li>UUID identifiers use JSON strings; boxed and primitive counts stay numeric.</li>
 *   <li>Instants are ISO-8601 in UTC; clients choose the display time zone.</li>
 * </ul>
 */
public final class JsonConventions {
    private JsonConventions() {}

    public static void apply(JsonMapper.Builder builder) {
        builder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
