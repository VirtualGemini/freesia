package com.freesia.common.audit;

/** Trusted peer-derived context only; never an identity decision or a credential container. */
public record ClientOrigin(String clientIp, String traceId, String userAgent, Location location) {
    public record Location(String countryName, String provinceName, String cityName, String isp, String countryCode) {
        public static Location unknown() { return new Location(null, null, null, null, null); }
    }
}
