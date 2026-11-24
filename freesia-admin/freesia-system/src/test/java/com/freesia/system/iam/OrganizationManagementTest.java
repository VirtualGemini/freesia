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
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(OrganizationManagementTest.AuditFault.class)
class OrganizationManagementTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("organization_management").withUsername("freesia").withPassword("test-only");
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
    String token;
    @TestConfiguration
    static class AuditFault {
        static volatile boolean failSuccess;
        static volatile boolean failFailure;
        static volatile java.util.concurrent.CountDownLatch entered;
        static volatile java.util.concurrent.CountDownLatch release;
        @EventListener
        void audit(OperationAudit event) {
            if(event.success()&&failSuccess||!event.success()&&failFailure)throw new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE);
            if(event.success()&&event.action().equals("system:role:status")&&entered!=null) {
                entered.countDown();
                try {
                    if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Audit test timeout");
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    @BeforeAll
    void signInThroughThePublicInitializationAndLoginFlows() throws Exception {
        Path secret = temporary.resolve("initial-password");
        Files.writeString(secret, "Organization-initial-password!");
        Path output = temporary.resolve("bootstrap.log");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(), "bootstrap-admin", "--username=organization-owner",
                "--password-file=" + secret, "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(),
                "--spring.data.redis.port=" + VALKEY.getMappedPort(6379), "--spring.main.banner-mode=off");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        var login = call("POST", "/api/auth/login", null,
                "{\"userName\":\"organization-owner\",\"password\":\"Organization-initial-password!\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        token = json.readTree(login.body()).path("data").path("token").asString();
    }

    @Test
    void theInitializedOwnerHasTheExclusiveRootAndCanCreateAnOrganizationWithoutAStatus() throws Exception {
        var response = call("GET", "/api/organization/tree", token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = json.readTree(response.body()).path("data").get(0);
        assertThat(root.path("root").asBoolean()).isTrue();
        assertThat(UUID.fromString(root.path("id").asString()).version()).isEqualTo(7);
        assertThat(root.has("enabled")).isFalse();
        assertThat(root.has("status")).isFalse();
        var info = json.readTree(call("GET", "/api/account/info", token, null).body()).path("data");
        assertThat(info.path("organizationId").asString()).isEqualTo(root.path("id").asString());
        var created = call("POST", "/api/organization", token,
                "{\"parentId\":\"" + root.path("id").asString()
                        + "\",\"code\":\"RESEARCH\",\"name\":\"Research\",\"remark\":null,\"sort\":10}");
        assertThat(created.statusCode()).isEqualTo(200);
        String id = json.readTree(created.body()).path("data").asString();
        var tree = call("GET", "/api/organization/tree", token, null);
        assertThat(tree.body()).contains(id, "\"name\":\"Research\"").doesNotContain("\"enabled\"", "\"status\"");
    }

    @Test
    void theOwnerCanViewTheBuiltInRoleWithoutMakingItAssignable() throws Exception {
        var response = call("GET", "/api/role/list?roleCode=R_SUPER", token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode ownerRole = null;
        for (JsonNode role : json.readTree(response.body()).path("data").path("records")) {
            if ("R_SUPER".equals(role.path("roleCode").asString())) ownerRole = role;
        }
        assertThat(ownerRole).as("The owner's built-in role must be visible in role management").isNotNull();
        assertThat(call("GET", "/api/role/" + ownerRole.path("roleId").asString(), token, null).statusCode())
                .isEqualTo(200);
        assertThat(call("GET", "/api/role/options", token, null).body()).doesNotContain("R_SUPER");
        assertThat(call("PUT", "/api/role/" + ownerRole.path("roleId").asString() + "/status", token,
                "{\"enabled\":false}").statusCode()).isEqualTo(403);
        String target = createAccount("owner-role-protection", null);
        String roleId = ownerRole.path("roleId").asString();
        assertThat(assign(token, target, List.of(roleId), List.of()).statusCode()).isEqualTo(403);
        String owner = json.readTree(call("GET", "/api/account/info", token, null).body())
                .path("data").path("accountId").asString();
        assertThat(assign(token, owner, List.of(), List.of(roleId)).statusCode()).isEqualTo(403);
    }

    @Test
    void permissionOptionsKeepManagementActionsUnderTheirMenu() throws Exception {
        var response = call("GET", "/api/role/permission-options", token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode nodes = json.readTree(response.body()).path("data");
        for (String capability : List.of("Account", "Role", "Organization")) {
            String menuId = "";
            for (JsonNode node : nodes) {
                if (capability.equals(node.path("name").asString())) menuId = node.path("id").asString();
            }
            assertThat(menuId).isNotBlank();
            for (JsonNode node : nodes) {
                if (node.path("authMark").asString().startsWith("system:" + capability.toLowerCase(java.util.Locale.ROOT) + ":")
                        && !menuId.equals(node.path("id").asString())) {
                    assertThat(node.path("parentId").asString()).as(node.path("name").asString()).isEqualTo(menuId);
                }
            }
        }
    }

    @Test
    void anAuthenticatedAccountCanSwitchAndPersistItsOwnLanguageWithoutManagementPermissions() throws Exception {
        String account = createAccount("language-preference", null);
        String access = access("language-preference", "x");
        for (String language : List.of("en", "zh")) {
            var response = call("PUT", "/api/account/language", access, "{\"language\":\"" + language + "\"}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var info = json.readTree(call("GET", "/api/account/info", access, null).body()).path("data");
            assertThat(info.path("language").asString()).isEqualTo(language);
        }
        assertThat(call("PUT", "/api/account/language", access, "{\"language\":\"fr\"}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/account/language", access, "{\"language\":null}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/account/language", null, "{\"language\":\"en\"}").statusCode()).isEqualTo(401);
        var owner = json.readTree(call("GET", "/api/account/info", token, null).body()).path("data");
        assertThat(owner.path("accountId").asString()).isNotEqualTo(account);
        assertThat(owner.path("language").asString()).isEqualTo("zh");
    }

    @Test
    void accountCreationCanBindAnInitialRoleWithoutFailingOnImmutableRoleLists() throws Exception {
        String role = createRole("INITIAL_BINDING", "SELF", List.of("system:account:query"), List.of());
        var created = call("POST", "/api/account", token,
                "{\"username\":\"initial-role-member\",\"password\":\"x\",\"roleIds\":[\"" + role + "\"]}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String account = json.readTree(created.body()).path("data").asString();
        var detail = json.readTree(call("GET", "/api/account/" + account + "/detail-card", token, null).body());
        assertThat(detail.path("data").path("account").path("roles").get(0).path("roleId").asString()).isEqualTo(role);
        assertThat(call("GET", "/api/account/list", access("initial-role-member", "x"), null).statusCode()).isEqualTo(200);
    }

    @Test
    void organizationEditsProtectTheRootAndRejectCyclesAndReferencedDeletion() throws Exception {
        String root = json.readTree(call("GET", "/api/organization/tree", token, null).body()).path("data").get(0).path("id").asString();
        String parent = json.readTree(call("POST", "/api/organization", token, "{\"parentId\":\"" + root
                + "\",\"code\":\"OPERATIONS\",\"name\":\"Operations\",\"sort\":0}").body()).path("data").asString();
        String child = json.readTree(call("POST", "/api/organization", token, "{\"parentId\":\"" + parent
                + "\",\"code\":\"SUPPORT\",\"name\":\"Support\",\"sort\":0}").body()).path("data").asString();
        assertThat(call("PUT", "/api/organization/" + parent, token, "{\"parentId\":\"" + child
                + "\",\"code\":\"OPERATIONS\",\"name\":\"Operations\",\"sort\":0}").statusCode()).isEqualTo(409);
        assertThat(call("DELETE", "/api/organization/" + parent, token, null).statusCode()).isEqualTo(409);
        assertThat(call("DELETE", "/api/organization/" + root, token, null).statusCode()).isEqualTo(403);
        assertThat(call("PUT", "/api/organization/" + root, token,
                "{\"parentId\":null,\"code\":\"OTHER\",\"name\":\"Headquarters\",\"sort\":0}").statusCode()).isEqualTo(403);
        assertThat(call("PUT", "/api/organization/" + root, token,
                "{\"parentId\":null,\"code\":\"ROOT\",\"name\":\"Headquarters\",\"remark\":\"Owner only\",\"sort\":0}")
                .statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/api/organization/" + child, token, null).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/api/organization/" + parent, token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void aCustomRoleCarriesIndependentUseAndGrantPermissionsAndOneDataScope() throws Exception {
        var catalog = call("GET", "/api/role/permission-options", token, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        JsonNode nodes = json.readTree(catalog.body()).path("data");
        String query = "";
        for (JsonNode node : nodes) if ("system:account:query".equals(node.path("authMark").asString())) {
            query = node.path("id").asString();
        }
        assertThat(query).isNotBlank();
        var created = call("POST", "/api/role", token,
                "{\"roleCode\":\"READER\",\"roleName\":\"Reader\",\"description\":\"No grant rights\","
                        + "\"enabled\":true,\"dataScope\":\"SELF\",\"customOrganizationIds\":[],"
                        + "\"menuIds\":[\"" + query + "\"],\"grantMenuIds\":[]}");
        assertThat(created.statusCode()).isEqualTo(200);
        String role = json.readTree(created.body()).path("data").asString();
        var detail = call("GET", "/api/role/" + role, token, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode data = json.readTree(detail.body()).path("data");
        assertThat(data.path("dataScope").asString()).isEqualTo("SELF");
        assertThat(data.path("menuIds").get(0).asString()).isEqualTo(query);
        assertThat(data.path("grantMenuIds").size()).isZero();
        assertThat(data.has("level")).isFalse();
        assertThat(data.has("organizationId")).isFalse();
        assertThat(call("GET", "/api/role/list?current=1&size=10", token, null).body()).contains(role, "READER");
    }

    @Test
    void anAccountMayHaveNoRolesAndTheInitialPasswordIsNotTrimmedOrStrengthChecked() throws Exception {
        var created = call("POST", "/api/account", token,
                "{\"username\":\"zero-roles\",\"password\":\" 1 \",\"organizationId\":null,\"remark\":\"New colleague\",\"roleIds\":[]}");
        assertThat(created.statusCode()).isEqualTo(200);
        String id = json.readTree(created.body()).path("data").asString();
        assertThat(call("GET", "/api/account/list?username=zero-roles", token, null).body()).contains(id, "New colleague");
        var detail = call("GET", "/api/account/" + id + "/detail-card", token, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).doesNotContain("passwordHash", " 1 ");
        var login = call("POST", "/api/auth/login", null, "{\"userName\":\"zero-roles\",\"password\":\" 1 \"}");
        assertThat(login.statusCode()).isEqualTo(200);
        String access = json.readTree(login.body()).path("data").path("token").asString();
        assertThat(call("GET", "/api/account/info", access, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/organization/tree", access, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/account", token,
                "{\"username\":\"zero-roles\",\"password\":\"x\",\"roleIds\":[]}").statusCode()).isEqualTo(409);
    }

    @Test
    void delegatedRoleAssignmentUsesTheOrganizationScopeAndCanBeReversedWithoutTouchingOtherRoles() throws Exception {
        String root = json.readTree(call("GET", "/api/organization/tree", token, null).body()).path("data").get(0).path("id").asString();
        String research = createOrganization(root, "DELEGATION", "Delegation");
        String finance = createOrganization(root, "FINANCE", "Finance");
        String hrRole = createRole("HR", "ORG", List.of("system:account:query", "system:account:assign-role",
                "system:role:query", "system:role:create", "system:role:update", "system:organization:query"),
                List.of("system:organization:query"));
        String hr = createAccount("hr-officer", research);
        String target = createAccount("research-member", research);
        String outsider = createAccount("finance-member", finance);
        assertThat(assign(token, hr, List.of(hrRole), List.of()).statusCode()).isEqualTo(200);
        String hrAccess = access("hr-officer", "x");
        var hrList = call("GET", "/api/account/list", hrAccess, null);
        assertThat(hrList.body()).contains("research-member").doesNotContain("finance-member", "organization-owner");
        var role = call("POST", "/api/role", hrAccess,
                roleInput("TEAM_VIEW", "ORG", List.of(permission("system:organization:query")), List.of()));
        assertThat(role.statusCode()).isEqualTo(200);
        String reader = json.readTree(role.body()).path("data").asString();
        assertThat(assign(hrAccess, target, List.of(reader), List.of()).statusCode()).isEqualTo(200);
        assertThat(assign(hrAccess, outsider, List.of(reader), List.of()).statusCode()).isEqualTo(403);
        String targetAccess = access("research-member", "x");
        var visible = call("GET", "/api/organization/tree", targetAccess, null);
        assertThat(visible.statusCode()).isEqualTo(200);
        assertThat(visible.body()).contains(research).doesNotContain(finance, root);
        assertThat(assign(hrAccess, target, List.of(), List.of(reader)).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/organization/tree", targetAccess, null).statusCode()).isEqualTo(403);
    }

    @Test
    void organizationTransferIsIndependentAndCannotClearAnOrganizationRequiredByABoundRole() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String source=createOrganization(root,"HR_DEPT","Human resources");
        String destination=createOrganization(root,"PRODUCT","Product");
        String transferRole=createRole("TRANSFER","ALL",List.of("system:account:query","system:account:transfer"),List.of());
        String readerRole=createRole("RELATIVE","ORG",List.of("system:organization:query"),List.of());
        String member=createAccount("moving-member",source);
        String editorRole=createRole("PROFILE_ONLY","ALL",List.of("system:account:query","system:account:update"),List.of());
        String editor=createAccount("profile-only-editor",source);
        assertThat(assign(token,editor,List.of(editorRole),List.of()).statusCode()).isEqualTo(200);
        String editorAccess=access("profile-only-editor","x");
        assertThat(call("PUT","/api/account/"+member+"/organization",editorAccess,
                "{\"organizationId\":\""+destination+"\"}").statusCode()).isEqualTo(403);
        assertThat(json.readTree(call("GET","/api/account/"+member+"/detail-card",token,null).body())
                .path("data").path("account").path("organizationId").asString()).isEqualTo(source);
        assertThat(assign(token,member,List.of(readerRole),List.of()).statusCode()).isEqualTo(200);
        String mover=createAccount("moving-hr",source);
        assertThat(assign(token,mover,List.of(transferRole),List.of()).statusCode()).isEqualTo(200);
        String hr=access("moving-hr","x");
        assertThat(call("PUT","/api/account/"+member+"/organization",hr,"{\"organizationId\":null}").statusCode()).isEqualTo(409);
        assertThat(call("PUT","/api/account/"+mover+"/organization",hr,"{\"organizationId\":\""+destination+"\"}").statusCode()).isEqualTo(200);
        assertThat(call("PUT","/api/account/"+member+"/organization",hr,"{\"organizationId\":\""+destination+"\"}").statusCode()).isEqualTo(200);
        var detail=json.readTree(call("GET","/api/account/"+member+"/detail-card",token,null).body()).path("data");
        assertThat(detail.path("account").path("organizationId").asString()).isEqualTo(destination);
        assertThat(detail.path("account").path("roles").get(0).path("roleId").asString()).isEqualTo(readerRole);
        assertThat(call("PUT","/api/account/"+member+"/organization",hr,"{\"organizationId\":\""+root+"\"}").statusCode()).isEqualTo(403);
        assertThat(call("PUT","/api/account/"+member,hr,
                "{\"username\":\"moving-member\",\"organizationId\":\""+source+"\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void roleChangesCannotHideTheOldExcessGrantAndNullOrganizationBindingsAreProtected() throws Exception {
        String account=createAccount("role-context",null);
        String selfRole=createRole("NULL_SELF","SELF",List.of("system:account:query"),List.of());
        assertThat(assign(token,account,List.of(selfRole),List.of()).statusCode()).isEqualTo(200);
        var changed=call("PUT","/api/role/"+selfRole,token,roleInput("NULL_SELF","ORG",
                List.of(permission("system:account:query")),List.of()));
        assertThat(changed.statusCode()).isEqualTo(409);
        String builtIn=json.readTree(call("GET","/api/account/info",token,null).body()).path("data").path("accountId").asString();
        assertThat(assign(token,builtIn,List.of(),List.of(selfRole)).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/role/options",token,null).body()).doesNotContain("R_SUPER","roleLevel");
    }

    @Test
    void nonAssignableRolesAreHiddenAndEditingCannotEraseAnOldExcessPermissionToBypassTheGuard() throws Exception {
        String administrator=createRole("LIMITED_ADMIN","ALL",List.of("system:role:query","system:role:update","system:role:create"),
                List.of("system:organization:query"));
        String actor=createAccount("limited-admin",null);
        assertThat(assign(token,actor,List.of(administrator),List.of()).statusCode()).isEqualTo(200);
        String excess=createRole("EXCESS","ALL",List.of("system:organization:query"),List.of("system:account:query"));
        String limited=access("limited-admin","x");
        assertThat(call("GET","/api/role/list",limited,null).body()).doesNotContain(excess,"EXCESS");
        assertThat(call("GET","/api/role/list?roleCode=R_SUPER",limited,null).body()).doesNotContain("R_SUPER");
        assertThat(call("GET","/api/role/"+excess,limited,null).statusCode()).isEqualTo(403);
        assertThat(call("PUT","/api/role/"+excess,limited,roleInput("EXCESS","SELF",
                List.of(permission("system:organization:query")),List.of())).statusCode()).isEqualTo(403);
        var unauthorized=call("POST","/api/role",limited,roleInput("BAD_GRANT","SELF",List.of(),
                List.of(permission("system:account:query"))));
        assertThat(unauthorized.statusCode()).isEqualTo(403);
    }

    @Test
    void customOrganizationScopesAreExplicitAndAReferencedOrganizationCannotBeDeleted() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String selected=createOrganization(root,"CUSTOM_SCOPE","Selected");
        String child=createOrganization(selected,"CUSTOM_CHILD","Not automatically selected");
        var body=json.writeValueAsString(java.util.Map.of("roleCode","CUSTOM","roleName","CUSTOM","description","",
                "enabled",true,"dataScope","CUSTOM_ORGS","customOrganizationIds",List.of(selected),
                "menuIds",List.of(permission("system:organization:query")),"grantMenuIds",List.of()));
        String role=json.readTree(call("POST","/api/role",token,body).body()).path("data").asString();
        String account=createAccount("custom-scope-reader",null);
        assertThat(assign(token,account,List.of(role),List.of()).statusCode()).isEqualTo(200);
        String actor=access("custom-scope-reader","x");
        assertThat(call("GET","/api/organization/tree",actor,null).body()).contains(selected).doesNotContain(child,root);
        assertThat(call("DELETE","/api/organization/"+child,token,null).statusCode()).isEqualTo(200);
        assertThat(call("DELETE","/api/organization/"+selected,token,null).statusCode()).isEqualTo(409);
    }

    @Test
    void sharedRoleMembersExposeOnlyVisibleAccountsAndProfileRequestsCannotChangeRolesOrOrganizations() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String visible=createOrganization(root,"VISIBLE","Visible");
        String hidden=createOrganization(root,"HIDDEN","Hidden");
        String reader=createRole("SHARED_READER","ORG",List.of("system:organization:query"),List.of());
        String inspector=createRole("INSPECTOR","ORG",List.of("system:role:query","system:role:members","system:account:query","system:account:update"),
                List.of("system:organization:query"));
        String admin=createAccount("visible-inspector",visible);
        String known=createAccount("visible-member",visible);
        String secret=createAccount("hidden-member",hidden);
        assertThat(assign(token,admin,List.of(inspector),List.of()).statusCode()).isEqualTo(200);
        assertThat(assign(token,known,List.of(reader),List.of()).statusCode()).isEqualTo(200);
        assertThat(assign(token,secret,List.of(reader),List.of()).statusCode()).isEqualTo(200);
        String access=access("visible-inspector","x");
        var members=call("GET","/api/role/"+reader+"/members",access,null);
        assertThat(members.statusCode()).isEqualTo(200);
        assertThat(members.body()).contains("visible-member","\"visibleCount\":1").doesNotContain("hidden-member","\"total\"");
        assertThat(call("PUT","/api/account/"+known,access,"{\"username\":\"visible-member\",\"organizationId\":\""+hidden+"\"}").statusCode()).isEqualTo(400);
        assertThat(call("PUT","/api/account/"+known,access,"{\"username\":\"visible-member\",\"roleCodes\":[]}").statusCode()).isEqualTo(400);
        assertThat(call("PUT","/api/account/"+known,access,"{\"username\":\"visible-member\",\"remark\":\"Updated\",\"nickname\":\"Name\",\"email\":null,\"phone\":null}")
                .statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/account/"+known+"/detail-card",access,null).body()).contains(reader,"Updated");
    }

    @Test
    void failedSuccessAuditRollsBackTheBusinessChangeAndFailureAuditPreservesTheOriginalError() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String organization=createOrganization(root,"AUDIT","Before");
        try {
            AuditFault.failSuccess=true;
            var result=call("PUT","/api/organization/"+organization,token,
                    "{\"parentId\":\""+root+"\",\"code\":\"AUDIT\",\"name\":\"Must roll back\",\"sort\":0}");
            assertThat(result.statusCode()).isEqualTo(503);
        } finally { AuditFault.failSuccess=false; }
        assertThat(call("GET","/api/organization/tree",token,null).body()).contains("\"name\":\"Before\"").doesNotContain("Must roll back");
        var observations=call("GET","/api/log/operation/observations?targetId="+organization,token,null);
        assertThat(observations.statusCode()).isEqualTo(200);
        assertThat(observations.body()).contains("system:organization:update","\"success\":false","503");
        try {
            AuditFault.failFailure=true;
            assertThat(call("DELETE","/api/organization/"+root,token,null).statusCode()).isEqualTo(403);
        } finally { AuditFault.failFailure=false; }
    }

    @Test
    void concurrentDuplicateOrganizationsYieldOneSuccessAndOneConflict() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String body="{\"parentId\":\""+root+"\",\"code\":\"CONCURRENT\",\"name\":\"Concurrent\",\"sort\":0}";
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->call("POST","/api/organization",token,body).statusCode());
            var second=executor.submit(()->call("POST","/api/organization",token,body).statusCode());
            assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(200,409);
        }
    }

    @Test
    void permissionsMergePerFunctionAndStrictDescendantsExcludeTheCurrentOrganization() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String parent=createOrganization(root,"MERGE","Merge parent");
        String child=createOrganization(parent,"MERGE_CHILD","Merge child");
        String own=createRole("MERGE_OWN","ORG",List.of("system:account:query","system:organization:query"),List.of("system:organization:update"));
        String all=createRole("MERGE_ALL","ALL",List.of("system:organization:query"),List.of());
        String account=createAccount("merge-account",parent);
        assertThat(assign(token,account,List.of(own,all),List.of()).statusCode()).isEqualTo(200);
        String access=access("merge-account","x");
        var profile=json.readTree(call("GET","/api/iam/permission-profile",access,null).body()).path("data");
        assertThat(profile.path("use").path("system:account:query").path("all").asBoolean()).isFalse();
        assertThat(profile.path("use").path("system:organization:query").path("all").asBoolean()).isTrue();
        assertThat(profile.path("grant").path("system:organization:update").path("all").asBoolean()).isFalse();
        assertThat(profile.path("grant").path("system:organization:update").path("organizationIds").get(0).asString()).isEqualTo(parent);
        String strict=createRole("STRICT","SUB_ORG",List.of("system:organization:query"),List.of());
        assertThat(assign(token,account,List.of(strict),List.of(own,all)).statusCode()).isEqualTo(200);
        var tree=call("GET","/api/organization/tree",access,null);
        assertThat(tree.statusCode()).isEqualTo(200);
        assertThat(tree.body()).contains(child).doesNotContain(parent,root);
    }

    @Test
    void aVisibleOrganizationCanBeRenamedWithoutExposingItsParentAndAssignmentOptionsUseTheRecipient() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String parent=createOrganization(root,"LOCAL_EDIT","Local");
        String actorRole=createRole("LOCAL_EDITOR","ORG",List.of("system:organization:query","system:organization:update",
                "system:account:query","system:account:assign-role"),List.of("system:organization:query"));
        String actor=createAccount("local-editor",parent);
        assertThat(assign(token,actor,List.of(actorRole),List.of()).statusCode()).isEqualTo(200);
        String access=access("local-editor","x");
        assertThat(call("GET","/api/organization/tree",access,null).body()).contains(parent).doesNotContain(root);
        assertThat(call("PUT","/api/organization/"+parent,access,
                "{\"parentId\":null,\"code\":\"LOCAL_EDIT\",\"name\":\"Renamed locally\",\"sort\":0}").statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/organization/tree",access,null).body()).contains("Renamed locally");
        String reader=createRole("LOCAL_READER","ORG",List.of("system:organization:query"),List.of());
        assertThat(call("GET","/api/role/options?organizationId="+parent,access,null).body()).contains(reader);
        assertThat(call("GET","/api/role/options?organizationSelected=true",access,null).body()).doesNotContain(reader);
    }

    @Test
    void editingCannotUseASeparateWiderWritePermissionToBypassTheRoleStatusScope() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String org=createOrganization(root,"STATUS_TEST","Status");
        String admin=createRole("STATUS_ADMIN","ALL",List.of("system:role:query","system:role:update"),
                List.of("system:organization:query"));
        String status=createRole("STATUS_SELF","SELF",List.of("system:role:status"),List.of());
        String actor=createAccount("status-admin",org);
        assertThat(assign(token,actor,List.of(admin,status),List.of()).statusCode()).isEqualTo(200);
        String target=createRole("STATUS_TARGET","ALL",List.of("system:organization:query"),List.of());
        String access=access("status-admin","x");
        assertThat(call("PUT","/api/role/"+target+"/status",access,"{\"enabled\":false}").statusCode()).isEqualTo(403);
        String changed=roleInput("STATUS_TARGET","ALL",List.of(permission("system:organization:query")),List.of())
                .replace("\"enabled\":true","\"enabled\":false");
        assertThat(call("PUT","/api/role/"+target,access,changed).statusCode()).isEqualTo(403);
    }

    @Test
    void aManagementWriteWaitingBehindRevocationMustRecheckTheNewPermissions() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String org=createOrganization(root,"RACE","Race");
        String role=createRole("RACE_CREATOR","ORG",List.of("system:organization:create"),List.of());
        String actor=createAccount("racing-creator",org);
        assertThat(assign(token,actor,List.of(role),List.of()).statusCode()).isEqualTo(200);
        String access=access("racing-creator","x");
        AuditFault.entered=new java.util.concurrent.CountDownLatch(1);
        AuditFault.release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var revoke=executor.submit(()->call("PUT","/api/role/"+role+"/status",token,"{\"enabled\":false}"));
            assertThat(AuditFault.entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var mutate=executor.submit(()->call("POST","/api/organization",access,
                    "{\"parentId\":\""+org+"\",\"code\":\"RACE_FORBIDDEN\",\"name\":\"Must not exist\",\"sort\":0}"));
            Thread.sleep(100);
            assertThat(mutate.isDone()).isFalse();
            AuditFault.release.countDown();
            assertThat(revoke.get().statusCode()).isEqualTo(200);
            assertThat(mutate.get().statusCode()).isEqualTo(403);
        } finally {
            AuditFault.release.countDown();AuditFault.entered=null;AuditFault.release=null;
        }
        assertThat(call("GET","/api/organization/tree",token,null).body()).doesNotContain("RACE_FORBIDDEN");
    }

    @Test
    void accountQueryStaysInTheOrganizationWhenAnotherRoleGrantsAllFileQueries() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String org=createOrganization(root,"FIXED_EXAMPLE","Fixed example");
        String roleA=createRole("FIXED_A","ORG",List.of("system:account:query","system:file:query"),List.of());
        String roleB=createRole("FIXED_B","ALL",List.of("system:file:query"),List.of());
        String account=createAccount("fixed-example-account",org);
        assertThat(assign(token,account,List.of(roleA,roleB),List.of()).statusCode()).isEqualTo(200);
        String access=access("fixed-example-account","x");
        var profile=json.readTree(call("GET","/api/iam/permission-profile",access,null).body()).path("data").path("use");
        assertThat(profile.path("system:account:query").path("all").asBoolean()).isFalse();
        assertThat(profile.path("system:account:query").path("organizationIds").get(0).asString()).isEqualTo(org);
        assertThat(profile.path("system:file:query").path("all").asBoolean()).isTrue();
        assertThat(call("GET","/api/account/list",access,null).body()).contains("fixed-example-account").doesNotContain("organization-owner");
    }

    @Test
    void roleGrantEditingAndSelfScopeCandidatesUseTheSameGuardsAsSubmission() throws Exception {
        String org=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String team=createOrganization(org,"SECOND_REVIEW","Review");
        String edit=createRole("GRANT_EDITOR","ALL",List.of("system:role:query","system:role:update","system:account:assign-role"),
                List.of("system:organization:query","system:organization:update"));
        String narrow=createRole("NARROW_GRANT_EDIT","SELF",List.of("system:role:permission"),List.of());
        String actor=createAccount("grant-editor",team);
        assertThat(assign(token,actor,List.of(edit,narrow),List.of()).statusCode()).isEqualTo(200);
        String target=createRole("GRANT_EDIT_TARGET","ALL",List.of("system:organization:query"),List.of());
        String access=access("grant-editor","x");
        String payload=roleInput("GRANT_EDIT_TARGET","ALL",
                List.of(permission("system:organization:query"),permission("system:organization:update")),List.of());
        assertThat(call("PUT","/api/role/"+target+"/permissions",access,payload).statusCode()).isEqualTo(403);
        assertThat(call("PUT","/api/role/"+target,access,payload).statusCode()).isEqualTo(403);

        String selfGrant=createRole("SELF_GRANT","SELF",List.of("system:account:query","system:account:assign-role"),
                List.of("system:organization:query"));
        String self=createAccount("self-grant-account",team);
        assertThat(assign(token,self,List.of(selfGrant),List.of()).statusCode()).isEqualTo(200);
        String selfReader=createRole("ONLY_SELF_READER","SELF",List.of("system:organization:query"),List.of());
        String selfAccess=access("self-grant-account","x");
        assertThat(call("GET","/api/role/options?accountId="+self,selfAccess,null).body()).contains(selfReader);
        assertThat(assign(selfAccess,self,List.of(selfReader),List.of()).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/role/options?organizationId="+team,selfAccess,null).body()).doesNotContain(selfReader);
    }

    @Test
    void assignmentTargetRangeDoesNotReplaceTheGrantedFunctionsOwnScope() throws Exception {
        String root=json.readTree(call("GET","/api/organization/tree",token,null).body()).path("data").get(0).path("id").asString();
        String team=createOrganization(root,"CANDIDATE_PARITY","Candidate parity");
        String local=createRole("LOCAL_ASSIGN","ORG",List.of("system:account:assign-role"),List.of());
        String grant=createRole("ALL_FILE_GRANT","ALL",List.of(),List.of("system:file:query"));
        String reader=createRole("ALL_FILE_READER","ALL",List.of("system:file:query"),List.of());
        String actor=createAccount("parity-actor",team);
        String target=createAccount("parity-target",team);
        assertThat(assign(token,actor,List.of(local,grant),List.of()).statusCode()).isEqualTo(200);
        String access=access("parity-actor","x");
        var candidates=call("GET","/api/role/options?accountId="+target,access,null);
        assertThat(candidates.statusCode()).isEqualTo(200);
        assertThat(candidates.body()).contains(reader);
        assertThat(assign(access,target,List.of(reader),List.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void accountTimeFiltersBindUtcInstantsAndExcludeFutureRecordsWithoutReturningServerErrors() throws Exception {
        assertThat(call("GET","/api/account/list?createTimeStart=2099-01-01T00:00:00Z",token,null).statusCode()).isEqualTo(200);
        var result=json.readTree(call("GET","/api/account/list?updateTimeStart=2099-01-01T00:00:00Z",token,null).body()).path("data");
        assertThat(result.path("total").asLong()).isZero();
        assertThat(result.path("records").size()).isZero();
        var past=call("GET","/api/account/list?createTimeStart=2000-01-01T00:00:00Z&createTimeEnd=2099-01-01T00:00:00Z",token,null);
        assertThat(past.statusCode()).isEqualTo(200);
        assertThat(past.body()).contains("organization-owner");
    }

    String createOrganization(String parent, String code, String name) throws Exception {
        var result=call("POST","/api/organization",token,"{\"parentId\":\""+parent+"\",\"code\":\""+code+"\",\"name\":\""+name+"\",\"sort\":0}");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        return json.readTree(result.body()).path("data").asString();
    }
    String permission(String code) throws Exception {
        for(JsonNode node:json.readTree(call("GET","/api/role/permission-options",token,null).body()).path("data")) {
            if(code.equals(node.path("authMark").asString()))return node.path("id").asString();
        }
        throw new IllegalArgumentException("Missing permission "+code);
    }
    String roleInput(String code,String scope,List<String> menus,List<String> grants) throws Exception {
        return json.writeValueAsString(java.util.Map.of("roleCode",code,"roleName",code,"description","",
                "enabled",true,"dataScope",scope,"customOrganizationIds",List.of(),"menuIds",menus,"grantMenuIds",grants));
    }
    String createRole(String code,String scope,List<String> menus,List<String> grants) throws Exception {
        var use=new java.util.ArrayList<String>();for(String p:menus)use.add(permission(p));
        var grant=new java.util.ArrayList<String>();for(String p:grants)grant.add(permission(p));
        var result=call("POST","/api/role",token,roleInput(code,scope,use,grant));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        return json.readTree(result.body()).path("data").asString();
    }
    String createAccount(String name,String organization) throws Exception {
        var result=call("POST","/api/account",token,"{\"username\":\""+name+"\",\"password\":\"x\",\"roleIds\":[],\"organizationId\":"
                +(organization==null?"null":"\""+organization+"\"")+"}");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        return json.readTree(result.body()).path("data").asString();
    }
    String access(String name,String password) throws Exception {
        var result=call("POST","/api/auth/login",null,json.writeValueAsString(java.util.Map.of("userName",name,"password",password)));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        return json.readTree(result.body()).path("data").path("token").asString();
    }
    HttpResponse<String> assign(String actor,String target,List<String> add,List<String> remove) throws Exception {
        return call("PUT","/api/account/"+target+"/roles",actor,
                json.writeValueAsString(java.util.Map.of("addRoleIds",add,"removeRoleIds",remove)));
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
