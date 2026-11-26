package com.freesia.system.iam;

import com.freesia.system.SystemTestApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import com.freesia.common.audit.OperationAudit;
import com.freesia.common.audit.LoginAudit;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
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

/** S1: real multi-device credentials and lifecycle commands, observed only through public HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.connection-timeout=1000"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(AccountLifecycleTest.AuditFault.class)
class AccountLifecycleTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("account_lifecycle").withUsername("freesia").withPassword("test-only");
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
    static class AuditFault {
        static volatile String failedTarget;
        static volatile String heldTarget;
        static volatile String heldLogin;
        static volatile java.util.concurrent.CountDownLatch entered;
        static volatile java.util.concurrent.CountDownLatch release;
        @EventListener
        void operation(OperationAudit event) {
            if (!event.success()) return;
            if (event.targetId() != null && event.targetId().equals(failedTarget)) {
                throw new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            if (event.targetId() != null && event.targetId().equals(heldTarget)) hold();
        }
        @EventListener
        void login(LoginAudit event) {
            if (event.success() && event.username().equals(heldLogin)) hold();
        }
        private void hold() {
            if (entered == null) return;
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Audit barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    @BeforeAll
    void bootstrapOwner() throws Exception {
        Path secret = temporary.resolve("initial-password");
        Files.writeString(secret, "Lifecycle-owner-test!");
        Path output = temporary.resolve("bootstrap.log");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(), "bootstrap-admin", "--username=lifecycle-owner",
                "--password-file=" + secret, "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(),
                "--spring.data.redis.port=" + VALKEY.getMappedPort(6379), "--spring.main.banner-mode=off");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        owner = access(login("lifecycle-owner", "Lifecycle-owner-test!"));
    }

    @Test
    void abnormalIsASeparateSearchableStatusWithoutChangingLoginAndCancelledIsRejected() throws Exception {
        String target = account("lifecycle-abnormal", null, List.of());
        var session = login("lifecycle-abnormal", "test-only");
        var changed = call("PUT", "/api/account/" + target + "/status", owner, "{\"status\":\"3\"}");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(detail(target).path("account").path("status").asString()).isEqualTo("3");
        assertThat(data(call("GET", "/api/account/list?username=lifecycle-abnormal&status=3", owner, null))
                .path("total").asLong()).isEqualTo(1);
        assertThat(data(call("GET", "/api/account/list?username=lifecycle-abnormal&status=1", owner, null))
                .path("total").asLong()).isZero();
        assertThat(call("GET", "/api/account/info", access(session), null).statusCode()).isEqualTo(200);
        assertThat(login("lifecycle-abnormal", "test-only").statusCode()).isEqualTo(200);
        for (String invalid : List.of("4", "other")) {
            assertThat(call("GET", "/api/account/list?status=" + invalid, owner, null).statusCode()).isEqualTo(400);
            assertThat(call("PUT", "/api/account/" + target + "/status", owner,
                    "{\"status\":\"" + invalid + "\"}").statusCode()).isEqualTo(400);
        }
        assertThat(status(owner, target, false).statusCode()).isEqualTo(200);
        assertThat(detail(target).path("account").path("status").asString()).isEqualTo("2");
        assertInvalid(session);
    }

    @Test
    void disablingRevokesAllDevicesAndReenablingNeverResurrectsOldAccessOrRefresh() throws Exception {
        String target = account("lifecycle-multidevice", null, List.of());
        var first = login("lifecycle-multidevice", "test-only");
        var second = login("lifecycle-multidevice", "test-only");
        String otherId = account("lifecycle-unrelated", null, List.of());
        var other = login("lifecycle-unrelated", "test-only");
        var rotated = refresh(first);
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/info", access(first), null).statusCode()).isEqualTo(200);

        var disabled = status(owner, target, false);
        assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
        assertThat(data(disabled).asBoolean()).isTrue();
        assertThat(detail(target).path("account").path("status").asString()).isEqualTo("2");
        for (var session : List.of(first, second, rotated)) assertInvalid(session);
        assertThat(login("lifecycle-multidevice", "test-only").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/account/info", access(other), null).statusCode()).isEqualTo(200);
        assertThat(detail(otherId).path("account").path("status").asString()).isEqualTo("1");

        assertThat(status(owner, target, true).statusCode()).isEqualTo(200);
        for (var session : List.of(first, second, rotated)) assertInvalid(session);
        var fresh = login("lifecycle-multidevice", "test-only");
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/info", access(fresh), null).statusCode()).isEqualTo(200);
        assertThat(status(owner, target, true).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/info", access(fresh), null).statusCode()).isEqualTo(200);
        var audit = data(call("GET", "/api/log/operation/observations?targetId=" + target, owner, null));
        assertThat(audit.toString()).contains("system:account:disable", "system:account:enable")
                .doesNotContain(access(first), cookie(first), "test-only");
    }

    @Test
    void deletionRequiresDisabledTargetsAndFreshConfirmationAndRemovesBindingsAtomically() throws Exception {
        String first = account("lifecycle-delete-a", null, List.of());
        String second = account("lifecycle-delete-b", null, List.of());
        assertThat(delete(List.of(first), "A".repeat(43)).statusCode()).isEqualTo(409);
        assertThat(status(owner, first, false).statusCode()).isEqualTo(200);
        var rejected = call("GET", "/api/account/deletion-preview?ids=" + first + "&ids=" + second, owner, null);
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("lifecycle-delete-b").doesNotContain("lifecycle-delete-a");
        assertThat(data(rejected).path("blockedAccounts").size()).isEqualTo(1);
        assertThat(data(rejected).path("confirmationToken").isNull()).isTrue();
        assertThat(detail(first).path("account").path("status").asString()).isEqualTo("2");
        assertThat(status(owner, second, false).statusCode()).isEqualTo(200);
        var preview = deletionPreview(List.of(first, second));
        assertThat(preview.path("accounts").size()).isEqualTo(2);
        assertThat(preview.toString()).contains("lifecycle-delete-a", "lifecycle-delete-b")
                .doesNotContain("password", "sessionVersion");
        String confirmation = preview.path("confirmationToken").asString();
        assertThat(status(owner, second, true).statusCode()).isEqualTo(200);
        assertThat(delete(List.of(first, second), confirmation).statusCode()).isEqualTo(409);
        assertThat(detail(first).path("account").path("status").asString()).isEqualTo("2");
        assertThat(status(owner, second, false).statusCode()).isEqualTo(200);
        assertThat(delete(List.of(first, second), confirmation).statusCode()).isEqualTo(409);
        var fresh = deletionPreview(List.of(first, second));
        assertThat(data(delete(List.of(first, second), fresh.path("confirmationToken").asString())).asBoolean()).isTrue();
        for (String id : List.of(first, second)) {
            assertThat(call("GET", "/api/account/" + id + "/detail-card", owner, null).statusCode()).isEqualTo(404);
            var audit = data(call("GET", "/api/log/operation/observations?targetId=" + id, owner, null));
            assertThat(audit.get(0).path("action").asString()).isEqualTo("system:account:delete");
            assertThat(audit.get(0).path("success").asBoolean()).isTrue();
        }
        assertThat(data(call("GET", "/api/account/list?username=lifecycle-delete", owner, null)).path("total").asLong()).isZero();
        assertThat(status(owner, first, true).statusCode()).isEqualTo(404);
        assertThat(login("lifecycle-delete-a", "test-only").statusCode()).isEqualTo(400);
    }

    @Test
    void singleDeleteRequiresFreshConfirmationAndRechecksStatusAndSuperAccountProtection() throws Exception {
        String target = account("lifecycle-confirmed-delete", null, List.of());
        assertThat(call("DELETE", "/api/account/" + target, owner, null).statusCode()).isEqualTo(409);
        assertThat(status(owner, target, false).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/api/account/" + target, owner, null).statusCode()).isEqualTo(409);
        assertThat(detail(target).path("account").path("status").asString()).isEqualTo("2");
        String confirmation = deletionPreview(List.of(target)).path("confirmationToken").asString();
        assertThat(call("DELETE", "/api/account/" + target, owner,
                json.writeValueAsString(Map.of("confirmationToken", confirmation))).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/" + target + "/detail-card", owner, null).statusCode()).isEqualTo(404);
        String ownerId = data(call("GET", "/api/account/info", owner, null)).path("accountId").asString();
        var denied = call("DELETE", "/api/account/" + ownerId, owner, null);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()).path("code").asInt()).isEqualTo(10020);
        String role = role("LIFECYCLE_SUPER_REFUSAL", "ALL", List.of(option("system:account:delete")), List.of());
        account("lifecycle-super-refusal", null, List.of(role));
        var ordinaryDenied = call("DELETE", "/api/account/" + ownerId,
                access(login("lifecycle-super-refusal", "test-only")), null);
        assertThat(ordinaryDenied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(ordinaryDenied.body()).path("code").asInt()).isEqualTo(10020);
        var records = data(call("GET", "/api/account/list?username=lifecycle-owner", owner, null)).path("records");
        assertThat(records.get(0).path("protectedAccount").asBoolean()).isTrue();
    }

    @Test
    void operationSpecificRangesAllowSameOrganizationTargetsWithoutGrantContainmentAndRejectTheWholeBatch() throws Exception {
        String local = organization("lifecycle-local", null);
        String outside = organization("lifecycle-outside", null);
        String administration = role("LIFECYCLE_ORG_ADMIN", "ORG", List.of(
                option("system:account:query"), option("system:account:disable"),
                option("system:account:enable"), option("system:account:delete")), List.of());
        String broadTarget = role("LIFECYCLE_BROAD_TARGET", "ALL", List.of(option("system:menu:delete")), List.of(option("system:account:create")));
        account("lifecycle-local-admin", local, List.of(administration));
        String actor = access(login("lifecycle-local-admin", "test-only"));
        String allowed = account("lifecycle-local-target", local, List.of(broadTarget));
        String hidden = account("lifecycle-outside-target", outside, List.of());
        String previewPath = "/api/account/lifecycle-preview?ids=" + allowed + "&action=disable";
        assertThat(data(call("GET", previewPath, actor, null)).path("accounts").get(0).path("status").asString()).isEqualTo("1");
        var outsidePreview = call("GET", "/api/account/lifecycle-preview?ids=" + hidden + "&action=disable", actor, null);
        assertThat(outsidePreview.statusCode()).isEqualTo(403);
        assertThat(outsidePreview.body()).doesNotContain("lifecycle-outside-target");
        assertThat(call("PUT", "/api/account/disable-batch?ids=" + allowed + "&ids=" + hidden, actor, "{}").statusCode()).isEqualTo(403);
        assertThat(detail(allowed).path("account").path("status").asString()).isEqualTo("1");
        var preview = call("GET", "/api/account/deletion-preview?ids=" + allowed + "&ids=" + hidden, actor, null);
        assertThat(preview.statusCode()).isEqualTo(403);
        assertThat(preview.body()).doesNotContain("lifecycle-outside-target", "lifecycle-local-target");
        assertThat(status(actor, allowed, false).statusCode()).isEqualTo(200);
        assertThat(data(call("GET", previewPath, actor, null)).path("accounts").get(0).path("status").asString()).isEqualTo("2");
        assertThat(status(actor, allowed, true).statusCode()).isEqualTo(200);
        // ALL on an unrelated function does not widen disable D.
        String deleteOnly = role("LIFECYCLE_QUERY_ONLY", "ALL", List.of(option("system:account:query")), List.of());
        account("lifecycle-query-admin", local, List.of(deleteOnly));
        assertThat(status(access(login("lifecycle-query-admin", "test-only")), allowed, false).statusCode()).isEqualTo(403);
    }

    @Test
    void ownerMissingOrInvalidTargetsNeverPartiallyDisableAndSelfDisableRequiresFreshLogin() throws Exception {
        String ownerId = data(call("GET", "/api/account/info", owner, null)).path("accountId").asString();
        String ordinary = account("lifecycle-atomic-target", null, List.of());
        for (String invalid : List.of(ownerId, java.util.UUID.randomUUID().toString())) {
            var denied = call("PUT", "/api/account/disable-batch?ids=" + ordinary + "&ids=" + invalid, owner, "{}");
            assertThat(denied.statusCode()).isIn(403, 404);
            assertThat(detail(ordinary).path("account").path("status").asString()).isEqualTo("1");
        }
        assertThat(status(owner, ownerId, false).statusCode()).isEqualTo(403);
        assertThat(status(owner, ownerId, true).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/account/deletion-preview?ids=" + ownerId, owner, null).statusCode()).isEqualTo(403);
        assertThat(call("PUT", "/api/account/disable-batch", owner, "{}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/account/" + ordinary + "/status", owner, "{}").statusCode()).isEqualTo(400);
        assertThat(detail(ordinary).path("account").path("status").asString()).isEqualTo("1");
        String selfRole = role("LIFECYCLE_SELF_DISABLE", "SELF", List.of(option("system:account:disable")), List.of());
        String self = account("lifecycle-self-disable", null, List.of(selfRole));
        var session = login("lifecycle-self-disable", "test-only");
        assertThat(status(access(session), self, false).statusCode()).isEqualTo(200);
        assertThat(status(owner, self, true).statusCode()).isEqualTo(200);
        assertInvalid(session);
    }

    @Test
    void failedSuccessAuditRollsBackEveryStatusButConservativeRevocationIsNeverUndone() throws Exception {
        String first = account("lifecycle-rollback-a", null, List.of());
        String second = account("lifecycle-rollback-b", null, List.of());
        var session = login("lifecycle-rollback-a", "test-only");
        AuditFault.failedTarget = second;
        try {
            var result = call("PUT", "/api/account/disable-batch?ids=" + first + "&ids=" + second, owner, "{}");
            assertThat(result.statusCode()).isEqualTo(503);
        } finally {
            AuditFault.failedTarget = null;
        }
        for (String id : List.of(first, second)) {
            assertThat(detail(id).path("account").path("status").asString()).isEqualTo("1");
            var audits = data(call("GET", "/api/log/operation/observations?targetId=" + id, owner, null));
            assertThat(audits.get(0).path("success").asBoolean()).isFalse();
        }
        assertInvalid(session);
        assertThat(login("lifecycle-rollback-a", "test-only").statusCode()).isEqualTo(200);
        assertThat(call("PUT", "/api/account/disable-batch?ids=" + first + "&ids=" + second, owner, "{}").statusCode()).isEqualTo(200);
    }

    @Test
    void disableDuringLoginAuditRevokesItsInactiveSessionAndPreventsLaterActivation() throws Exception {
        String id = account("lifecycle-login-race", null, List.of());
        AuditFault.heldLogin = "lifecycle-login-race";
        withBarrier(() -> login("lifecycle-login-race", "test-only"), () -> status(owner, id, false), (issued, disabled) -> {
            assertThat(issued.statusCode()).isEqualTo(400);
            assertThat(disabled.statusCode()).isEqualTo(200);
        });
    }

    @Test
    void concurrentValidLoginsDoNotRequireAnotherConnectionWhileHoldingAnAccountLock() throws Exception {
        String id = account("lifecycle-login-pool", null, List.of());
        try (var connection = POSTGRES.createConnection("");
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM freesia.sys_account WHERE id=?::uuid FOR UPDATE")) {
                statement.setString(1, id);
                statement.executeQuery().close();
            }
            var first = executor.submit(() -> login("lifecycle-login-pool", "test-only"));
            var second = executor.submit(() -> login("lifecycle-login-pool", "test-only"));
            awaitAccountLockWait(2);
            connection.commit();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void loginThatVerifiedBeforeDisableIsDeniedAndFailureAuditedAfterItsLockedRecheck() throws Exception {
        String id = account("lifecycle-login-denied", null, List.of());
        try (var connection = POSTGRES.createConnection("");
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM freesia.sys_account WHERE id=?::uuid FOR UPDATE")) {
                statement.setString(1, id);
                statement.executeQuery().close();
            }
            var disabling = executor.submit(() -> status(owner, id, false));
            awaitAccountLockWait(1);
            var signingIn = executor.submit(() -> login("lifecycle-login-denied", "test-only"));
            awaitAccountLockWait(2);
            connection.commit();
            assertThat(disabling.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(signingIn.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(400);
        }
        var records = data(call("GET", "/api/log/login/list?size=100", owner, null)).path("records");
        var failures = new java.util.ArrayList<JsonNode>();
        for (var record : records) {
            if ("lifecycle-login-denied".equals(record.path("username").asString())) failures.add(record);
        }
        assertThat(failures).hasSize(1).allSatisfy(record -> assertThat(record.path("result").asInt()).isZero());
    }

    @Test
    void refreshThatReadSessionBeforeDisableWaitsOnTheAccountLockAndItsReturnedCredentialsAreRevoked() throws Exception {
        String id = account("lifecycle-refresh-race", null, List.of());
        var session = login("lifecycle-refresh-race", "test-only");
        // Hold only the infrastructure row lock. Refresh reads its live session, queues on
        // that lock, and must complete before the subsequently queued disable.
        try (var connection = POSTGRES.createConnection("");
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM freesia.sys_account WHERE id=?::uuid FOR UPDATE")) {
                statement.setString(1, id);
                statement.executeQuery().close();
            }
            var refreshing = executor.submit(() -> refresh(session));
            awaitAccountLockWait();
            var disabling = executor.submit(() -> status(owner, id, false));
            awaitAccountLockWait(2);
            connection.commit();
            var rotated = refreshing.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
            assertThat(disabling.get(15, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertInvalid(rotated);
        }
        assertThat(status(owner, id, true).statusCode()).isEqualTo(200);
        assertInvalid(session);
    }

    void awaitAccountLockWait() throws Exception {
        awaitAccountLockWait(1);
    }

    void awaitAccountLockWait(int count) throws Exception {
        awaitLockWait("SELECT enabled,deleted,session_version,password_hash%FOR UPDATE%", count);
    }

    void awaitLockWait(String queryPattern, int count) throws Exception {
        // Infrastructure-only synchronization, not a database side channel for business results.
        // Removing refresh's lock makes this wait fail instead of passing on an early 401.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (var connection = POSTGRES.createConnection(""); var statement = connection.prepareStatement("""
                SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE ?
                """)) {
            statement.setString(1, queryPattern);
            while (System.nanoTime() < deadline) {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= count) return;
                }
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Request did not reach the expected database lock boundary: " + queryPattern);
    }

    @Test
    void valkeyFailureDuringRevocationReturns503RollsBackStatusAndNeverClaimsCompletion() throws Exception {
        String id = account("lifecycle-valkey-outage", null, List.of());
        var session = login("lifecycle-valkey-outage", "test-only");
        // Hold an earlier target write after its own session checks. The queued lifecycle request
        // authenticates before the outage, then reaches real revocation while Valkey is paused.
        AuditFault.heldTarget = id;
        AuditFault.entered = new java.util.concurrent.CountDownLatch(1);
        AuditFault.release = new java.util.concurrent.CountDownLatch(1);
        var current = detail(id).path("account");
        boolean paused = false;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var earlier = executor.submit(() -> call("PUT", "/api/account/" + id, owner,
                    json.writeValueAsString(Map.of("username", current.path("username").asString(), "remark", "held write"))));
            assertThat(AuditFault.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var disabling = executor.submit(() -> status(owner, id, false));
            awaitLockWait("SELECT purpose FROM freesia.sys_bootstrap%FOR UPDATE%", 1);
            VALKEY.getDockerClient().pauseContainerCmd(VALKEY.getContainerId()).exec();
            paused = true;
            AuditFault.release.countDown();
            assertThat(earlier.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(disabling.get(20, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        } finally {
            AuditFault.release.countDown();
            if (paused) VALKEY.getDockerClient().unpauseContainerCmd(VALKEY.getContainerId()).exec();
            AuditFault.heldTarget = null; AuditFault.entered = null; AuditFault.release = null;
        }
        assertThat(detail(id).path("account").path("status").asString()).isEqualTo("1");
        // A timed-out Lua request may have completed after recovery: either outcome is safe;
        // explicit retry must finish revocation and persist the disabled state.
        assertThat(status(owner, id, false).statusCode()).isEqualTo(200);
        assertInvalid(session);
    }

    @Test
    void deletionRollsBackAllBindingsOnAuditFailureAndCannotReuseAnotherActorsPreview() throws Exception {
        String read = option("system:account:query");
        String shared = role("LIFECYCLE_DELETE_BOUND", "SELF", List.of(read), List.of());
        String first = account("lifecycle-delete-bound-a", null, List.of(shared));
        String second = account("lifecycle-delete-bound-b", null, List.of(shared));
        for (String id : List.of(first, second)) assertThat(status(owner, id, false).statusCode()).isEqualTo(200);
        String confirmation = deletionPreview(List.of(first, second)).path("confirmationToken").asString();
        String deleter = role("LIFECYCLE_SECOND_DELETER", "ALL", List.of(option("system:account:delete")), List.of());
        account("lifecycle-second-deleter", null, List.of(deleter));
        String otherActor = access(login("lifecycle-second-deleter", "test-only"));
        assertThat(call("DELETE", "/api/account/delete-batch?" + parameters(List.of(first, second)), otherActor,
                json.writeValueAsString(Map.of("confirmationToken", confirmation))).statusCode()).isEqualTo(409);
        AuditFault.failedTarget = second;
        try {
            assertThat(delete(List.of(first, second), confirmation).statusCode()).isEqualTo(503);
        } finally { AuditFault.failedTarget = null; }
        for (String id : List.of(first, second)) {
            assertThat(detail(id).path("account").path("roles").get(0).path("roleId").asString()).isEqualTo(shared);
        }
        assertThat(data(delete(List.of(first, second), confirmation)).asBoolean()).isTrue();
        var impact = data(call("GET", "/api/role/bound-accounts?ids=" + shared, owner, null));
        assertThat(impact.path("visibleCount").asLong()).isZero();
    }

    @Test
    void concurrentCorrectPasswordsRemainValidWhenTheFirstLoginUpgradesTheirStoredHash() throws Exception {
        String id = account("lifecycle-rehash-race", null, List.of());
        try (var connection = POSTGRES.createConnection("");
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // Controlled password-cost fixture, not a business-result assertion.
            String hash = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 8192, 1).encode("test-only");
            try (var statement = connection.prepareStatement("UPDATE freesia.sys_account SET password_hash=? WHERE id=?::uuid")) {
                statement.setString(1, hash); statement.setString(2, id); statement.executeUpdate();
            }
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM freesia.sys_account WHERE id=?::uuid FOR UPDATE")) {
                statement.setString(1, id); statement.executeQuery().close();
            }
            var first = executor.submit(() -> login("lifecycle-rehash-race", "test-only"));
            var second = executor.submit(() -> login("lifecycle-rehash-race", "test-only"));
            awaitAccountLockWait(2);
            connection.commit();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @FunctionalInterface
    interface Responses { void check(HttpResponse<String> first, HttpResponse<String> second) throws Exception; }

    void withBarrier(java.util.concurrent.Callable<HttpResponse<String>> first,
                     java.util.concurrent.Callable<HttpResponse<String>> second, Responses assertions) throws Exception {
        AuditFault.entered = new java.util.concurrent.CountDownLatch(1);
        AuditFault.release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var earlier = executor.submit(first);
            assertThat(AuditFault.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var later = executor.submit(second);
            var laterResponse = later.get(10, java.util.concurrent.TimeUnit.SECONDS);
            AuditFault.release.countDown();
            assertions.check(earlier.get(15, java.util.concurrent.TimeUnit.SECONDS), laterResponse);
        } finally {
            AuditFault.release.countDown();
            AuditFault.heldLogin = null; AuditFault.heldTarget = null;
            AuditFault.entered = null; AuditFault.release = null;
        }
    }

    String option(String permission) throws Exception {
        for (JsonNode node : data(call("GET", "/api/role/permission-options", owner, null))) {
            if (permission.equals(node.path("authMark").asString())) return node.path("id").asString();
        }
        throw new AssertionError("Missing option " + permission);
    }

    String role(String code, String scope, List<String> uses, List<String> grants) throws Exception {
        return data(call("POST", "/api/role", owner, json.writeValueAsString(Map.of(
                "roleCode", code, "roleName", code, "description", "", "enabled", true,
                "dataScope", scope, "customOrganizationIds", List.of(), "menuIds", uses, "grantMenuIds", grants)))).asString();
    }

    String organization(String code, String parent) throws Exception {
        if (parent == null) parent = data(call("GET", "/api/organization/tree", owner, null)).get(0).path("id").asString();
        return data(call("POST", "/api/organization", owner, json.writeValueAsString(Map.of(
                "parentId", parent, "code", code, "name", code, "sort", 0)))).asString();
    }

    JsonNode deletionPreview(List<String> ids) throws Exception {
        return data(call("GET", "/api/account/deletion-preview?" + parameters(ids), owner, null));
    }

    HttpResponse<String> delete(List<String> ids, String confirmation) throws Exception {
        return call("DELETE", "/api/account/delete-batch?" + parameters(ids), owner,
                json.writeValueAsString(Map.of("confirmationToken", confirmation)));
    }

    String parameters(List<String> ids) {
        return ids.stream().map(id -> "ids=" + id).collect(java.util.stream.Collectors.joining("&"));
    }

    HttpResponse<String> status(String actor, String target, boolean enabled) throws Exception {
        return call("PUT", "/api/account/" + target + "/status", actor, "{\"enabled\":" + enabled + "}");
    }

    JsonNode detail(String id) throws Exception {
        return data(call("GET", "/api/account/" + id + "/detail-card", owner, null));
    }

    String account(String username, String organization, List<String> roles) throws Exception {
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "username", username, "password", "test-only", "roleIds", roles));
        input.put("organizationId", organization);
        return data(call("POST", "/api/account", owner, json.writeValueAsString(input))).asString();
    }

    HttpResponse<String> login(String user, String password) throws Exception {
        return call("POST", "/api/auth/login", null, json.writeValueAsString(Map.of("userName", user, "password", password)));
    }

    HttpResponse<String> refresh(HttpResponse<String> session) throws Exception {
        return send("POST", "/api/auth/refresh", null, cookie(session), null);
    }

    void assertInvalid(HttpResponse<String> session) throws Exception {
        for (var response : List.of(call("GET", "/api/account/info", access(session), null), refresh(session))) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(10003);
        }
    }

    String access(HttpResponse<String> session) {
        return data(session).path("token").asString();
    }

    String cookie(HttpResponse<String> session) {
        return HttpCookie.parse(session.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getValue();
    }

    JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    HttpResponse<String> call(String method, String path, String access, String body) throws Exception {
        return send(method, path, access, null, body);
    }

    HttpResponse<String> send(String method, String path, String access, String refresh, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Accept-Language", "en").header("X-Freesia-CSRF", "1")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (access != null) request.header("Authorization", access);
        if (refresh != null) request.header("Cookie", "freesia_refresh=" + refresh);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
