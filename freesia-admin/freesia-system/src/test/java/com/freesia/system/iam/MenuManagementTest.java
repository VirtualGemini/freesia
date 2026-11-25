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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(MenuManagementTest.AuditFault.class)
class MenuManagementTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("menu_management").withUsername("freesia").withPassword("test-only");
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
        Files.writeString(secret, "Menu-test-initial-password!");
        Path output = temporary.resolve("bootstrap.log");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(), "bootstrap-admin", "--username=menu-owner",
                "--password-file=" + secret, "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(),
                "--spring.data.redis.port=" + VALKEY.getMappedPort(6379), "--spring.main.banner-mode=off");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        var login = call("POST", "/api/auth/login", null,
                "{\"userName\":\"menu-owner\",\"password\":\"Menu-test-initial-password!\"}");
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        token = json.readTree(login.body()).path("data").path("token").asString();
    }

    @Test
    void theGlobalMaintenanceDirectoryPreservesTheExistingTreeAndPresentationMetadata() throws Exception {
        var response = call("GET", "/api/v3/system/menus", token, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode account = named(data(response), "Account");
        assertThat(account).isNotNull();
        assertThat(account.path("parentId").asString()).isEqualTo(named(data(response), "System").path("id").asString());
        assertThat(account.path("component").asString()).isEqualTo("/system/account");
        assertThat(account.path("meta").has("isEnable")).isTrue();
        assertThat(account.path("meta").has("createTime")).isTrue();
        assertThat(account.path("meta").has("updateTime")).isTrue();
        assertThat(account.path("meta").path("authList").toString()).contains("system:account:transfer");
        var navigation = data(call("GET", "/api/v3/system/menus/simple", token, null));
        assertThat(named(navigation, "Menus").path("meta").path("authList").toString())
                .as("The existing v-auth directive consumes granted route buttons")
                .contains("system:menu:create");
    }

    @Test
    void anExternalLinkCanChangeThenExplicitlyClearWithoutRestoringItsOldAddressOrIframe() throws Exception {
        var created = call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"LinkLifecycle","title":"Link lifecycle","path":"/link-lifecycle",
                 "component":"/system/menu","isEnable":true,"sort":3,"link":"https://example.com/a","isIframe":true,
                 "keepAlive":true,"isHide":true,"isHideTab":true,"showBadge":true,"showTextBadge":"New",
                 "fixedTab":true,"activePath":"/system/menu","isFullPage":true}
                """);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String id = data(created).asString();
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"link\":\"https://example.com/b\"}").statusCode()).isEqualTo(200);
        var node = named(data(call("GET", "/api/v3/system/menus", token, null)), "LinkLifecycle");
        assertThat(node.path("meta").path("link").asString()).isEqualTo("https://example.com/b");
        assertThat(node.path("component").asString()).isEqualTo("/system/menu");
        assertThat(node.path("meta").path("keepAlive").asBoolean()).isTrue();
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"link\":null}").statusCode()).isEqualTo(200);
        node = named(data(call("GET", "/api/v3/system/menus", token, null)), "LinkLifecycle");
        assertThat(node.path("meta").path("link").isNull()).isTrue();
        assertThat(node.path("meta").path("isIframe").asBoolean()).isFalse();
        assertThat(node.path("meta").path("showTextBadge").asString()).isEqualTo("New");
        var navigation = named(data(call("GET", "/api/v3/system/menus/simple", token, null)), "LinkLifecycle");
        assertThat(navigation).as("New definitions remain available to the initial owner").isNotNull();
        assertThat(navigation.path("meta").path("link").isNull()).isTrue();
        assertThat(navigation.path("meta").path("isIframe").asBoolean()).isFalse();
        assertThat(navigation.path("component").asString()).isEqualTo("/system/menu");
    }

    @Test
    void globalMaintenanceAndGrantRightsNeverExpandTheAccountsUsableNavigation() throws Exception {
        String console = option("name", "Console");
        String query = option("authMark", "system:menu:query");
        String viewRole = role("CONSOLE_ONLY", "SELF", List.of(console), List.of());
        String maintenance = role("GLOBAL_MENU_READER", "ALL", List.of(query), List.of());
        String account = account("menu-directory-reader", List.of(viewRole, maintenance));
        String access = access("menu-directory-reader");
        assertThat(data(call("GET", "/api/v3/system/menus", access, null)).toString()).contains("Account", "Role", "Menus");
        JsonNode navigation = data(call("GET", "/api/v3/system/menus/simple", access, null));
        assertThat(named(navigation, "Console")).isNotNull();
        assertThat(named(navigation, "Dashboard")).as("Only necessary ancestors accompany authorized children").isNotNull();
        assertThat(named(navigation, "Account")).isNull();
        assertThat(named(navigation, "Menus")).isNull();
        assertThat(named(navigation, "System")).isNull();
        assertThat(call("POST", "/api/v3/system/menus", access,
                "{\"menuType\":\"menu\",\"name\":\"NoCreate\",\"title\":\"No create\",\"path\":\"/no-create\",\"component\":\"/system/menu\"}")
                .statusCode()).isEqualTo(403);

        String grantRole = role("MENU_GRANT_ONLY", "ALL", List.of(), List.of(query, console));
        account("menu-grant-only", List.of(grantRole));
        String grantAccess = access("menu-grant-only");
        assertThat(call("GET", "/api/v3/system/menus", grantAccess, null).statusCode()).isEqualTo(403);
        assertThat(data(call("GET", "/api/v3/system/menus/simple", grantAccess, null)).size()).isZero();
        assertThat(call("PUT", "/api/account/" + account + "/roles", token,
                json.writeValueAsString(Map.of("addRoleIds", List.of(), "removeRoleIds", List.of(viewRole)))).statusCode()).isEqualTo(200);
        assertThat(data(call("GET", "/api/v3/system/menus/simple", access, null)).size()).isZero();
    }

    @Test
    void eachMaintenanceFunctionRequiresItsOwnAllDataRangeRatherThanAnotherFunctionsRange() throws Exception {
        String query = option("authMark", "system:menu:query");
        String update = option("authMark", "system:menu:update");
        String local = role("MENU_LOCAL_UPDATE", "SELF", List.of(update), List.of());
        String global = role("MENU_GLOBAL_QUERY", "ALL", List.of(query), List.of());
        account("menu-local-update", List.of(local, global));
        String access = access("menu-local-update");
        assertThat(call("GET", "/api/v3/system/menus", access, null).statusCode()).isEqualTo(200);
        assertThat(call("PUT", "/api/v3/system/menus/" + option("name", "Console"), access,
                "{\"title\":\"Must not change\"}").statusCode()).isEqualTo(403);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "Console")
                .path("meta").path("title").asString()).isNotEqualTo("Must not change");
        String self = role("MENU_SELF_QUERY", "SELF", List.of(query), List.of());
        account("menu-self-reader", List.of(self));
        assertThat(call("GET", "/api/v3/system/menus", access("menu-self-reader"), null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/v3/system/menus", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void existingDirectoriesKeepTheirBlankComponentWhileLinkOnlyPagesCannotBeClearedIntoBrokenRoutes() throws Exception {
        String directory = data(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"BlankDirectory","title":"Directory","path":"/blank-directory",
                 "component":"/index/index","sort":8,"isEnable":true}
                """)).asString();
        data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(Map.of(
                "menuType", "menu", "name", "DirectoryChild", "title", "Child", "path", "child",
                "parentId", directory, "component", "/system/menu", "sort", 1, "isEnable", true))));
        assertThat(call("PUT", "/api/v3/system/menus/" + directory, token, "{\"component\":\"\"}")
                .statusCode()).as("The retained form allows directory components to be blank").isEqualTo(200);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "BlankDirectory")
                .path("component").isNull()).isTrue();
        String external = data(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"LinkOnly","title":"Link only","path":"/link-only","sort":8,
                 "isEnable":true,"link":"https://example.com/only","isIframe":true}
                """)).asString();
        assertThat(call("PUT", "/api/v3/system/menus/" + external, token, "{\"link\":\"\"}")
                .statusCode()).isEqualTo(400);
        JsonNode unchanged = named(data(call("GET", "/api/v3/system/menus", token, null)), "LinkOnly");
        assertThat(unchanged.path("meta").path("link").asString()).isEqualTo("https://example.com/only");
        assertThat(unchanged.path("meta").path("isIframe").asBoolean()).isTrue();
        assertThat(call("PUT", "/api/v3/system/menus/" + external, token,
                "{\"link\":\"\",\"component\":\"/system/menu\"}").statusCode()).isEqualTo(200);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "LinkOnly")
                .path("meta").path("link").isNull()).isTrue();
    }

    @Test
    void aDirectoryCanStartWithNoComponentAndBeFilledWithoutChangingTheRetainedEditorContract() throws Exception {
        var created = call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"NewEmptyDirectory","title":"New directory","path":"/new-directory",
                 "component":"","sort":1,"isEnable":true}
                """);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var directory = named(data(call("GET", "/api/v3/system/menus", token, null)), "NewEmptyDirectory");
        assertThat(directory.path("component").isNull()).isTrue();
    }

    @Test
    void menuTreeWritesRejectCyclesButtonsAsParentsDuplicatesAndReferencedDeletion() throws Exception {
        String parent = createMenu("TreeParent", null);
        String child = createMenu("TreeChild", parent);
        String button = data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(Map.of(
                "menuType", "button", "name", "TreePermission", "title", "Permission", "parentId", child,
                "authMark", "test:tree:action", "sort", 1, "isEnable", true)))).asString();
        assertThat(call("PUT", "/api/v3/system/menus/" + parent, token,
                "{\"parentId\":\"" + child + "\"}").statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token,
                "{\"parentId\":\"" + child + "\"}").statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/api/v3/system/menus/" + parent, token,
                "{\"parentId\":\"" + button + "\"}").statusCode()).isEqualTo(409);
        assertThat(call("DELETE", "/api/v3/system/menus/" + parent, token, null).statusCode()).isEqualTo(409);
        assertThat(call("DELETE", "/api/v3/system/menus/" + child, token, null).statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"TreeParent","title":"Duplicate","path":"/duplicate","component":"/system/menu"}
                """).statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token,
                "{\"authMark\":\"test:tree:action\"}").statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token,
                "{\"parentId\":\"not-a-uuid\"}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token,
                "{\"parentId\":\"" + UUID.randomUUID() + "\"}").statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token, "{\"menuType\":\"button\"}")
                .statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token, "{\"link\":\"javascript:alert(1)\"}")
                .statusCode()).isEqualTo(400);
        assertThat(call("DELETE", "/api/v3/system/menus/" + button, token, null).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/api/v3/system/menus/" + child, token, null).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/api/v3/system/menus/" + parent, token, null).statusCode()).isEqualTo(200);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "TreeParent")).isNull();
    }

    @Test
    void hiddenAndCachedMenusDoNotGrantFunctionsAndDisableOrDeleteRevokesTheExistingSessionImmediately() throws Exception {
        String menu = createMenu("VisibilityPage", null);
        String button = data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(Map.of(
                "menuType", "button", "name", "VisibilityAction", "title", "Action", "parentId", menu,
                "authMark", "test:visibility:action", "sort", 1, "isEnable", true)))).asString();
        String role = role("VISIBILITY_ROLE", "ALL", List.of(menu, button), List.of(button));
        account("visibility-member", List.of(role));
        String access = access("visibility-member");
        assertThat(data(call("GET", "/api/iam/permissions", access, null)).toString()).contains("test:visibility:action");
        assertThat(call("PUT", "/api/v3/system/menus/" + menu, token, "{\"isHide\":true,\"keepAlive\":true}")
                .statusCode()).isEqualTo(200);
        assertThat(named(data(call("GET", "/api/v3/system/menus/simple", access, null)), "VisibilityPage")
                .path("meta").path("isHide").asBoolean()).isTrue();
        assertThat(data(call("GET", "/api/iam/permissions", access, null)).toString()).contains("test:visibility:action");
        assertThat(call("PUT", "/api/v3/system/menus/" + button, token, "{\"authMark\":\"test:visibility:replacement\"}")
                .statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/api/v3/system/menus/" + button, token, "{\"isEnable\":false}").statusCode()).isEqualTo(200);
        assertThat(data(call("GET", "/api/iam/permissions", access, null)).toString()).doesNotContain("test:visibility:action");
        assertThat(call("PUT", "/api/v3/system/menus/" + button, token, "{\"isEnable\":true}").statusCode()).isEqualTo(200);
        assertThat(data(call("GET", "/api/iam/permissions", access, null)).toString()).contains("test:visibility:action");
        assertThat(call("DELETE", "/api/v3/system/menus/" + button, token, null).statusCode()).isEqualTo(200);
        var profile = data(call("GET", "/api/iam/permission-profile", access, null));
        assertThat(profile.path("use").has("test:visibility:action")).isFalse();
        assertThat(profile.path("grant").has("test:visibility:action")).isFalse();
        var definition = data(call("GET", "/api/role/" + role, token, null));
        assertThat(definition.path("menuIds").toString()).doesNotContain(button).contains(menu);
        assertThat(definition.path("grantMenuIds").size()).isZero();
        assertThat(call("DELETE", "/api/v3/system/menus/" + menu, token, null).statusCode()).isEqualTo(200);
        assertThat(named(data(call("GET", "/api/v3/system/menus/simple", access, null)), "VisibilityPage")).isNull();
    }

    @Test
    void auditFailureRollsBackMenuChangesAndFailureAuditIsIndependentWithoutMaskingTheOriginalError() throws Exception {
        String id = createMenu("AuditPage", null);
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token, "{\"title\":\"Before fault\"}")
                .statusCode()).isEqualTo(200);
        try {
            AuditFault.failedTarget = id;
            assertThat(call("PUT", "/api/v3/system/menus/" + id, token, "{\"title\":\"Rolled back\"}")
                    .statusCode()).isEqualTo(503);
            var observations = data(call("GET", "/api/log/operation/observations?targetId=" + id, token, null));
            assertThat(observations.get(0).path("success").asBoolean()).isFalse();
            assertThat(observations.get(0).path("action").asString()).isEqualTo("system:menu:update");
            AuditFault.failFailure = true;
            assertThat(call("DELETE", "/api/v3/system/menus/" + id, token, null).statusCode()).isEqualTo(503);
        } finally {
            AuditFault.failedTarget = null;
            AuditFault.failFailure = false;
        }
        var unchanged = named(data(call("GET", "/api/v3/system/menus", token, null)), "AuditPage");
        assertThat(unchanged.path("meta").path("title").asString()).isEqualTo("Before fault");
        assertThat(named(data(call("GET", "/api/v3/system/menus/simple", token, null)), "AuditPage")).isNotNull();
        assertThat(data(call("GET", "/api/log/operation/observations?targetId=" + id, token, null)).toString())
                .contains("system:menu:create", "system:menu:update").doesNotContain("\"action\":\"system:menu:delete\",\"targetType\":\"menu\",\"targetId\":\"" + id + "\",\"success\":true");
    }

    @Test
    void permissionRevocationWinsAgainstAMenuWriteWaitingForTheAuthoritativeTransactionLock() throws Exception {
        String menu = createMenu("RevocationTarget", null);
        String editor = role("RACE_MENU_EDITOR", "ALL", List.of(option("authMark", "system:menu:update")), List.of());
        account("race-menu-editor", List.of(editor));
        String access = access("race-menu-editor");
        AuditFault.heldTarget = editor;
        AuditFault.entered = new java.util.concurrent.CountDownLatch(1);
        AuditFault.release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var revoke = executor.submit(() -> call("PUT", "/api/role/" + editor + "/status", token, "{\"enabled\":false}"));
            assertThat(AuditFault.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var write = executor.submit(() -> call("PUT", "/api/v3/system/menus/" + menu, access,
                    "{\"title\":\"Revoked editor must not save\"}"));
            Thread.sleep(100);
            assertThat(write.isDone()).isFalse();
            AuditFault.release.countDown();
            assertThat(revoke.get().statusCode()).isEqualTo(200);
            assertThat(write.get().statusCode()).isEqualTo(403);
        } finally {
            AuditFault.release.countDown();
            AuditFault.heldTarget = null;
            AuditFault.entered = null;
            AuditFault.release = null;
        }
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "RevocationTarget")
                .path("meta").path("title").asString()).isEqualTo("RevocationTarget");
    }

    @Test
    void concurrentDuplicateCreationProducesOneRealMenuAndOneConflict() throws Exception {
        String input = """
                {"menuType":"menu","name":"ConcurrentMenu","title":"Concurrent","path":"/concurrent-menu",
                 "component":"/system/menu","isEnable":true,"sort":1}
                """;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return call("POST", "/api/v3/system/menus", token, input); });
            var second = executor.submit(() -> { start.await(); return call("POST", "/api/v3/system/menus", token, input); });
            start.countDown();
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "ConcurrentMenu")).isNotNull();
    }

    @Test
    void deletingAMenuCannotOrphanTheLegacyPermissionButtonsShownUnderIt() throws Exception {
        String accountMenu = option("name", "Account");
        assertThat(call("DELETE", "/api/v3/system/menus/" + accountMenu, token, null).statusCode())
                .as("Legacy seed buttons are presented as account-menu children").isEqualTo(409);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "Account")).isNotNull();
    }

    @Test
    void relativeOrProtocolRelativeExternalAddressesAreBadRequestsRatherThanServerErrors() throws Exception {
        String id = createMenu("InvalidExternalLink", null);
        for (String value : List.of("/relative", "//example.com/path", "example.com/path")) {
            var response = call("PUT", "/api/v3/system/menus/" + id, token,
                    json.writeValueAsString(Map.of("link", value)));
            assertThat(response.statusCode()).as(value + ": " + response.body()).isEqualTo(400);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "InvalidExternalLink")
                .path("meta").path("link").isNull()).isTrue();
    }

    @Test
    void legacyRootButtonsCanStillChangeTheirLabelsSortingAndStatusWithoutBeingReparented() throws Exception {
        String id = option("name", "FileRead");
        var result = call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"title\":\"Existing root permission\",\"sort\":0}");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        var node = named(data(call("GET", "/api/v3/system/menus", token, null)), "FileRead");
        assertThat(node.path("parentId").isNull()).isTrue();
        assertThat(node.path("meta").path("title").asString()).isEqualTo("Existing root permission");
        assertThat(node.path("meta").path("sort").asInt()).isZero();
    }

    @Test
    void externalLinkFallbackRedirectsMustReachAnExistingInternalPageAndCannotLoop() throws Exception {
        String id = data(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"RedirectFallback","title":"Redirect fallback","path":"/redirect-fallback",
                 "link":"https://example.com/redirect","isIframe":true,"sort":1,"isEnable":true}
                """)).asString();
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"link\":null,\"redirect\":\"/missing-internal-page\"}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"link\":null,\"redirect\":\"/redirect-fallback\"}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"link\":null,\"redirect\":\"/system/menu\"}").statusCode()).isEqualTo(200);
        var redirect = named(data(call("GET", "/api/v3/system/menus/simple", token, null)), "RedirectFallback");
        assertThat(redirect.path("redirect").asString()).isEqualTo("/system/menu");
        assertThat(redirect.path("component").isNull()).isTrue();
        String second = data(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"SecondRedirect","title":"Second redirect","path":"/second-redirect",
                 "redirect":"/redirect-fallback","sort":1,"isEnable":true}
                """)).asString();
        assertThat(call("PUT", "/api/v3/system/menus/" + id, token,
                "{\"redirect\":\"/second-redirect\"}").statusCode()).isEqualTo(400);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "SecondRedirect")
                .path("id").asString()).isEqualTo(second);
    }

    @Test
    void redirectingTheFirstInternalChildToItsDirectoryCannotCreateAnImplicitRedirectCycle() throws Exception {
        String parent = data(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"ImplicitRedirectParent","title":"Directory","path":"/implicit-parent",
                 "component":"/index/index","sort":1,"isEnable":true}
                """)).asString();
        String child = data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(Map.of(
                "menuType", "menu", "name", "ImplicitRedirectChild", "title", "Child", "path", "child",
                "component", "/system/menu", "parentId", parent, "sort", 1, "isEnable", true)))).asString();
        assertThat(call("PUT", "/api/v3/system/menus/" + child, token,
                "{\"redirect\":\"/implicit-parent\"}").statusCode()).isEqualTo(400);
        assertThat(named(data(call("GET", "/api/v3/system/menus", token, null)), "ImplicitRedirectChild")
                .path("redirect").isNull()).isTrue();
    }

    @Test
    void changingSortCannotMoveARedirectAheadOfItsSafeDirectoryLandingPage() throws Exception {
        String parent = data(call("POST", "/api/v3/system/menus", token, """
                {"menuType":"menu","name":"SortRedirectParent","title":"Directory","path":"/sort-parent",
                 "component":"/index/index","sort":1,"isEnable":true}
                """)).asString();
        String first = data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(Map.of(
                "menuType", "menu", "name", "SortSafeChild", "title", "Safe", "path", "safe",
                "component", "/system/menu", "parentId", parent, "sort", 1, "isEnable", true)))).asString();
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "menuType", "menu", "name", "SortLoopChild", "title", "Loop", "path", "loop",
                "component", "/system/menu", "parentId", parent, "sort", 2, "isEnable", true));
        input.put("redirect", "/sort-parent");
        String second = data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(input))).asString();
        assertThat(call("PUT", "/api/v3/system/menus/" + second, token, "{\"sort\":0}")
                .statusCode()).isEqualTo(400);
        // Moving the safe sibling instead must not bypass the same final-state check.
        assertThat(call("PUT", "/api/v3/system/menus/" + first, token, "{\"sort\":3}")
                .statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/v3/system/menus/" + first, token, "{\"isEnable\":false}")
                .statusCode()).isEqualTo(400);
        assertThat(call("DELETE", "/api/v3/system/menus/" + first, token, null)
                .statusCode()).isEqualTo(400);
        var tree = data(call("GET", "/api/v3/system/menus", token, null));
        assertThat(named(tree, "SortSafeChild").path("meta").path("sort").asInt()).isEqualTo(1);
        assertThat(named(tree, "SortLoopChild").path("meta").path("sort").asInt()).isEqualTo(2);
    }

    String createMenu(String name, String parent) throws Exception {
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "menuType", "menu", "name", name, "title", name, "path", "/" + name.toLowerCase(java.util.Locale.ROOT),
                "component", "/system/menu", "sort", 1, "isEnable", true));
        input.put("parentId", parent);
        return data(call("POST", "/api/v3/system/menus", token, json.writeValueAsString(input))).asString();
    }

    String option(String field, String value) throws Exception {
        for (JsonNode node : data(call("GET", "/api/role/permission-options", token, null))) {
            if (value.equals(node.path(field).asString())) return node.path("id").asString();
        }
        throw new AssertionError("Missing option " + value);
    }

    String role(String code, String scope, List<String> uses, List<String> grants) throws Exception {
        return data(call("POST", "/api/role", token, json.writeValueAsString(Map.of(
                "roleCode", code, "roleName", code, "description", "", "enabled", true,
                "dataScope", scope, "customOrganizationIds", List.of(), "menuIds", uses, "grantMenuIds", grants)))).asString();
    }

    String account(String username, List<String> roles) throws Exception {
        return data(call("POST", "/api/account", token, json.writeValueAsString(Map.of(
                "username", username, "password", "test-only", "roleIds", roles)))).asString();
    }

    String access(String username) throws Exception {
        return data(call("POST", "/api/auth/login", null, json.writeValueAsString(Map.of(
                "userName", username, "password", "test-only")))).path("token").asString();
    }

    JsonNode named(JsonNode nodes, String name) {
        for (JsonNode node : nodes) {
            if (name.equals(node.path("name").asString())) return node;
            JsonNode child = named(node.path("children"), name);
            if (child != null) return child;
        }
        return null;
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
