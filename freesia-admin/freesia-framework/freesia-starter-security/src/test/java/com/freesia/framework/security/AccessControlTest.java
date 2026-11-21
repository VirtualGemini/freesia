package com.freesia.framework.security;

import cn.dev33.satoken.stp.StpUtil;
import com.freesia.common.result.R;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AccessControlTest {
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.6-alpine").withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    /** Permissions per account; mutable so a test can revoke a grant while the session stays alive. */
    static final Map<UUID, Set<String>> GRANTS = new ConcurrentHashMap<>();

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

        String signIn(UUID accountId) throws Exception {
            return signInAt("/sign-in/" + accountId);
        }

        String signInAt(String path) throws Exception {
            var response = call("POST", path, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).matches("\\{\"code\":200,\"msg\":\"OK\",\"data\":\"[^\"]+\"}");
            return response.body().replaceAll(".*\"data\":\"([^\"]+)\".*", "$1");
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
        void authorizationErrorsAreLocalizedWithoutChangingTheirStatusOrCode() throws Exception {
            var unauthorized = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/me"))
                    .header("Accept-Language", "en-US").build(), HttpResponse.BodyHandlers.ofString());
            String token = signIn(UUID.fromString("019aa220-6c88-7000-8000-000000000031"));
            var forbidden = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/roles"))
                    .header("Accept-Language", "en").header("Authorization", token).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(unauthorized.statusCode()).isEqualTo(401);
            assertThat(unauthorized.body()).isEqualTo("{\"code\":401,\"msg\":\"Not signed in or the session has expired\",\"data\":null}");
            assertThat(forbidden.statusCode()).isEqualTo(403);
            assertThat(forbidden.body()).isEqualTo("{\"code\":403,\"msg\":\"Access denied\",\"data\":null}");
            assertThat(forbidden.headers().firstValue("X-Trace-Id")).isPresent();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/me", "/roles", "/audit/logs"})
        void protectedEndpointsRejectMissingAndUnknownTokens(String path) throws Exception {
            var missing = call("GET", path, null);
            var unknown = call("GET", path, "not-a-session");

            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(missing.body()).contains("\"code\":401", "\"data\":null");
            assertThat(unknown.statusCode()).isEqualTo(401);
            assertThat(unknown.body()).contains("\"code\":401", "\"data\":null");
        }

        @Test
        void aUuidSessionIsEnoughForAuthenticatedEndpointsButNotForPermissions() throws Exception {
            UUID accountId = UUID.fromString("019aa220-6c88-7000-8000-000000000011");
            GRANTS.put(accountId, Set.of("menu:read"));
            String token = signIn(accountId);

            assertThat(call("GET", "/me", token).body()).isEqualTo(
                    "{\"code\":200,\"msg\":\"OK\",\"data\":\"019aa220-6c88-7000-8000-000000000011\"}");
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(403);
        }

        @ParameterizedTest
        @ValueSource(strings = {"11", "not-a-uuid", "1-1-1-1-1"})
        void invalidSessionIdentitiesAreUnauthorized(String identity) throws Exception {
            String token = signInAt("/sign-in-invalid/" + identity);

            var authenticated = call("GET", "/me", token);
            var permission = call("GET", "/roles", token);
            var signOut = call("POST", "/sign-out", token);
            assertThat(authenticated.statusCode()).isEqualTo(401);
            assertThat(authenticated.body()).contains("\"code\":401", "\"data\":null");
            assertThat(permission.statusCode()).isEqualTo(401);
            assertThat(permission.body()).contains("\"code\":401", "\"data\":null");
            assertThat(signOut.statusCode()).isEqualTo(401);
        }

        @Test
        void revokingAGrantTakesEffectOnTheNextRequestOfALiveSession() throws Exception {
            UUID accountId = UUID.fromString("019aa220-6c88-7000-8000-000000000012");
            GRANTS.put(accountId, Set.of("role:read"));
            String token = signIn(accountId);
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(200);

            GRANTS.put(accountId, Set.of());

            var denied = call("GET", "/roles", token);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("\"code\":403", "\"data\":null");
            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(200);
        }

        @Test
        void methodLevelPermissionReplacesTheClassLevelOne() throws Exception {
            UUID accountId = UUID.fromString("019aa220-6c88-7000-8000-000000000013");
            GRANTS.put(accountId, Set.of("audit:read"));
            String token = signIn(accountId);

            assertThat(call("GET", "/audit/logs", token).statusCode()).isEqualTo(200);
            assertThat(call("POST", "/audit/purge", token).statusCode()).isEqualTo(403);
        }

        @Test
        void signingOutEndsTheSessionStoredInValkey() throws Exception {
            UUID accountId = UUID.fromString("019aa220-6c88-7000-8000-000000000014");
            GRANTS.put(accountId, Set.of("role:read"));
            String token = signIn(accountId);
            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(200);
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(200);

            assertThat(call("POST", "/sign-out", token).statusCode()).isEqualTo(200);

            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(401);
            assertThat(call("GET", "/roles", token).statusCode()).isEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"sa-token.token-name=Authorization", "sa-token.is-read-cookie=false", "sa-token.is-log=false"})
    class WithoutAnAuthorizationSource extends Scenario {
        @Test
        void everyPermissionCheckIsDeniedWhileAuthenticationStillWorks() throws Exception {
            UUID accountId = UUID.fromString("019aa220-6c88-7000-8000-000000000021");
            GRANTS.put(accountId, Set.of("role:read", "audit:read", "audit:purge"));
            String token = signIn(accountId);

            assertThat(call("GET", "/me", token).statusCode()).isEqualTo(200);
            var denied = call("GET", "/roles", token);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("\"code\":403", "\"data\":null");
            assertThat(call("GET", "/audit/logs", token).statusCode()).isEqualTo(403);
            assertThat(call("POST", "/audit/purge", token).statusCode()).isEqualTo(403);
        }
    }

    @SpringBootApplication
    @RestController
    static class TestApplication {
        @Bean
        @ConditionalOnProperty("test.authorization-source")
        AuthorizationSource authorizationSource() {
            return (UUID accountId) -> GRANTS.getOrDefault(accountId, Set.of());
        }

        @GetMapping("/public")
        R<Void> open() {
            return R.ok();
        }

        @PostMapping("/sign-in/{accountId}")
        R<String> signIn(@PathVariable UUID accountId) {
            StpUtil.login(accountId);
            return R.ok(StpUtil.getTokenValue());
        }

        @PostMapping("/sign-in-invalid/{identity}")
        R<String> signInInvalid(@PathVariable String identity) {
            StpUtil.login(identity);
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
        R<UUID> me() {
            return R.ok(UUID.fromString(StpUtil.getLoginIdAsString()));
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
