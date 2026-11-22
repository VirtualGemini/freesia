package com.freesia.system.iam.internal.password.service;

import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.password.config.PasswordProperties;
import com.freesia.common.error.ApiException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** Keeps third-party password types and the no-downgrade policy inside IAM. */
@Component
@EnableConfigurationProperties(PasswordProperties.class)
public class Passwords {
    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\$([A-Za-z0-9+/]{11,86})\\$([A-Za-z0-9+/]{22,86})");
    private final PasswordProperties configuration;
    private final Semaphore capacity;
    private final Argon2PasswordEncoder encoder;
    private final String dummyHash;

    public Passwords(PasswordProperties configuration) {
        this.configuration = configuration;
        capacity = new Semaphore(configuration.maxConcurrent());
        encoder = encoder(configuration.memoryKib(), configuration.iterations(), configuration.parallelism());
        byte[] unknown = new byte[32];
        new SecureRandom().nextBytes(unknown);
        dummyHash = encoder.encode(Base64.getEncoder().encodeToString(unknown));
        java.util.Arrays.fill(unknown, (byte) 0);
    }

    public String hash(CharSequence password) {
        acquire();
        try { return encoder.encode(password); }
        finally { capacity.release(); }
    }

    /** Null means the password does not match. A changed value is a non-decreasing rehash. */
    public String verifyAndUpgrade(CharSequence password, String stored) {
        acquire();
        try {
            var parsed = stored == null ? null : PHC.matcher(stored);
            boolean usable = parsed != null && parsed.matches();
            int memory = usable ? Integer.parseInt(parsed.group(1)) : 0;
            int iterations = usable ? Integer.parseInt(parsed.group(2)) : 0;
            int lanes = usable ? Integer.parseInt(parsed.group(3)) : 0;
            usable = usable && memory >= 8 * lanes && memory <= 1048576
                    && iterations >= 1 && iterations <= 20 && lanes >= 1 && lanes <= 16;
            boolean matched = encoder.matches(password, usable ? stored : dummyHash);
            if (!usable || !matched) return null;
            int targetMemory = Math.max(memory, configuration.memoryKib());
            int targetIterations = Math.max(iterations, configuration.iterations());
            int targetLanes = Math.max(lanes, configuration.parallelism());
            return targetMemory == memory && targetIterations == iterations && targetLanes == lanes
                    ? stored : encoder(targetMemory, targetIterations, targetLanes).encode(password);
        } finally { capacity.release(); }
    }

    private void acquire() {
        if (!capacity.tryAcquire()) throw new ApiException(IamErrorCode.PASSWORD_CAPACITY);
    }
    private static Argon2PasswordEncoder encoder(int memory, int iterations, int lanes) {
        return new Argon2PasswordEncoder(16, 32, lanes, memory, iterations);
    }
}
