package com.freesia.framework.security;

import cn.dev33.satoken.stp.StpUtil;
import com.freesia.common.result.R;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AccessControlTest {
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.6-alpine").withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    /** Permissions per account; mutable so a test can revoke a grant while the session stays alive. */
    static final Map<Long, Set<String>> GRANTS = new ConcurrentHashMap<>();

    abstract static class Scenario {
        @LocalServerPort int port;

        final HttpClient client = HttpClient.newHttpClient();

        @DynamicPropertySource
        static void valkey(DynamicPropertyRegistry registry) {
            registry.add("spring.data.redis.host", VALKEY::getHost);
            registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        }

        HttpResponse<String> call(String method, String path, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            if (token != null) {
                request.header("Authorization", token);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        String signIn(long accountId) throws Exception {
            String body = call("POST", "/sign-in/" + accountId, null).body();
            return body.replaceAll(".*\"data\":\"([^\"]+)\".*", "$1");
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"sa-token.token-name=Authorization", "sa-token.is-read-cookie=false", "sa-token.is-log=false",
                    "test.authorization-source=true"})
    class WithAnAuthorizationSource extends Scenario {
        @Test
        void unannotatedEndpointsStayPublic() throws Exception {
            assertThat(call("GET", "/public", null).statusCode()).isEqualTo(200);
        }

        @Test
        void protectedEndpointsRejectMissingAndUnknownTokens() throws Exception {
            assertThat(call("GET", "/me", null).statusCode()).isEqualTo(401);
            assertThat(call("GET", "/me", "not-a-session").statusCode()).isEqualTo(401);
            assertThat(call("GET", "/roles", null).body()).contains("\"code\":401");
        }

        @Test
        void aSessionIsEnoughForAuthenticatedEndpointsButNotForPermissions() throws Exception {
            GRANTS.put(11L, Set.of("menu:read"));
            String token = signIn(11);

            assertThat(call("GET", "/me", token).body()).isEqualTo("{\"code\":200,\"msg\":\"OK\",\"data\":\"11\"}");
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(403);
        }

        @Test
        void revokingAGrantTakesEffectOnTheNextRequestOfALiveSession() throws Exception {
            GRANTS.put(12L, Set.of("role:read"));
            String token = signIn(12);
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(200);

            GRANTS.put(12L, Set.of());

            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(403);
        }

        @Test
        void methodLevelPermissionReplacesTheClassLevelOne() throws Exception {
            GRANTS.put(13L, Set.of("audit:read"));
            String token = signIn(13);

            assertThat(call("GET", "/audit/logs", token).statusCode()).isEqualTo(200);
            assertThat(call("POST", "/audit/purge", token).statusCode()).isEqualTo(403);
        }

        @Test
        void signingOutEndsTheSessionStoredInValkey() throws Exception {
            String token = signIn(14);
            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(200);

            assertThat(call("POST", "/sign-out", token).statusCode()).isEqualTo(200);

            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"sa-token.token-name=Authorization", "sa-token.is-read-cookie=false", "sa-token.is-log=false"})
    class WithoutAnAuthorizationSource extends Scenario {
        @Test
        void everyPermissionCheckIsDeniedWhileAuthenticationStillWorks() throws Exception {
            GRANTS.put(21L, Set.of("role:read"));
            String token = signIn(21);

            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(200);
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(403);
        }
    }

    @SpringBootApplication
    @RestController
    static class TestApplication {
        @Bean
        @ConditionalOnProperty("test.authorization-source")
        AuthorizationSource authorizationSource() {
            return accountId -> GRANTS.getOrDefault(accountId, Set.of());
        }

        @GetMapping("/public")
        R<Void> open() {
            return R.ok();
        }

        @PostMapping("/sign-in/{accountId}")
        R<String> signIn(@PathVariable long accountId) {
            StpUtil.login(accountId);
            return R.ok(StpUtil.getTokenValue());
        }

        @PostMapping("/sign-out")
        @RequireAuthenticated
        R<Void> signOut() {
            StpUtil.logout();
            return R.ok();
        }

        @GetMapping("/me")
        @RequireAuthenticated
        R<Long> me() {
            return R.ok(StpUtil.getLoginIdAsLong());
        }

        @GetMapping("/roles")
        @RequirePermission("role:read")
        R<Void> roles() {
            return R.ok();
        }
    }

    @RestController
    @RequirePermission("audit:read")
    static class AuditController {
        @GetMapping("/audit/logs")
        R<Void> logs() {
            return R.ok();
        }

        @PostMapping("/audit/purge")
        @RequirePermission("audit:purge")
        R<Void> purge() {
            return R.ok();
        }
    }
}
