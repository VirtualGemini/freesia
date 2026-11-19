package com.freesia.framework.web.json;

import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * JSON rules of the HTTP contract.
 * <ul>
 *   <li>{@code Long} is written as a string: identifiers exceed JavaScript's safe integer range, and a
 *       field must not switch between number and string depending on its value. Use {@code long} or
 *       {@code int} for counts, which stay numeric.</li>
 *   <li>Instants are ISO-8601 in UTC; clients choose the display time zone.</li>
 * </ul>
 */
public final class JsonConventions {
    private JsonConventions() {}

    public static void apply(JsonMapper.Builder builder) {
        builder.addModule(new SimpleModule("freesia-json").addSerializer(Long.class, ToStringSerializer.instance));
        builder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
