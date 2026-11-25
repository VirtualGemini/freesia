package com.freesia.system.iam;

import com.freesia.system.SystemTestApplication;
import com.freesia.common.audit.OperationAudit;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(RoleDeletionTest.AuditFault.class)
class RoleDeletionTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("role_deletion").withUsername("freesia").withPassword("test-only");
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1.6-alpine")
            .withExposedPorts(6379);
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
    String token;

    @TestConfiguration
    static class AuditFault {
        static volatile String failedTarget;
        static volatile boolean failFailure;
        static volatile String heldTarget;
        static volatile java.util.concurrent.CountDownLatch entered;
        static volatile java.util.concurrent.CountDownLatch release;

        @EventListener
        void audit(OperationAudit event) {
            if (event.targetId() != null && event.targetId().equals(failedTarget) && (event.success() || failFailure)) {
                throw new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            if (event.success() && event.targetId() != null && event.targetId().equals(heldTarget) && entered != null) {
                entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Audit test timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    @BeforeAll
    void initializeAndSignInThroughPublicInterfaces() throws Exception {
        Path secret = temporary.resolve("initial-password");
        Files.writeString(secret, "Role-test-initial-password!");
        Path output = temporary.resolve("bootstrap.log");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(), "bootstrap-admin", "--username=role-owner",
                "--password-file=" + secret, "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(),
                "--spring.data.redis.port=" + VALKEY.getMappedPort(6379), "--spring.main.banner-mode=off");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        token = data(call("POST", "/api/auth/login", null,
                "{\"userName\":\"role-owner\",\"password\":\"Role-test-initial-password!\"}")).path("token").asString();
    }

    @Test
    void sharedRolePreviewShowsOnlyVisibleMembersWithoutExposingOutsideIdentitiesOrTotals() throws Exception {
        String first = organization("preview-a");
        String second = organization("preview-b");
        String read = option("system:organization:query");
        String shared = role("SHARED_PREVIEW", "ORG", List.of(read), List.of());
        String admin = role("PREVIEW_ADMIN", "ORG",
                List.of(option("system:role:delete"), option("system:account:query")), List.of(read));
        account("preview-admin", first, List.of(admin));
        String visible = account("preview-visible", first, List.of(shared));
        String hidden = account("preview-hidden", second, List.of(shared));
        var preview = data(call("GET", "/api/role/bound-accounts?ids=" + shared, access("preview-admin"), null));
        assertThat(preview.path("confirmationToken").asString()).isNotBlank();
        assertThat(preview.path("accountsComplete").asBoolean()).isFalse();
        assertThat(preview.path("visibleCount").asLong()).isEqualTo(1);
        assertThat(preview.path("records").get(0).path("accountId").asString()).isEqualTo(visible);
        assertThat(preview.path("roles").get(0).path("roleId").asString()).isEqualTo(shared);
        assertThat(preview.toString()).doesNotContain(hidden, "preview-hidden", "totalCount", "hiddenCount");
        var complete = preview(token, List.of(shared));
        assertThat(complete.path("accountsComplete").asBoolean()).isTrue();
        assertThat(complete.path("visibleCount").asLong()).isEqualTo(2);
        assertThat(complete.toString()).contains(hidden, visible);
        assertThat(data(call("GET", "/api/role/" + shared, token, null)).path("roleId").asString()).isEqualTo(shared);
        assertThat(data(call("GET", "/api/account/" + hidden + "/detail-card", token, null))
                .path("account").path("roles").get(0).path("roleId").asString()).isEqualTo(shared);
    }

    @Test
    void confirmedSingleDeletionUnbindsAllHoldersAndRevokesUseGrantAndScopeWithoutRemovingOtherSources() throws Exception {
        String first = organization("delete-a");
        String second = organization("delete-b");
        String read = option("system:organization:query");
        String accountRead = option("system:account:query");
        String shared = role("SHARED_DELETE", "ORG", List.of(read, accountRead), List.of(read));
        String retained = role("RETAINED_SOURCE", "SELF", List.of(accountRead), List.of(accountRead));
        String admin = role("DELETE_ADMIN", "ORG",
                List.of(option("system:role:delete"), accountRead, option("system:log:operation:query")), List.of(read, accountRead));
        account("delete-admin", first, List.of(admin));
        String visible = account("delete-visible", first, List.of(shared, retained));
        String hidden = account("delete-hidden", second, List.of(shared));
        String visibleAccess = access("delete-visible");
        String hiddenAccess = access("delete-hidden");
        String adminAccess = access("delete-admin");
        assertThat(call("GET", "/api/organization/tree", hiddenAccess, null).statusCode()).isEqualTo(200);
        var preview = data(call("GET", "/api/role/bound-accounts?ids=" + shared, adminAccess, null));
        assertThat(preview.path("visibleCount").asLong()).isEqualTo(1);
        var deleted = call("DELETE", "/api/role/" + shared, adminAccess,
                json.writeValueAsString(Map.of("confirmationToken", preview.path("confirmationToken").asString())));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
        assertThat(data(deleted).asBoolean()).isTrue();
        assertThat(call("GET", "/api/role/" + shared, token, null).statusCode()).isEqualTo(404);
        assertThat(data(call("GET", "/api/account/" + hidden + "/detail-card", token, null))
                .path("account").path("roles").size()).isZero();
        assertThat(data(call("GET", "/api/account/" + visible + "/detail-card", token, null))
                .path("account").path("roles").get(0).path("roleId").asString()).isEqualTo(retained);
        assertThat(call("GET", "/api/organization/tree", hiddenAccess, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/organization/tree", visibleAccess, null).statusCode()).isEqualTo(403);
        var profile = data(call("GET", "/api/iam/permission-profile", visibleAccess, null));
        assertThat(profile.path("grant").has("system:organization:query")).isFalse();
        assertThat(profile.path("grant").has("system:account:query")).isTrue();
        assertThat(profile.path("use").path("system:account:query").path("self").asBoolean()).isTrue();
        assertThat(profile.path("use").path("system:account:query").path("organizationIds").size()).isZero();
        assertThat(data(call("GET", "/api/account/list", visibleAccess, null)).path("total").asLong()).isEqualTo(1);
        var audit = data(call("GET", "/api/log/operation/observations?targetId=" + shared, adminAccess, null));
        assertThat(audit.get(0).path("action").asString()).isEqualTo("system:role:delete");
        assertThat(audit.get(0).path("success").asBoolean()).isTrue();
        assertThat(audit.toString()).doesNotContain("confirmationToken", hidden, "test-only");
    }

    @Test
    void batchPreviewDeduplicatesAccountsAndRejectsChangedHiddenBindingsBeforeAnyRoleIsDeleted() throws Exception {
        String team = organization("batch-team");
        String outside = organization("batch-outside");
        String read = option("system:organization:query");
        String first = role("BATCH_FIRST", "ORG", List.of(read), List.of());
        String second = role("BATCH_SECOND", "ORG", List.of(read), List.of());
        String admin = role("BATCH_ADMIN", "ORG",
                List.of(option("system:role:delete"), option("system:account:query")), List.of(read));
        account("batch-admin", team, List.of(admin));
        String visible = account("batch-visible", team, List.of(first, second));
        String hidden = account("batch-hidden", outside, List.of(first));
        String actor = access("batch-admin");
        List<String> ids = List.of(first, second);
        var preview = preview(actor, ids);
        assertThat(preview.path("roles").size()).isEqualTo(2);
        assertThat(preview.path("visibleCount").asLong()).isEqualTo(1);
        assertThat(preview.path("records").get(0).path("accountId").asString()).isEqualTo(visible);
        assertThat(preview.path("roles").get(0).path("visibleCount").asLong()).isEqualTo(1);
        assertThat(preview.path("roles").get(1).path("visibleCount").asLong()).isEqualTo(1);
        assertThat(assign(hidden, List.of(second), List.of()).statusCode()).isEqualTo(200);
        var stale = delete(actor, ids, preview.path("confirmationToken").asString());
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("code").asInt()).isEqualTo(10017);
        for (String id : ids) assertThat(call("GET", "/api/role/" + id, token, null).statusCode()).isEqualTo(200);
        assertThat(data(call("GET", "/api/account/" + visible + "/detail-card", token, null))
                .path("account").path("roles").size()).isEqualTo(2);
        assertThat(data(delete(actor, ids.reversed(), preview(actor, ids).path("confirmationToken").asString())).asBoolean()).isTrue();
        for (String id : ids) assertThat(call("GET", "/api/role/" + id, token, null).statusCode()).isEqualTo(404);
        assertThat(data(call("GET", "/api/account/" + hidden + "/detail-card", token, null))
                .path("account").path("roles").size()).isZero();
    }

    @Test
    void builtInMissingAndExcessUseOrGrantRolesRejectTheWholeBatchAndAuditEveryRequestedTarget() throws Exception {
        String allowed = option("system:organization:query");
        String excess = option("system:account:update");
        String ordinary = role("ATOMIC_ORDINARY", "SELF", List.of(allowed), List.of());
        String excessUse = role("ATOMIC_EXCESS_USE", "SELF", List.of(excess), List.of());
        String excessGrant = role("ATOMIC_EXCESS_GRANT", "SELF", List.of(allowed), List.of(excess));
        String administrator = role("ATOMIC_ADMIN", "ALL",
                List.of(option("system:role:delete"), option("system:log:operation:query")), List.of(allowed));
        account("atomic-admin", null, List.of(administrator));
        String actor = access("atomic-admin");
        String ownerRole = data(call("GET", "/api/role/list?roleCode=R_SUPER", token, null))
                .path("records").get(0).path("roleId").asString();
        String confirmation = preview(actor, List.of(ordinary)).path("confirmationToken").asString();
        for (String forbidden : List.of(ownerRole, excessUse, excessGrant)) {
            var rejected = delete(actor, List.of(ordinary, forbidden), confirmation);
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(403);
            assertThat(call("GET", "/api/role/" + ordinary, token, null).statusCode()).isEqualTo(200);
            assertThat(call("GET", "/api/role/bound-accounts?ids=" + forbidden, actor, null).statusCode()).isEqualTo(403);
            var audit = data(call("GET", "/api/log/operation/observations?targetId=" + forbidden, actor, null));
            assertThat(audit.get(0).path("success").asBoolean()).isFalse();
            assertThat(audit.get(0).path("action").asString()).isEqualTo("system:role:delete");
        }
        assertThat(call("GET", "/api/role/bound-accounts?ids=" + ownerRole, token, null).statusCode()).isEqualTo(403);
        assertThat(delete(token, List.of(ordinary, ownerRole), confirmation).statusCode()).isEqualTo(403);
        String missing = java.util.UUID.randomUUID().toString();
        assertThat(delete(token, List.of(ordinary, missing), confirmation).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/role/" + ordinary, token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void deletionRequiresItsOwnUseAndDataRangeAndRechecksAuthorityRatherThanTrustingConfirmation() throws Exception {
        String team = organization("guards-team");
        String read = option("system:organization:query");
        String target = role("GUARDED_TARGET", "ALL", List.of(read), List.of());
        String local = role("GUARDED_LOCAL", "SELF", List.of(option("system:role:delete")), List.of());
        String other = role("GUARDED_OTHER", "ALL", List.of(option("system:account:query")), List.of(read));
        String noUse = role("GUARDED_GRANT_ONLY", "ALL", List.of(), List.of(read));
        account("guarded-local", team, List.of(local, other));
        account("guarded-no-use", null, List.of(noUse));
        for (String user : List.of("guarded-local", "guarded-no-use")) {
            String actor = access(user);
            assertThat(call("GET", "/api/role/bound-accounts?ids=" + target, actor, null).statusCode()).isEqualTo(403);
            assertThat(delete(actor, List.of(target), preview(token, List.of(target)).path("confirmationToken").asString())
                    .statusCode()).isEqualTo(403);
        }
        String administrator = role("GUARDED_ADMIN", "ALL", List.of(option("system:role:delete")), List.of(read));
        String adminId = account("guarded-admin", null, List.of(administrator));
        String actor = access("guarded-admin");
        String confirmation = preview(actor, List.of(target)).path("confirmationToken").asString();
        assertThat(delete(token, List.of(target), confirmation).statusCode()).isEqualTo(409);
        assertThat(assign(adminId, List.of(), List.of(administrator)).statusCode()).isEqualTo(200);
        assertThat(delete(actor, List.of(target), confirmation).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/role/" + target, token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void missingForgedOrChangedTargetConfirmationsCannotDeleteAndSuccessfulBatchAuditsEachRole() throws Exception {
        String first = role("CONFIRM_FIRST", "SELF", List.of(), List.of());
        String second = role("CONFIRM_SECOND", "SELF", List.of(), List.of());
        String confirmation = preview(token, List.of(first)).path("confirmationToken").asString();
        assertThat(call("DELETE", "/api/role/" + first, token, null).statusCode()).isEqualTo(409);
        assertThat(delete(token, List.of(first), "A".repeat(43)).statusCode()).isEqualTo(409);
        assertThat(delete(token, List.of(first, second), confirmation).statusCode()).isEqualTo(409);
        var changed = data(call("GET", "/api/role/" + first, token, null));
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "roleCode", "CONFIRM_FIRST", "roleName", "Changed after preview", "description", "",
                "enabled", true, "dataScope", "SELF", "menuIds", List.of(), "grantMenuIds", List.of(), "customOrganizationIds", List.of()));
        assertThat(call("PUT", "/api/role/" + first, token, json.writeValueAsString(input)).statusCode()).isEqualTo(200);
        assertThat(delete(token, List.of(first), confirmation).statusCode()).isEqualTo(409);
        assertThat(changed.path("roleName").asString()).isEqualTo("CONFIRM_FIRST");
        var fresh = preview(token, List.of(second, first));
        assertThat(fresh.path("accountsComplete").asBoolean()).isTrue();
        assertThat(fresh.path("visibleCount").asLong()).isZero();
        assertThat(data(delete(token, List.of(first, second, first), fresh.path("confirmationToken").asString())).asBoolean()).isTrue();
        for (String id : List.of(first, second)) {
            var audit = data(call("GET", "/api/log/operation/observations?targetId=" + id, token, null));
            assertThat(audit.get(0).path("success").asBoolean()).isTrue();
            assertThat(audit.get(0).path("action").asString()).isEqualTo("system:role:delete");
        }
        assertThat(delete(token, List.of(first, second), fresh.path("confirmationToken").asString()).statusCode()).isEqualTo(404);
    }

    @Test
    void auditFailureRollsBackAllRolesMembershipAndAuthorizationAndOnlyFailureObservationsRemain() throws Exception {
        String team = organization("rollback-team");
        String read = option("system:organization:query");
        String first = role("ROLLBACK_FIRST", "ORG", List.of(read), List.of(read));
        String second = role("ROLLBACK_SECOND", "ORG", List.of(read), List.of());
        String member = account("rollback-member", team, List.of(first, second));
        String access = access("rollback-member");
        var ids = List.of(first, second);
        String confirmation = preview(token, ids).path("confirmationToken").asString();
        AuditFault.failedTarget = second;
        try {
            assertThat(delete(token, ids, confirmation).statusCode()).isEqualTo(503);
        } finally {
            AuditFault.failedTarget = null;
        }
        for (String id : ids) {
            assertThat(call("GET", "/api/role/" + id, token, null).statusCode()).isEqualTo(200);
            var observations = data(call("GET", "/api/log/operation/observations?targetId=" + id, token, null));
            var deletions = new java.util.ArrayList<JsonNode>();
            for (JsonNode entry : observations) if ("system:role:delete".equals(entry.path("action").asString())) deletions.add(entry);
            assertThat(deletions).hasSize(1).allSatisfy(entry -> assertThat(entry.path("success").asBoolean()).isFalse());
        }
        assertThat(data(call("GET", "/api/account/" + member + "/detail-card", token, null))
                .path("account").path("roles").size()).isEqualTo(2);
        assertThat(call("GET", "/api/organization/tree", access, null).statusCode()).isEqualTo(200);
        assertThat(data(call("GET", "/api/iam/permission-profile", access, null)).path("grant").has("system:organization:query")).isTrue();
        assertThat(data(delete(token, ids, confirmation)).asBoolean()).isTrue();
    }

    @Test
    void concurrentBindingChangeCommitsBeforeDeletionRecheckAndCannotBeSilentlyUnbound() throws Exception {
        String role = role("RACE_BIND_TARGET", "SELF", List.of(), List.of());
        String member = account("race-bind-member", null, List.of());
        String confirmation = preview(token, List.of(role)).path("confirmationToken").asString();
        assertWritePrecedesDeletion(member, () -> assign(member, List.of(role), List.of()),
                () -> delete(token, List.of(role), confirmation), 409);
        assertThat(data(call("GET", "/api/account/" + member + "/detail-card", token, null))
                .path("account").path("roles").get(0).path("roleId").asString()).isEqualTo(role);
    }

    @Test
    void grantRangeMustCoverTheEntireRoleAndUnavailableFailureAuditNeverMasksTheOriginalDenial() throws Exception {
        String read = option("system:organization:query");
        String target = role("RANGE_ALL_TARGET", "ALL", List.of(read), List.of());
        String narrowGrant = role("RANGE_NARROW_ADMIN", "SELF", List.of(option("system:role:delete")), List.of(read));
        account("range-narrow-admin", null, List.of(narrowGrant));
        String actor = access("range-narrow-admin");
        assertThat(call("GET", "/api/role/bound-accounts?ids=" + target, actor, null).statusCode()).isEqualTo(403);
        AuditFault.failedTarget = target;
        AuditFault.failFailure = true;
        try {
            var denial = delete(actor, List.of(target), preview(token, List.of(target)).path("confirmationToken").asString());
            assertThat(denial.statusCode()).isEqualTo(403);
            assertThat(json.readTree(denial.body()).path("code").asInt()).isEqualTo(10011);
        } finally {
            AuditFault.failedTarget = null;
            AuditFault.failFailure = false;
        }
        assertThat(call("GET", "/api/role/" + target, token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentGrantRevocationWinsBeforeDeletionRechecksTheActorsCurrentGrantAuthority() throws Exception {
        String read = option("system:organization:query");
        String target = role("REVOKE_TARGET", "SELF", List.of(read), List.of());
        String admin = role("REVOKE_ADMIN", "ALL", List.of(option("system:role:delete")), List.of(read));
        String actorId = account("revoke-delete-admin", null, List.of(admin));
        String actor = access("revoke-delete-admin");
        String confirmation = preview(actor, List.of(target)).path("confirmationToken").asString();
        assertWritePrecedesDeletion(actorId, () -> assign(actorId, List.of(), List.of(admin)),
                () -> delete(actor, List.of(target), confirmation), 403);
        assertThat(call("GET", "/api/role/" + target, token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void customScopeReferencesAreRemovedWithDeletionAndNoLongerBlockTheirOrganization() throws Exception {
        String org = organization("deleted-custom-scope");
        String read = option("system:organization:query");
        String target = data(call("POST", "/api/role", token, json.writeValueAsString(Map.of(
                "roleCode", "DELETE_CUSTOM_SCOPE", "roleName", "Custom", "description", "", "enabled", true,
                "dataScope", "CUSTOM_ORGS", "customOrganizationIds", List.of(org), "menuIds", List.of(read), "grantMenuIds", List.of(read))))).asString();
        assertThat(call("DELETE", "/api/organization/" + org, token, null).statusCode()).isEqualTo(409);
        assertThat(data(delete(token, List.of(target), preview(token, List.of(target)).path("confirmationToken").asString())).asBoolean()).isTrue();
        assertThat(call("DELETE", "/api/organization/" + org, token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void relativeRoleDeletionChecksGrantAndDeleteRangesInTheActorsContextWithoutControllingAllHolders() throws Exception {
        String parent = organization("relative-delete-parent");
        data(call("POST", "/api/organization", token, json.writeValueAsString(Map.of(
                "parentId", parent, "code", "relative-delete-child", "name", "Child", "sort", 0))));
        String read = option("system:organization:query");
        String deletion = option("system:role:delete");
        String narrowUse = role("RELATIVE_DELETE_SELF", "SELF", List.of(deletion), List.of());
        String fullUse = role("RELATIVE_DELETE_ALL", "ALL", List.of(deletion), List.of());
        String narrowGrant = role("RELATIVE_GRANT_SELF", "SELF", List.of(), List.of(read));
        String fullGrant = role("RELATIVE_GRANT_ALL", "ALL", List.of(), List.of(read));
        account("relative-delete-local", parent, List.of(narrowUse, fullGrant));
        account("relative-delete-grant", parent, List.of(fullUse, narrowGrant));
        account("relative-delete-no-org", null, List.of(fullUse, narrowGrant));
        String emptyUse = role("RELATIVE_EMPTY_USE", "CUSTOM_ORGS", List.of(deletion), List.of());
        account("relative-delete-empty", parent, List.of(emptyUse, fullGrant));
        String local = access("relative-delete-local");
        String grant = access("relative-delete-grant");
        String unscoped = access("relative-delete-no-org");
        String empty = access("relative-delete-empty");
        for (String scope : List.of("ORG", "SUB_ORG", "ORG_AND_SUB")) {
            String target = role("RELATIVE_TARGET_" + scope, scope, List.of(read), List.of(read));
            String confirmation = preview(token, List.of(target)).path("confirmationToken").asString();
            for (String actor : List.of(local, grant, unscoped, empty)) {
                assertThat(call("GET", "/api/role/bound-accounts?ids=" + target, actor, null).statusCode())
                        .as(scope + " preview").isEqualTo(403);
                assertThat(delete(actor, List.of(target), confirmation).statusCode()).as(scope + " execute").isEqualTo(403);
            }
            assertThat(call("GET", "/api/role/" + target, token, null).statusCode()).isEqualTo(200);
        }
    }

    void assertWritePrecedesDeletion(String heldTarget, java.util.concurrent.Callable<HttpResponse<String>> write,
                                    java.util.concurrent.Callable<HttpResponse<String>> delete, int deletionStatus) throws Exception {
        AuditFault.heldTarget = heldTarget;
        AuditFault.entered = new java.util.concurrent.CountDownLatch(1);
        AuditFault.release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var earlier = executor.submit(write);
            assertThat(AuditFault.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(delete);
            Thread.sleep(100);
            assertThat(deletion.isDone()).isFalse();
            AuditFault.release.countDown();
            assertThat(earlier.get().statusCode()).isEqualTo(200);
            assertThat(deletion.get().statusCode()).isEqualTo(deletionStatus);
        } finally {
            AuditFault.release.countDown();
            AuditFault.heldTarget = null;
            AuditFault.entered = null;
            AuditFault.release = null;
        }
    }

    JsonNode preview(String actor, List<String> ids) throws Exception {
        return data(call("GET", "/api/role/bound-accounts?" + ids.stream().map(id -> "ids=" + id)
                .collect(java.util.stream.Collectors.joining("&")), actor, null));
    }

    HttpResponse<String> delete(String actor, List<String> ids, String confirmation) throws Exception {
        return call("DELETE", "/api/role/delete-batch?" + ids.stream().map(id -> "ids=" + id)
                        .collect(java.util.stream.Collectors.joining("&")), actor,
                json.writeValueAsString(Map.of("confirmationToken", confirmation)));
    }

    HttpResponse<String> assign(String account, List<String> add, List<String> remove) throws Exception {
        return call("PUT", "/api/account/" + account + "/roles", token,
                json.writeValueAsString(Map.of("addRoleIds", add, "removeRoleIds", remove)));
    }

    String organization(String code) throws Exception {
        String root = data(call("GET", "/api/organization/tree", token, null)).get(0).path("id").asString();
        return data(call("POST", "/api/organization", token, json.writeValueAsString(Map.of(
                "parentId", root, "code", code, "name", code, "remark", "", "sort", 0)))).asString();
    }

    String option(String permission) throws Exception {
        for (JsonNode node : data(call("GET", "/api/role/permission-options", token, null))) {
            if (permission.equals(node.path("authMark").asString())) return node.path("id").asString();
        }
        throw new AssertionError("Missing option " + permission);
    }

    String role(String code, String scope, List<String> uses, List<String> grants) throws Exception {
        return data(call("POST", "/api/role", token, json.writeValueAsString(Map.of(
                "roleCode", code, "roleName", code, "description", "", "enabled", true,
                "dataScope", scope, "customOrganizationIds", List.of(), "menuIds", uses, "grantMenuIds", grants)))).asString();
    }

    String account(String username, String organization, List<String> roles) throws Exception {
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "username", username, "password", "test-only", "roleIds", roles));
        input.put("organizationId", organization);
        return data(call("POST", "/api/account", token, json.writeValueAsString(input))).asString();
    }

    String access(String username) throws Exception {
        return data(call("POST", "/api/auth/login", null, json.writeValueAsString(Map.of(
                "userName", username, "password", "test-only")))).path("token").asString();
    }

    JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    HttpResponse<String> call(String method, String path, String access, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Accept-Language", "en").header("X-Freesia-CSRF", "1")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (access != null) request.header("Authorization", access);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
