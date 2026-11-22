package com.freesia.system.iam.internal.availability.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Startup hard dependency; Boot's Redis readiness indicator checks runtime Valkey connectivity. */
@Component
class ValkeyAvailability implements InitializingBean {
    private final StringRedisTemplate valkey;
    ValkeyAvailability(StringRedisTemplate valkey) { this.valkey = valkey; }
    @Override public void afterPropertiesSet() {
        try { ping(); }
        catch (RuntimeException failure) { throw new IllegalStateException("Valkey is required at startup", failure); }
    }
    private void ping() {
        String response = valkey.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
        if (!"PONG".equals(response)) throw new IllegalStateException("Valkey ping failed");
    }
}
