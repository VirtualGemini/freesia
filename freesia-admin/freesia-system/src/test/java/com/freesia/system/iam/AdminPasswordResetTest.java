package com.freesia.system.iam;

import com.freesia.system.SystemTestApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import com.freesia.common.audit.OperationAudit;
import com.freesia.common.audit.LoginAudit;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S1: direct reset, real independent devices, races, and conservative failure compensation through public HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(AdminPasswordResetTest.Faults.class)
class AdminPasswordResetTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("admin_password_reset").withUsername("freesia").withPassword("test-only");
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.6-alpine").withExposedPorts(6379);
    static { POSTGRES.start(); VALKEY.start(); }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @LocalServerPort int port;
    @TempDir static Path temporary;
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    String owner;

    @TestConfiguration
    static class Faults {
        static volatile String failedTarget;
        static volatile String heldLogin;
        static volatile String heldTarget;
        static volatile java.util.concurrent.CountDownLatch entered;
        static volatile java.util.concurrent.CountDownLatch release;
        @EventListener void operation(OperationAudit event) {
            if (event.success() && event.targetId().equals(failedTarget)) throw new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE);
            if (event.success() && event.targetId().equals(heldTarget)) hold();
        }
        @EventListener void login(LoginAudit event) {
            if (!event.success() || !event.username().equals(heldLogin)) return;
            hold();
        }
        void hold() {
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
            }
        }
    }

    @BeforeAll
    void initialize() throws Exception {
        Path secret = temporary.resolve("bootstrap-secret");
        Files.writeString(secret, "Reset-password-owner!");
        Path output = temporary.resolve("bootstrap.log");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(), "bootstrap-admin", "--username=recovery-owner",
                "--password-file=" + secret, "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(), "--spring.data.redis.port=" + VALKEY.getMappedPort(6379));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        owner = data(login("recovery-owner", "Reset-password-owner!")).path("token").asString();
    }

    @Test
    void directResetChangesTheNormalPasswordAndRevokesEveryOldDeviceButNotOtherAccounts() throws Exception {
        String id = account("reset-devices", null, List.of());
        var first = login("reset-devices", "original-password");
        var second = login("reset-devices", "original-password");
        account("reset-unrelated", null, List.of());
        var unrelated = login("reset-unrelated", "original-password");
        String secret = "  administrator-chosen password  ";
        var response = call("PUT", "/api/account/" + id + "/security/password", owner,
                json.writeValueAsString(Map.of("newPassword", secret)));
        var result = data(response);
        assertThat(result.asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain(secret, "temporaryPassword", "expiresAt");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(login("reset-devices", "original-password").statusCode()).isEqualTo(400);
        for (var device : List.of(first, second)) assertInvalid(device);
        assertThat(call("GET", "/api/account/info", token(unrelated), null).statusCode()).isEqualTo(200);
        var detail = call("GET", "/api/account/" + id + "/detail-card", owner, null);
        var audit = call("GET", "/api/log/operation/observations?targetId=" + id, owner, null);
        assertThat(detail.body()).doesNotContain(secret);
        assertThat(audit.body()).contains("system:account:reset-password").doesNotContain(secret, "original-password");
        assertThat(login("reset-devices", secret.trim()).statusCode()).isEqualTo(400);
        var normal = login("reset-devices", secret);
        assertThat(data(normal).has("token")).isTrue();
        assertThat(call("GET", "/api/account/info", token(normal), null).statusCode()).isEqualTo(200);
        assertThat(normal.headers().firstValue("Set-Cookie")).isPresent();
    }

    @Test
    void passwordRulesPreserveVerbatimInputAndRejectOnlyMissingOrBlankValuesWithoutRevoking() throws Exception {
        String id = account("reset-validation", null, List.of());
        var device = login("reset-validation", "original-password");
        for (String body : List.of("{}", "{\"newPassword\":null}",
                json.writeValueAsString(Map.of("newPassword", "")),
                json.writeValueAsString(Map.of("newPassword", " \t ")))) {
            assertThat(call("PUT", "/api/account/" + id + "/security/password", owner, body).statusCode()).isEqualTo(400);
        }
        assertThat(call("PUT", "/api/account/" + id + "/security/password", owner, null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/account/info", token(device), null).statusCode()).isEqualTo(200);
        assertThat(reset(owner, id, "x").statusCode()).isEqualTo(200);
        assertThat(data(login("reset-validation", "x")).has("token")).isTrue();
        String longPassword = "  " + "a".repeat(80) + "  ";
        assertThat(reset(owner, id, longPassword).statusCode()).isEqualTo(200);
        assertThat(login("reset-validation", longPassword.trim()).statusCode()).isEqualTo(400);
        assertThat(data(login("reset-validation", longPassword)).has("token")).isTrue();
    }

    @Test
    void resetUsesItsOwnRangeRatherThanQueryScopeOrTargetGrantContainmentAndProtectsOwner() throws Exception {
        String local = organization("recovery-local");
        String outside = organization("recovery-outside");
        String reset = option("system:account:reset-password");
        String localRole = role("RECOVERY_LOCAL", "ORG", List.of(reset), List.of());
        String queryRole = role("RECOVERY_QUERY", "ALL", List.of(option("system:account:query")), List.of());
        account("reset-local-operator", local, List.of(localRole, queryRole));
        String actor = token(login("reset-local-operator", "original-password"));
        String powerful = role("RECOVERY_POWERFUL", "ALL", List.of(option("system:menu:delete")), List.of(option("system:account:create")));
        String target = account("reset-local-target", local, List.of(powerful));
        String denied = account("reset-outside-target", outside, List.of());
        assertThat(reset(actor, target).statusCode()).isEqualTo(200);
        assertThat(reset(actor, denied).statusCode()).isEqualTo(403);
        assertThat(login("reset-outside-target", "original-password").statusCode()).isEqualTo(200);
        String noQuery = role("RECOVERY_NO_QUERY", "ORG", List.of(reset), List.of());
        account("reset-no-query", local, List.of(noQuery));
        String noQueryActor = token(login("reset-no-query", "original-password"));
        assertThat(call("GET", "/api/account/list", noQueryActor, null).statusCode()).isEqualTo(403);
        assertThat(reset(noQueryActor, target).statusCode()).isEqualTo(200);
        String onlyGrant = role("RECOVERY_ONLY_G", "ALL", List.of(), List.of(reset));
        account("reset-only-g", null, List.of(onlyGrant));
        assertThat(reset(token(login("reset-only-g", "original-password")), target).statusCode()).isEqualTo(403);
        String ownerId = data(call("GET", "/api/account/info", owner, null)).path("accountId").asString();
        assertThat(reset(owner, ownerId).statusCode()).isEqualTo(403);
        String ordinaryAll = role("RECOVERY_ALL", "ALL", List.of(reset), List.of());
        account("reset-all", null, List.of(ordinaryAll));
        assertThat(reset(token(login("reset-all", "original-password")), ownerId).statusCode()).isEqualTo(403);
        assertThat(call("PUT", "/api/account/" + denied + "/status", owner, "{\"status\":\"2\"}").statusCode()).isEqualTo(200);
        assertThat(reset(owner, denied).statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/account/" + denied + "/status", owner, "{\"status\":\"3\"}").statusCode()).isEqualTo(200);
        assertThat(reset(owner, denied).statusCode()).isEqualTo(200);
        assertThat(data(login("reset-outside-target", "replacement-password")).has("token")).isTrue();
        assertThat(call("PUT", "/api/account/" + denied + "/status", owner, "{\"status\":\"2\"}").statusCode()).isEqualTo(200);
        String confirmation = data(call("GET", "/api/account/deletion-preview?ids=" + denied, owner, null))
                .path("confirmationToken").asString();
        assertThat(call("DELETE", "/api/account/" + denied, owner,
                json.writeValueAsString(Map.of("confirmationToken", confirmation))).statusCode()).isEqualTo(200);
        assertThat(reset(owner, denied).statusCode()).isEqualTo(404);
        String selfId = data(call("GET", "/api/account/info", noQueryActor, null)).path("accountId").asString();
        assertThat(reset(noQueryActor, selfId).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/info", noQueryActor, null).statusCode()).isEqualTo(401);
        assertThat(data(login("reset-no-query", "replacement-password")).has("token")).isTrue();
    }

    @Test
    void repeatedResetRevokesThePreviousReplacementAndConcurrentResetsCommitInLockOrder() throws Exception {
        String id = account("reset-repeated", null, List.of());
        assertThat(reset(owner, id, "first-password").statusCode()).isEqualTo(200);
        var first = login("reset-repeated", "first-password");
        assertThat(reset(owner, id, "second-password").statusCode()).isEqualTo(200);
        assertInvalid(first);
        assertThat(login("reset-repeated", "first-password").statusCode()).isEqualTo(400);
        var second = login("reset-repeated", "second-password");
        Faults.heldTarget = id;
        Faults.entered = new java.util.concurrent.CountDownLatch(1);
        Faults.release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> reset(owner, id, "concurrent-a"));
            assertThat(Faults.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var b = executor.submit(() -> reset(owner, id, "concurrent-b"));
            awaitLock("SELECT purpose FROM freesia.sys_bootstrap%FOR UPDATE%", 1);
            Faults.heldTarget = null;
            Faults.release.countDown();
            assertThat(a.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(b.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally { Faults.release.countDown(); Faults.heldTarget = null; }
        assertInvalid(second);
        assertThat(login("reset-repeated", "concurrent-a").statusCode()).isEqualTo(400);
        assertThat(data(login("reset-repeated", "concurrent-b")).has("token")).isTrue();
    }

    @Test
    void auditRollbackPreservesOriginalPasswordButNeverRestoresRevokedSessionsAndExplicitRetryWorks() throws Exception {
        String id = account("reset-rollback", null, List.of());
        var device = login("reset-rollback", "original-password");
        Faults.failedTarget = id;
        try { assertThat(reset(owner, id).statusCode()).isEqualTo(503); }
        finally { Faults.failedTarget = null; }
        assertInvalid(device);
        assertThat(login("reset-rollback", "replacement-password").statusCode()).isEqualTo(400);
        var recovered = login("reset-rollback", "original-password");
        assertThat(data(recovered).has("token")).isTrue();
        assertThat(reset(owner, id).statusCode()).isEqualTo(200);
        assertInvalid(recovered);
        assertThat(login("reset-rollback", "original-password").statusCode()).isEqualTo(400);
        assertThat(data(login("reset-rollback", "replacement-password")).has("token")).isTrue();
        var audit = data(call("GET", "/api/log/operation/observations?targetId=" + id, owner, null));
        var resets = new java.util.ArrayList<JsonNode>();
        audit.forEach(record -> { if (record.path("action").asString().equals("system:account:reset-password")) resets.add(record); });
        assertThat(resets).hasSize(2);
        assertThat(resets.get(0).path("success").asBoolean()).isTrue();
        assertThat(resets.get(1).path("success").asBoolean()).isFalse();
        assertThat(audit.toString()).doesNotContain("replacement-password", "original-password", "newPassword");
    }

    @Test
    void resetDuringLoginAuditCannotActivateAnOldPasswordEvenAfterItWasVerified() throws Exception {
        String id = account("reset-audit-race", null, List.of());
        heldLoginReset("reset-audit-race", "original-password", id);
        assertThat(data(login("reset-audit-race", "replacement-password")).has("token")).isTrue();
    }

    void heldLoginReset(String username, String password, String id) throws Exception {
        Faults.heldLogin = username;
        Faults.entered = new java.util.concurrent.CountDownLatch(1);
        Faults.release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> login(username, password));
            assertThat(Faults.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(reset(owner, id).statusCode()).isEqualTo(200);
            Faults.release.countDown();
            assertThat(pending.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(400);
        } finally {
            Faults.release.countDown(); Faults.heldLogin = null;
        }
    }

    @Test
    void valkeyRevocationTimeoutRollsBackPasswordAndExplicitRetryNeverRestoresOldSessions() throws Exception {
        String id = account("reset-valkey-failure", null, List.of());
        var device = login("reset-valkey-failure", "original-password");
        Faults.heldTarget = id;
        Faults.entered = new java.util.concurrent.CountDownLatch(1);
        Faults.release = new java.util.concurrent.CountDownLatch(1);
        boolean paused = false;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var earlier = executor.submit(() -> call("PUT", "/api/account/" + id, owner,
                    "{\"username\":\"reset-valkey-failure\",\"remark\":\"controlled barrier\"}"));
            assertThat(Faults.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var resetting = executor.submit(() -> reset(owner, id));
            awaitLock("SELECT purpose FROM freesia.sys_bootstrap%FOR UPDATE%", 1);
            VALKEY.getDockerClient().pauseContainerCmd(VALKEY.getContainerId()).exec();
            paused = true;
            Faults.release.countDown();
            assertThat(earlier.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(resetting.get(20, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        } finally {
            Faults.release.countDown(); Faults.heldTarget = null;
            if (paused) VALKEY.getDockerClient().unpauseContainerCmd(VALKEY.getContainerId()).exec();
        }
        assertThat(data(login("reset-valkey-failure", "original-password")).has("token")).isTrue();
        assertThat(reset(owner, id).statusCode()).isEqualTo(200);
        assertInvalid(device);
        assertThat(login("reset-valkey-failure", "original-password").statusCode()).isEqualTo(400);
    }

    @Test
    void refreshQueuedOnTheAccountLockBeforeResetCannotSurviveItsCommittedGeneration() throws Exception {
        String id = account("reset-refresh-race", null, List.of());
        var device = login("reset-refresh-race", "original-password");
        String cookie = HttpCookie.parse(device.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getValue();
        try (var connection = POSTGRES.createConnection("");
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM freesia.sys_account WHERE id=?::uuid FOR UPDATE")) {
                statement.setString(1, id); statement.executeQuery().close();
            }
            var refreshing = executor.submit(() -> send("POST", "/api/auth/refresh", null, cookie, null));
            awaitLock("SELECT enabled,deleted,session_version,password_hash%FOR UPDATE%", 1);
            var resetting = executor.submit(() -> reset(owner, id));
            awaitLock("SELECT enabled,deleted,session_version,password_hash%FOR UPDATE%", 2);
            connection.commit();
            var rotated = refreshing.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
            assertThat(resetting.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertInvalid(rotated);
            assertInvalid(device);
        }
    }

    void awaitLock(String pattern, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (var connection = POSTGRES.createConnection(""); var statement = connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE ?")) {
            statement.setString(1, pattern);
            while (System.nanoTime() < deadline) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= count) return;
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Request did not reach infrastructure lock: " + pattern);
    }

    String option(String permission) throws Exception {
        for (var node : data(call("GET", "/api/role/permission-options", owner, null))) {
            if (node.path("authMark").asString().equals(permission)) return node.path("id").asString();
        }
        throw new AssertionError("Missing permission " + permission);
    }
    String role(String code, String scope, List<String> uses, List<String> grants) throws Exception {
        return data(call("POST", "/api/role", owner, json.writeValueAsString(Map.of("roleCode", code, "roleName", code,
                "description", "", "enabled", true, "dataScope", scope, "customOrganizationIds", List.of(),
                "menuIds", uses, "grantMenuIds", grants)))).asString();
    }
    String organization(String code) throws Exception {
        String root = data(call("GET", "/api/organization/tree", owner, null)).get(0).path("id").asString();
        return data(call("POST", "/api/organization", owner, json.writeValueAsString(Map.of(
                "parentId", root, "code", code, "name", code, "sort", 0)))).asString();
    }

    String account(String username, String organization, List<String> roles) throws Exception {
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "username", username, "password", "original-password", "roleIds", roles));
        input.put("organizationId", organization);
        return data(call("POST", "/api/account", owner, json.writeValueAsString(input))).asString();
    }
    HttpResponse<String> reset(String actor, String id) throws Exception {
        return reset(actor, id, "replacement-password");
    }
    HttpResponse<String> reset(String actor, String id, String password) throws Exception {
        return call("PUT", "/api/account/" + id + "/security/password", actor, json.writeValueAsString(Map.of("newPassword", password)));
    }
    HttpResponse<String> login(String username, String password) throws Exception {
        return call("POST", "/api/auth/login", null, json.writeValueAsString(Map.of("userName", username, "password", password)));
    }
    String token(HttpResponse<String> response) { return data(response).path("token").asString(); }
    JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    void assertInvalid(HttpResponse<String> session) throws Exception {
        assertThat(call("GET", "/api/account/info", token(session), null).statusCode()).isEqualTo(401);
        String cookie = HttpCookie.parse(session.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getValue();
        var refresh = send("POST", "/api/auth/refresh", null, cookie, null);
        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(401);
        assertThat(json.readTree(refresh.body()).path("code").asInt()).isEqualTo(10003);
    }
    HttpResponse<String> call(String method, String path, String access, String body) throws Exception {
        return send(method, path, access, null, body);
    }
    HttpResponse<String> send(String method, String path, String access, String refresh, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Accept-Language", "en").header("X-Freesia-CSRF", "1")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (access != null) request.header("Authorization", access);
        if (refresh != null) request.header("Cookie", "freesia_refresh=" + refresh);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
