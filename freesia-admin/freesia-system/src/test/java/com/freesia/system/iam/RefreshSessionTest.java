package com.freesia.system.iam;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S1: fixtures arrange real infrastructure; all lifecycle outcomes are observed over public HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "freesia.iam.password.memory-kib=8192", "freesia.iam.password.iterations=1",
        "freesia.iam.password.parallelism=1", "freesia.iam.browser-session.cookie-secure=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(RefreshSessionTest.MutationProbe.class)
@ActiveProfiles("test")
class RefreshSessionTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("refresh_sessions").withUsername("freesia").withPassword("refresh-test-only");
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.6-alpine").withExposedPorts(6379);
    static {
        POSTGRES.start();
        VALKEY.start();
    }
    private static final String LOGIN = "{\"userName\":\"refresh-owner\",\"password\":\"refresh-test-secret\"}";
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate valkey;
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @BeforeAll
    void accountFixture() {
        String hash = new Argon2PasswordEncoder(16, 32, 1, 8192, 1).encode("refresh-test-secret");
        UUID id = jdbc.sql("INSERT INTO freesia.sys_account(username,password_hash) VALUES('refresh-owner',:hash) RETURNING id")
                .param("hash", hash).query(UUID.class).single();
        jdbc.sql("INSERT INTO freesia.sys_profile(account_id) VALUES(:id)").param("id", id).update();
        jdbc.sql("INSERT INTO freesia.sys_account_role SELECT :id,id FROM freesia.sys_role WHERE role_code='R_SUPER'")
                .param("id", id).update();
    }

    @Test
    void preLifecycleSessionsWithoutARevocationGenerationCannotAuthenticateOrRefresh() throws Exception {
        var first = login();
        String sid = data(first).path("sessionId").asString();
        // Arrange a persisted pre-lifecycle representation at the existing infrastructure seam.
        valkey.opsForHash().delete("freesia:iam:session:" + sid, "version");
        assertInvalid(call("GET", "/api/account/info", data(first).path("token").asString(), null, null));
        assertInvalid(call("POST", "/api/auth/refresh", null, refresh(first), null));
    }

    @Test
    void loginKeepsRefreshOnlyInAHostOnlyPersistentSecureHttpOnlyStrictCookie() throws Exception {
        Instant before = Instant.now();
        var response = login();
        assertThat(response.statusCode()).isEqualTo(200);
        var data = data(response);
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("token", "sessionId", "accessExpiresAt", "sessionExpiresAt");
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        HttpCookie cookie = HttpCookie.parse(header).getFirst();
        assertThat(cookie.getName()).isEqualTo("freesia_refresh");
        assertThat(cookie.getValue()).hasSize(43).isNotEqualTo(data.path("token").asString());
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(header).contains("SameSite=Strict");
        assertThat(cookie.getMaxAge()).isBetween(1L, 1209600L);
        Instant deadline = Instant.parse(data.path("sessionExpiresAt").asString());
        assertThat(before.plusSeconds(cookie.getMaxAge())).isBeforeOrEqualTo(deadline);
        assertThat(Duration.between(Instant.parse(data.path("accessExpiresAt").asString()), deadline))
                .isEqualTo(Duration.ofDays(14).minusMinutes(30));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(cookie.getValue(), "refreshToken");
        assertThat(call("GET", "/api/account/info", data.path("token").asString(), null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void credentialEndpointsRejectMissingOrHostileCsrfHeadersWithoutCookiesOrCorsAndDoNotCacheFailures() throws Exception {
        for (String endpoint : java.util.List.of("login", "refresh", "logout")) {
            for (var headers : java.util.List.of(
                    java.util.Map.<String, String>of(),
                    java.util.Map.of("X-Freesia-CSRF", "0"),
                    java.util.Map.of("X-Freesia-CSRF", "1", "Sec-Fetch-Site", "cross-site"),
                    java.util.Map.of("X-Freesia-CSRF", "1", "Origin", "null"))) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + endpoint))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(LOGIN));
                headers.forEach(request::header);
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as("%s with %s", endpoint, headers).isEqualTo(403);
                assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(403);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
            var preflight = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + endpoint))
                    .header("Origin", "https://attacker.example").header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-Freesia-CSRF")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        }
    }

    @Test
    void csrfRejectionsNegotiateDefaultWeightedAndRegionalRequestLanguages() throws Exception {
        record Language(String header, String message) {}
        var languages = java.util.List.of(
                new Language(null, "没有访问权限"),
                new Language("en", "Access denied"),
                new Language("zh", "没有访问权限"),
                new Language("en-US", "Access denied"),
                new Language("zh-TW", "没有访问权限"),
                new Language("en;q=0.2,zh-CN;q=0.9", "没有访问权限"),
                new Language("zh;q=0.2,en-US;q=0.9", "Access denied"),
                new Language("fr-FR", "没有访问权限"));
        for (String endpoint : java.util.List.of("login", "refresh", "logout")) {
            for (var language : languages) {
                for (boolean hostile : new boolean[]{false, true}) {
                    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + endpoint))
                            .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.noBody());
                    if (language.header() != null) request.header("Accept-Language", language.header());
                    if (hostile) request.header("X-Freesia-CSRF", "1").header("Sec-Fetch-Site", "cross-site");
                    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(403);
                    assertThat(json.readTree(response.body()).path("msg").asString())
                            .as("%s language %s, hostile=%s", endpoint, language.header(), hostile).isEqualTo(language.message());
                    assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(403);
                    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                    assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
                }
            }
        }
    }

    @Test
    void cookieOnlyRefreshRotatesOnceKeepsSessionAndDeadlineAndReplayCannotEraseTheNewCookie() throws Exception {
        var first = login();
        String oldAccess = data(first).path("token").asString();
        String oldRefresh = refresh(first);
        var rotated = call("POST", "/api/auth/refresh", null, oldRefresh, null);
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(data(rotated).propertyNames()).containsExactlyInAnyOrder("token", "sessionId", "accessExpiresAt", "sessionExpiresAt");
        assertThat(data(rotated).path("sessionId")).isEqualTo(data(first).path("sessionId"));
        assertThat(data(rotated).path("sessionExpiresAt")).isEqualTo(data(first).path("sessionExpiresAt"));
        String newAccess = data(rotated).path("token").asString();
        String newRefresh = refresh(rotated);
        assertThat(newAccess).isNotEqualTo(oldAccess);
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        assertThat(rotated.body()).doesNotContain(oldRefresh, newRefresh, "refreshToken");
        assertThat(call("GET", "/api/account/info", oldAccess, null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/info", newAccess, null, null).statusCode()).isEqualTo(200);
        assertInvalid(call("POST", "/api/auth/refresh", null, oldRefresh, null));
        assertInvalid(call("POST", "/api/auth/refresh", oldRefresh, null, "{\"refreshToken\":\"" + oldRefresh + "\"}"));
        assertThat(call("POST", "/api/auth/refresh", null, newRefresh, null).statusCode()).isEqualTo(200);
    }

    @Test
    void logoutUsesTheCurrentCookieAfterReloadOrAfterRotationInvalidatesTheExpiredAccess() throws Exception {
        for (boolean expiredAccess : new boolean[]{false, true}) {
            var first = login();
            String access = data(first).path("token").asString();
            if (expiredAccess) expireAccess(first);
            var rotated = call("POST", "/api/auth/refresh", null, refresh(first), null);
            assertThat(rotated.statusCode()).isEqualTo(200);
            var ended = call("POST", "/api/auth/logout", expiredAccess ? access : null, refresh(rotated), null);
            assertThat(ended.statusCode()).isEqualTo(200);
            HttpCookie removed = HttpCookie.parse(ended.headers().firstValue("Set-Cookie").orElseThrow()).getFirst();
            assertThat(removed.getName()).isEqualTo("freesia_refresh");
            assertThat(removed.getMaxAge()).isZero();
            assertThat(removed.isHttpOnly()).isTrue();
            assertThat(removed.getSecure()).isTrue();
            assertInvalid(call("GET", "/api/account/info", data(rotated).path("token").asString(), null, null));
            assertInvalid(call("POST", "/api/auth/refresh", null, refresh(rotated), null));
        }
    }

    void expireAccess(HttpResponse<String> login) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(data(login).path("token").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        valkey.opsForValue().set("freesia:iam:access:" + digest,
                data(login).path("sessionId").asString() + ":" + Instant.now().minusSeconds(1).getEpochSecond(), Duration.ofDays(14));
    }

    @Test
    void concurrentRefreshHasExactlyOneWinnerAndEvenItsLateResponseCannotReviveALoggedOutSession() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            for (int round = 0; round < 8; round++) {
                var first = login();
                String oldRefresh = refresh(first);
                var start = new java.util.concurrent.CountDownLatch(1);
                var attempts = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
                for (int i = 0; i < 6; i++) attempts.add(executor.submit(() -> {
                    start.await();
                    return call("POST", "/api/auth/refresh", null, oldRefresh, null);
                }));
                start.countDown();
                var responses = new java.util.ArrayList<HttpResponse<String>>();
                for (var attempt : attempts) responses.add(attempt.get(15, java.util.concurrent.TimeUnit.SECONDS));
                var winners = responses.stream().filter(response -> response.statusCode() == 200).toList();
                assertThat(winners).hasSize(1);
                responses.stream().filter(response -> response.statusCode() != 200).forEach(this::assertInvalid);
                var winner = winners.getFirst();
                assertThat(call("GET", "/api/account/info", data(winner).path("token").asString(), null, null).statusCode()).isEqualTo(200);
                // Simulate an in-flight logout carrying the generation that just lost the rotation race.
                assertThat(call("POST", "/api/auth/logout", null, oldRefresh, null).statusCode()).isEqualTo(200);
                assertInvalid(call("GET", "/api/account/info", data(winner).path("token").asString(), null, null));
                assertInvalid(call("POST", "/api/auth/refresh", null, refresh(winner), null));
            }
        }
    }

    @Test
    void logoutRacingRefreshAlwaysLeavesEveryReturnedCredentialRevokedAndOtherDevicesAlone() throws Exception {
        var observer = login();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 12; round++) {
                var first = login();
                String oldRefresh = refresh(first);
                var start = new java.util.concurrent.CountDownLatch(1);
                var rotation = executor.submit(() -> {
                    start.await();
                    return call("POST", "/api/auth/refresh", null, oldRefresh, null);
                });
                var logout = executor.submit(() -> {
                    start.await();
                    return call("POST", "/api/auth/logout", null, oldRefresh, null);
                });
                start.countDown();
                var rotated = rotation.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(logout.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                if (rotated.statusCode() == 200) {
                    assertInvalid(call("GET", "/api/account/info", data(rotated).path("token").asString(), null, null));
                    assertInvalid(call("POST", "/api/auth/refresh", null, refresh(rotated), null));
                } else assertInvalid(rotated);
                assertInvalid(call("GET", "/api/account/info", data(first).path("token").asString(), null, null));
                assertInvalid(call("POST", "/api/auth/refresh", null, oldRefresh, null));
            }
        }
        assertThat(call("GET", "/api/account/info", data(observer).path("token").asString(), null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void credentialEndpointsRejectHostileFetchMetadataWithoutRelyingOnForwardedHost() throws Exception {
        for (String endpoint : java.util.List.of("login", "refresh", "logout")) {
            for (String metadata : java.util.List.of("same-site", "cross-site", "invalid", "same-origin, cross-site")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + endpoint))
                        .header("X-Freesia-CSRF", "1").header("Origin", "https://attacker.example")
                        .header("Sec-Fetch-Site", metadata).header("X-Forwarded-Host", "attacker.example")
                        .header("X-Forwarded-Proto", "https").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(LOGIN)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            }
        }
        // Vite changeOrigin changes the proxy Host, not the browser's same-origin Fetch Metadata.
        var proxied = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("X-Freesia-CSRF", "1").header("Origin", "http://localhost:3000")
                .header("Sec-Fetch-Site", "same-origin").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(LOGIN)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(proxied.statusCode()).isEqualTo(200);
    }

    @Test
    void expiredAccessIsRejectedBeforeMutationAndOnlyAValidAccessCanExecuteIt() throws Exception {
        var observer = login();
        String observerAccess = data(observer).path("token").asString();
        int before = data(call("GET", "/test/refresh/counter", observerAccess, null, null)).asInt();
        var first = login();
        String access = data(first).path("token").asString();
        expireAccess(first);
        var expired = call("POST", "/test/refresh/mutate", access, null, null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(json.readTree(expired.body()).path("code").asInt()).isEqualTo(10002);
        assertThat(data(call("GET", "/test/refresh/counter", observerAccess, null, null)).asInt()).isEqualTo(before);
        assertInvalid(call("POST", "/test/refresh/mutate", null, refresh(first), null));
        assertInvalid(call("POST", "/test/refresh/mutate", refresh(first), null, null));
        var rotated = call("POST", "/api/auth/refresh", null, refresh(first), null);
        String valid = data(rotated).path("token").asString();
        assertThat(call("POST", "/test/refresh/mutate", valid, null, null).statusCode()).isEqualTo(200);
        assertThat(data(call("GET", "/test/refresh/counter", observerAccess, null, null)).asInt()).isEqualTo(before + 1);
        assertThat(call("POST", "/api/auth/logout", null, refresh(rotated), null).statusCode()).isEqualTo(200);
        assertInvalid(call("POST", "/test/refresh/mutate", valid, null, null));
        assertThat(data(call("GET", "/test/refresh/counter", observerAccess, null, null)).asInt()).isEqualTo(before + 1);
    }

    @Test
    void malformedAndAmbiguousRefreshCookiesCannotRotateOrLogoutAnySession() throws Exception {
        var first = login();
        String refresh = refresh(first);
        for (String endpoint : java.util.List.of("refresh", "logout")) {
            for (String cookie : java.util.List.of("freesia_refresh=", "freesia_refresh=invalid",
                    "freesia_refresh=" + refresh + "; freesia_refresh=" + refresh,
                    "freesia_refresh=invalid; freesia_refresh=" + refresh)) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + endpoint))
                        .header("X-Freesia-CSRF", "1").header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertInvalid(response);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            }
            var duplicateHeaders = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + endpoint))
                    .header("X-Freesia-CSRF", "1").header("Cookie", "freesia_refresh=" + refresh)
                    .header("Cookie", "freesia_refresh=" + refresh).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertInvalid(duplicateHeaders);
        }
        assertThat(call("GET", "/api/account/info", data(first).path("token").asString(), null, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/auth/refresh", null, refresh, null).statusCode()).isEqualTo(200);
    }

    @Test
    void expectedSessionPreventsAnUnrelatedCookieFromBeingRotatedRevokedOrCleared() throws Exception {
        var expected = login();
        var unrelated = login();
        String expectedSid = data(expected).path("sessionId").asString();
        String expectedAccess = data(expected).path("token").asString();
        String unrelatedAccess = data(unrelated).path("token").asString();
        String unrelatedRefresh = refresh(unrelated);
        assertInvalid(call("POST", "/api/auth/refresh", expectedAccess, unrelatedRefresh, null, expectedSid));
        assertInvalid(call("POST", "/api/auth/logout", null, unrelatedRefresh, null, expectedSid));
        var ended = call("POST", "/api/auth/logout", expectedAccess, unrelatedRefresh, null, expectedSid);
        assertThat(ended.statusCode()).isEqualTo(200);
        assertThat(ended.headers().allValues("Set-Cookie")).isEmpty();
        assertInvalid(call("GET", "/api/account/info", expectedAccess, null, null));
        assertInvalid(call("POST", "/api/auth/refresh", null, refresh(expected), null));
        assertThat(call("GET", "/api/account/info", unrelatedAccess, null, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/auth/refresh", null, unrelatedRefresh, null).statusCode()).isEqualTo(200);
    }

    @Test
    void databaseAccessPolicyAppliesAtRefreshButNeverExtendsTheOriginalAbsoluteDeadline() throws Exception {
        jdbc.sql("UPDATE freesia.sys_access_control SET access_seconds=2,refresh_seconds=30 WHERE scope_key='global'").update();
        try {
            var first = login();
            Instant originalDeadline = Instant.parse(data(first).path("sessionExpiresAt").asString());
            assertThat(Duration.between(Instant.parse(data(first).path("accessExpiresAt").asString()), originalDeadline))
                    .isEqualTo(Duration.ofSeconds(28));
            jdbc.sql("UPDATE freesia.sys_access_control SET access_seconds=7,refresh_seconds=60 WHERE scope_key='global'").update();
            Instant before = Instant.now();
            var rotated = call("POST", "/api/auth/refresh", null, refresh(first), null);
            assertThat(rotated.statusCode()).isEqualTo(200);
            assertThat(Instant.parse(data(rotated).path("sessionExpiresAt").asString())).isEqualTo(originalDeadline);
            assertThat(Instant.parse(data(rotated).path("accessExpiresAt").asString()))
                    .isBetween(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(7), Instant.now().plusSeconds(7));
            String session = "freesia:iam:session:" + data(first).path("sessionId").asString();
            Instant nearDeadline = Instant.now().plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            valkey.opsForHash().put(session, "deadline", Long.toString(nearDeadline.getEpochSecond()));
            before = Instant.now();
            var capped = call("POST", "/api/auth/refresh", null, refresh(rotated), null);
            assertThat(capped.statusCode()).isEqualTo(200);
            assertThat(Instant.parse(data(capped).path("accessExpiresAt").asString())).isEqualTo(nearDeadline);
            assertThat(Instant.parse(data(capped).path("sessionExpiresAt").asString())).isEqualTo(nearDeadline);
            long maxAge = HttpCookie.parse(capped.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getMaxAge();
            assertThat(before.plusSeconds(maxAge)).isBeforeOrEqualTo(nearDeadline);
            valkey.opsForHash().put(session, "deadline", Long.toString(Instant.now().getEpochSecond()));
            assertInvalid(call("POST", "/api/auth/refresh", null, refresh(capped), null));
            assertInvalid(call("GET", "/api/account/info", data(capped).path("token").asString(), null, null));
            assertInvalid(call("POST", "/api/auth/logout", null, refresh(capped), null));
        } finally {
            jdbc.sql("UPDATE freesia.sys_access_control SET access_seconds=1800,refresh_seconds=1209600 WHERE scope_key='global'").update();
        }
    }

    @Test
    void disabledAndDeletedAccountsCannotRefreshOrResumeEvenIfReenabledLater() throws Exception {
        for (String flag : java.util.List.of("enabled=false", "deleted=true")) {
            var first = login();
            // The two SQL fragments above are fixed test fixtures, not external input.
            jdbc.sql("UPDATE freesia.sys_account SET " + flag + " WHERE username='refresh-owner'").update();
            try {
                assertInvalid(call("POST", "/api/auth/refresh", null, refresh(first), null));
                assertInvalid(call("GET", "/api/account/info", data(first).path("token").asString(), null, null));
            } finally {
                jdbc.sql("UPDATE freesia.sys_account SET enabled=true,deleted=false WHERE username='refresh-owner'").update();
            }
            assertInvalid(call("POST", "/api/auth/refresh", null, refresh(first), null));
            assertInvalid(call("GET", "/api/account/info", data(first).path("token").asString(), null, null));
        }
    }

    @Test
    void cookieLogoutAuditFailureIsRetryableForAtMost24HoursWithoutRestoringCredentials() throws Exception {
        var observer = login();
        var first = login();
        String sid = data(first).path("sessionId").asString();
        String refresh = refresh(first);
        jdbc.sql("ALTER TABLE freesia.sys_login_log ADD CONSTRAINT test_refresh_reject_logout CHECK(event_type<>'LOGOUT') NOT VALID").update();
        try {
            var failed = call("POST", "/api/auth/logout", null, refresh, null, sid);
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.headers().allValues("Set-Cookie")).isEmpty();
            assertInvalid(call("GET", "/api/account/info", data(first).path("token").asString(), null, null));
            assertInvalid(call("POST", "/api/auth/refresh", null, refresh, null));
        } finally {
            jdbc.sql("ALTER TABLE freesia.sys_login_log DROP CONSTRAINT test_refresh_reject_logout").update();
        }
        assertThat(call("POST", "/api/auth/logout", null, refresh, null, sid).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/auth/logout", null, refresh, null, sid).statusCode()).isEqualTo(200);
        var records = data(call("GET", "/api/log/login/list?size=100", data(observer).path("token").asString(), null, null)).path("records");
        assertThat(java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .filter(record -> record.path("sessionId").asString().equals(sid) && record.path("eventType").asString().equals("LOGOUT"))
                .count()).isEqualTo(1);
        // Fast-forward only the isolated retry snapshot, observing expiry through HTTP.
        valkey.delete("freesia:iam:logout:session:" + sid);
        assertInvalid(call("POST", "/api/auth/logout", null, refresh, null, sid));
        assertInvalid(call("POST", "/api/auth/refresh", null, refresh, null));
        assertInvalid(call("GET", "/api/account/info", data(first).path("token").asString(), null, null));
    }

    @Test
    void aTemporaryValkeyFailureIs503WithNoCookieMutationAndRefreshRecoveryKeepsTheSessionUsable() throws Exception {
        var first = login();
        var loggingOut = login();
        String refresh = refresh(first);
        VALKEY.getDockerClient().pauseContainerCmd(VALKEY.getContainerId()).exec();
        try {
            for (String endpoint : java.util.List.of("refresh", "logout")) {
                var response = call("POST", "/api/auth/" + endpoint, null,
                        endpoint.equals("refresh") ? refresh : refresh(loggingOut), null);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(503);
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(response.body()).doesNotContain(refresh);
            }
        } finally {
            VALKEY.getDockerClient().unpauseContainerCmd(VALKEY.getContainerId()).exec();
        }
        assertThat(call("GET", "/api/account/info", data(first).path("token").asString(), null, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/auth/refresh", null, refresh, null).statusCode()).isEqualTo(200);
        // Timed-out writes are uncertain, not rolled back: only an explicit logout retry resolves it.
        assertThat(call("POST", "/api/auth/logout", null, refresh(loggingOut), null).statusCode()).isEqualTo(200);
        assertInvalid(call("GET", "/api/account/info", data(loggingOut).path("token").asString(), null, null));
        assertInvalid(call("POST", "/api/auth/refresh", null, refresh(loggingOut), null));
    }

    @Test
    void pathParametersAndEncodingCannotBypassCredentialCsrfProtection() throws Exception {
        for (String path : java.util.List.of("/api/auth/login;ignored=1", "/api/auth/lo%67in")) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(LOGIN)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("path %s: %s", path, response.body()).isEqualTo(403);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class MutationProbe {
        private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        @RequireAuthenticated
        @PostMapping("/test/refresh/mutate")
        R<Integer> mutate() { return R.ok(counter.incrementAndGet()); }
        @RequireAuthenticated
        @GetMapping("/test/refresh/counter")
        R<Integer> counter() { return R.ok(counter.get()); }
    }

    void assertInvalid(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(10003);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    HttpResponse<String> login() throws Exception {
        return call("POST", "/api/auth/login", null, null, LOGIN);
    }

    JsonNode data(HttpResponse<String> response) {
        return json.readTree(response.body()).path("data");
    }

    String refresh(HttpResponse<String> response) {
        return HttpCookie.parse(response.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getValue();
    }

    HttpResponse<String> call(String method, String path, String access, String refresh, String body) throws Exception {
        return call(method, path, access, refresh, body, null);
    }

    HttpResponse<String> call(String method, String path, String access, String refresh, String body, String expectedSession) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Accept-Language", "en")
                .header("X-Freesia-CSRF", "1")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (access != null) request.header("Authorization", access);
        if (refresh != null) request.header("Cookie", "freesia_refresh=" + refresh);
        if (expectedSession != null) request.header("X-Freesia-Session", expectedSession);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
