package com.freesia.system.iam;

import com.freesia.system.SystemTestApplication;
import com.freesia.system.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"freesia.web.trusted-proxies[0]=127.0.0.1/32", "freesia.web.trusted-proxies[1]=::1/128"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordLoginTest extends IntegrationTest {
    @LocalServerPort int port;
    @TempDir static Path temporary;
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate valkey;

    @org.junit.jupiter.api.AfterEach
    void discardOnlyFailureFixtures() {
        var keys = valkey.keys("freesia:iam:password-failures:*");
        if (keys != null && !keys.isEmpty()) valkey.delete(keys);
    }

    @BeforeAll
    void bootstrapThroughTheExplicitProcessCommand() throws Exception {
        Path secret = temporary.resolve("password");
        Files.writeString(secret, "First-admin-test-password-2026!");
        Path output = temporary.resolve("bootstrap.log");
        Process first = bootstrapProcess(secret, output);
        Process concurrent = bootstrapProcess(secret, temporary.resolve("bootstrap-concurrent.log"));
        assertThat(first.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(concurrent.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(List.of(first.exitValue(),concurrent.exitValue())).containsExactlyInAnyOrder(0,1);
        Path winner = first.exitValue()==0 ? output : temporary.resolve("bootstrap-concurrent.log");
        assertThat(Files.readString(winner)).contains("Administrator initialization completed.")
                .doesNotContain("First-admin-test-password-2026!");
    }

    Process bootstrapProcess(Path secret, Path output) throws Exception {
        var command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName()));
        command.addAll(List.of(
                "bootstrap-admin", "--username=bootstrap-owner", "--password-file=" + secret,
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + VALKEY.getHost(),
                "--spring.data.redis.port=" + VALKEY.getMappedPort(6379),
                "--spring.main.banner-mode=off"));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 300})
    void nonblankPasswordsRemainUsableWithoutLengthOrCompositionChecks(int length) throws Exception {
        String original = jdbc.sql("SELECT password_hash FROM freesia.sys_account WHERE username='bootstrap-owner'")
                .query(String.class).single();
        String password = "1".repeat(length);
        var encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 4, 65536, 3);
        jdbc.sql("UPDATE freesia.sys_account SET password_hash=:hash WHERE username='bootstrap-owner'")
                .param("hash", encoder.encode(password)).update();
        try {
            var response = call("POST", "/api/auth/login", null,
                    "{\"userName\":\"bootstrap-owner\",\"password\":\"" + password + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            String token = json.readTree(response.body()).path("data").path("token").asString();
            assertThat(call("GET", "/api/account/info", token, null).statusCode()).isEqualTo(200);
            assertThat(call("POST", "/api/auth/logout", token, null).statusCode()).isEqualTo(200);
        } finally {
            jdbc.sql("UPDATE freesia.sys_account SET password_hash=:hash WHERE username='bootstrap-owner'")
                    .param("hash", original).update();
        }
    }

    @Test
    void anExplicitBootstrapCommandMakesTheFirstAdministratorAbleToSignInAndLoadRealNavigation() throws Exception {
        var response = call("POST", "/api/auth/login", null,
                "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode credentials = json.readTree(response.body()).path("data");
        String token = credentials.path("token").asString();
        assertThat(token).hasSizeGreaterThanOrEqualTo(32);
        String refresh = java.net.HttpCookie.parse(response.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getValue();
        assertThat(refresh).hasSizeGreaterThanOrEqualTo(32).isNotEqualTo(token);
        assertThat(credentials.has("refreshToken")).isFalse();
        assertThat(credentials.path("sessionId").asString()).isNotBlank().isNotEqualTo(token);
        Instant accessExpiry = Instant.parse(credentials.path("accessExpiresAt").asString());
        Instant sessionExpiry = Instant.parse(credentials.path("sessionExpiresAt").asString());
        assertThat(Duration.between(accessExpiry, sessionExpiry)).isEqualTo(Duration.ofDays(14).minusMinutes(30));

        var current = call("GET", "/api/account/info", token, null);
        assertThat(current.statusCode()).isEqualTo(200);
        JsonNode account = json.readTree(current.body()).path("data");
        assertThat(UUID.fromString(account.path("accountId").asString()).version()).isEqualTo(7);
        assertThat(account.path("roles").toString()).isEqualTo("[\"R_SUPER\"]");
        assertThat(account.path("buttons").toString()).contains("system:log:login:query");
        var permissions = call("GET", "/api/iam/permissions", token, null);
        assertThat(permissions.statusCode()).isEqualTo(200);
        assertThat(json.readTree(permissions.body()).path("data")).isEqualTo(account.path("buttons"));
        var menus = call("GET", "/api/v3/system/menus/simple", token, null);
        assertThat(menus.statusCode()).isEqualTo(200);
        assertThat(menus.body()).contains("\"name\":\"Console\"", "\"component\":\"/dashboard/console\"");
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();

        var audit = call("GET", "/api/log/login/list", token, null);
        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(audit.body()).contains("\"eventType\":\"LOGIN\"", credentials.path("sessionId").asString(), "\"username\":\"bootstrap-owner\"")
                .doesNotContain(token, refresh, "First-admin-test-password");

        var secondResponse = call("POST", "/api/auth/login", null,
                "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}");
        assertThat(secondResponse.statusCode()).isEqualTo(200);
        JsonNode second = json.readTree(secondResponse.body()).path("data");
        assertThat(second.path("sessionId").asString()).isNotEqualTo(credentials.path("sessionId").asString());
        assertThat(call("POST", "/api/auth/logout", token, null).statusCode()).isEqualTo(200);
        var ended = call("GET", "/api/account/info", token, null);
        assertThat(ended.statusCode()).isEqualTo(401);
        assertThat(json.readTree(ended.body()).path("code").asInt()).isEqualTo(10003);
        assertThat(call("GET", "/api/account/info", second.path("token").asString(), null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/log/login/list", second.path("token").asString(), null).body())
                .contains("\"eventType\":\"LOGOUT\"", credentials.path("sessionId").asString());
    }

    @Test
    void fiveFailuresPauseOnlyTheUsernameAndSourcePairAndSuccessBeforeTheLimitClearsIt() throws Exception {
        String credentials = "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}";
        for (int i=0; i<4; i++) {
            var denied = call("POST", "/api/auth/login", null, "{\"userName\":\"bootstrap-owner\",\"password\":\"wrong-password\"}");
            assertThat(denied.statusCode()).isEqualTo(400);
            assertThat(json.readTree(denied.body()).path("code").asInt()).isEqualTo(10001);
        }
        String validToken = json.readTree(call("POST", "/api/auth/login", null, credentials).body()).path("data").path("token").asString();
        assertThat(validToken).isNotBlank();
        for (int i=0; i<5; i++) call("POST", "/api/auth/login", null, "{\"userName\":\"bootstrap-owner\",\"password\":\"wrong-password\"}");
        var paused = call("POST", "/api/auth/login", null, credentials);
        assertThat(paused.statusCode()).isEqualTo(429);
        assertThat(json.readTree(paused.body()).path("code").asInt()).isEqualTo(10004);
        assertThat(call("GET", "/api/account/info", validToken, null).statusCode()).isEqualTo(200);
        var unknown = call("POST", "/api/auth/login", null, "{\"userName\":\"unknown-account\",\"password\":\"wrong-password\"}");
        assertThat(unknown.statusCode()).isEqualTo(400);
    }

    @Test
    void aValkeyOutageLeavesTheProcessAliveButUnreadyAndNeverMasqueradesAsAnInvalidSession() throws Exception {
        var login = call("POST", "/api/auth/login", null,
                "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}");
        String token = json.readTree(login.body()).path("data").path("token").asString();
        var docker = VALKEY.getDockerClient();
        docker.pauseContainerCmd(VALKEY.getContainerId()).exec();
        try {
            assertThat(call("GET", "/actuator/health/liveness", null, null).statusCode()).isEqualTo(200);
            assertThat(call("GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(503);
            var unavailable = call("GET", "/api/account/info", token, null);
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(json.readTree(unavailable.body()).path("code").asInt()).isEqualTo(503);
            assertThat(call("POST", "/api/auth/login", null,
                    "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}").statusCode()).isEqualTo(503);
        } finally { docker.unpauseContainerCmd(VALKEY.getContainerId()).exec(); }
        assertThat(call("GET", "/actuator/health/readiness", null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/account/info", token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentlyPausedPasswordAttemptsAreAllAudited() throws Exception {
        String wrong="{\"userName\":\"bootstrap-owner\",\"password\":\"incorrect\"}";
        for(int i=0;i<4;i++) assertThat(forwardedLogin(wrong,"9.9.9.9").statusCode()).isEqualTo(400);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var first=executor.submit(()->{start.await();return forwardedLogin(wrong,"9.9.9.9");});
            var second=executor.submit(()->{start.await();return forwardedLogin(wrong,"9.9.9.9");});
            start.countDown();
            assertThat(List.of(first.get().statusCode(),second.get().statusCode())).allMatch(status->status==400||status==429);
        }
        var observer=forwardedLogin("{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}","1.1.1.1");
        String token=json.readTree(observer.body()).path("data").path("token").asString();
        var records=json.readTree(call("GET","/api/log/login/list?size=100",token,null).body()).path("data").path("records");
        long failures=java.util.stream.StreamSupport.stream(records.spliterator(),false)
                .filter(node->node.path("clientIp").asString().equals("9.9.9.9")&&node.path("result").asInt()==0).count();
        assertThat(failures).isEqualTo(6);
    }

    @Test
    void aFailedThirteenthLoginNeverEvictsAnEstablishedSession() throws Exception {
        String body="{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}";
        var first=call("POST","/api/auth/login",null,body);
        String token=json.readTree(first.body()).path("data").path("token").asString();
        for(int i=0;i<12;i++) assertThat(call("POST","/api/auth/login",null,body).statusCode()).isEqualTo(200);
        jdbc.sql("ALTER TABLE freesia.sys_login_log ADD CONSTRAINT test_reject_overflow CHECK(result=0) NOT VALID").update();
        try {
            assertThat(call("POST","/api/auth/login",null,body).statusCode()).isEqualTo(503);
            assertThat(call("GET","/api/account/info",token,null).statusCode()).isEqualTo(200);
        } finally { jdbc.sql("ALTER TABLE freesia.sys_login_log DROP CONSTRAINT test_reject_overflow").update(); }
    }

    @Test
    void logoutAuditCanBeRetriedWithoutReactivatingTheRevokedCredentials() throws Exception {
        String body="{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}";
        var first=json.readTree(call("POST","/api/auth/login",null,body).body()).path("data");
        String token=first.path("token").asString();
        String observer=json.readTree(call("POST","/api/auth/login",null,body).body()).path("data").path("token").asString();
        jdbc.sql("ALTER TABLE freesia.sys_login_log ADD CONSTRAINT test_reject_logout CHECK(event_type<>'LOGOUT') NOT VALID").update();
        try {
            assertThat(call("POST","/api/auth/logout",token,null).statusCode()).isEqualTo(503);
            assertThat(call("GET","/api/account/info",token,null).statusCode()).isEqualTo(401);
        } finally { jdbc.sql("ALTER TABLE freesia.sys_login_log DROP CONSTRAINT test_reject_logout").update(); }
        assertThat(call("POST","/api/auth/logout",token,null).statusCode()).isEqualTo(200);
        var logs=json.readTree(call("GET","/api/log/login/list",observer,null).body()).path("data").path("records");
        long count=java.util.stream.StreamSupport.stream(logs.spliterator(),false)
                .filter(node->node.path("sessionId").asString().equals(first.path("sessionId").asString())
                        && node.path("eventType").asString().equals("LOGOUT")).count();
        assertThat(count).isEqualTo(1);
        assertThat(call("GET","/api/account/info",token,null).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredAccessAndRevokedRefreshStateAreDistinctAndTokensAreNotStoredAsRefreshPlaintext() throws Exception {
        var response = call("POST", "/api/auth/login", null,
                "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}");
        JsonNode data = json.readTree(response.body()).path("data");
        String token = data.path("token").asString();
        String refresh = java.net.HttpCookie.parse(response.headers().firstValue("Set-Cookie").orElseThrow()).getFirst().getValue();
        String sid = data.path("sessionId").asString();
        String sessionKey = "freesia:iam:session:" + sid;
        assertThat(valkey.opsForHash().entries(sessionKey).toString()).doesNotContain(refresh,token);
        String accessDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        valkey.opsForValue().set("freesia:iam:access:" + accessDigest,
                sid + ":" + Instant.now().minusSeconds(1).getEpochSecond(), Duration.ofDays(14));
        var expired = call("GET","/api/account/info",token,null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(json.readTree(expired.body()).path("code").asInt()).isEqualTo(10002);
        assertThat(call("POST","/api/auth/logout",token,null).statusCode()).isEqualTo(200);
        assertThat(valkey.hasKey(sessionKey)).isFalse();
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(refresh.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(valkey.hasKey("freesia:iam:refresh:" + digest)).isFalse();
    }

    @Test
    void permissionRevocationAndRoleDisablingImmediatelyAffectLiveSessionsWithoutRoleNameBypasses() throws Exception {
        var response=call("POST","/api/auth/login",null,
                "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}");
        String token=json.readTree(response.body()).path("data").path("token").asString();
        assertThat(call("GET","/api/role/options",token,null).body()).doesNotContain("R_SUPER","R_ADMIN","R_USER","roleLevel");
        jdbc.sql("""
                DELETE FROM freesia.sys_role_menu_permission WHERE role_id=(SELECT id FROM freesia.sys_role WHERE role_code='R_SUPER')
                AND menu_id=(SELECT id FROM freesia.sys_menu WHERE auth_mark='system:log:login:query')
                """).update();
        try {
            assertThat(call("GET","/api/log/login/list",token,null).statusCode()).isEqualTo(403);
        } finally {
            jdbc.sql("""
                    INSERT INTO freesia.sys_role_menu_permission SELECT r.id,m.id FROM freesia.sys_role r
                    CROSS JOIN freesia.sys_menu m WHERE r.role_code='R_SUPER' AND m.auth_mark='system:log:login:query'
                    """).update();
        }
        jdbc.sql("UPDATE freesia.sys_role SET enabled=false WHERE role_code='R_SUPER'").update();
        try {
            assertThat(call("GET","/api/log/login/list",token,null).statusCode()).isEqualTo(403);
            assertThat(call("GET","/api/v3/system/menus/simple",token,null).body()).contains("\"data\":[]");
        } finally { jdbc.sql("UPDATE freesia.sys_role SET enabled=true WHERE role_code='R_SUPER'").update(); }
    }

    @Test
    void auditWriteFailureDoesNotReturnCredentialsOrInvalidateAnExistingSession() throws Exception {
        String body = "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}";
        String token = json.readTree(call("POST", "/api/auth/login", null, body).body()).path("data").path("token").asString();
        jdbc.sql("ALTER TABLE freesia.sys_login_log ADD CONSTRAINT test_reject_success CHECK(result=0) NOT VALID").update();
        try {
            var failed = call("POST", "/api/auth/login", null, body);
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.body()).doesNotContain("\"token\"", "\"refreshToken\"", "password_hash");
            assertThat(call("GET", "/api/account/info", token, null).statusCode()).isEqualTo(200);
        } finally { jdbc.sql("ALTER TABLE freesia.sys_login_log DROP CONSTRAINT test_reject_success").update(); }
        jdbc.sql("ALTER TABLE freesia.sys_login_log ADD CONSTRAINT test_reject_failure CHECK(result=1) NOT VALID").update();
        try {
            assertThat(call("POST", "/api/auth/login", null,
                    "{\"userName\":\"bootstrap-owner\",\"password\":\"incorrect\"}").statusCode()).isEqualTo(503);
        } finally { jdbc.sql("ALTER TABLE freesia.sys_login_log DROP CONSTRAINT test_reject_failure").update(); }
    }

    @Test
    void disabledDeletedAndUnknownAccountsHaveTheSamePublicFailureAndDisabledSessionsCannotAct() throws Exception {
        String good = "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}";
        String token = json.readTree(call("POST", "/api/auth/login", null, good).body()).path("data").path("token").asString();
        var wrong = call("POST", "/api/auth/login", null, "{\"userName\":\"bootstrap-owner\",\"password\":\"incorrect\"}");
        jdbc.sql("UPDATE freesia.sys_account SET enabled=false WHERE username='bootstrap-owner'").update();
        try {
            var disabled = call("POST", "/api/auth/login", null, good);
            var missing = call("POST", "/api/auth/login", null, "{\"userName\":\"no-such-account\",\"password\":\"incorrect\"}");
            assertThat(disabled.statusCode()).isEqualTo(400);
            assertThat(disabled.body()).isEqualTo(wrong.body()).isEqualTo(missing.body());
            assertThat(call("GET", "/api/account/info", token, null).statusCode()).isEqualTo(401);
            jdbc.sql("UPDATE freesia.sys_account SET deleted=true WHERE username='bootstrap-owner'").update();
            assertThat(call("POST", "/api/auth/login", null, good).body()).isEqualTo(wrong.body());
        } finally { jdbc.sql("UPDATE freesia.sys_account SET enabled=true,deleted=false WHERE username='bootstrap-owner'").update(); }
        assertThat(call("GET", "/api/account/info", token, null).statusCode()).isEqualTo(401);
    }

    @Test
    void existingArgon2idParametersRemainValidAndMixedCostChangesNeverLowerStoredStrength() throws Exception {
        String password = "First-admin-test-password-2026!";
        var provider = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,2,131072,2);
        String fixture = provider.encode(password);
        jdbc.sql("UPDATE freesia.sys_account SET password_hash=:hash WHERE username='bootstrap-owner'").param("hash",fixture).update();
        try {
            var response = call("POST", "/api/auth/login", null,
                    "{\"userName\":\"bootstrap-owner\",\"password\":\"" + password + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            // Credential-at-rest contract: public responses deliberately never expose the hash.
            String upgraded = jdbc.sql("SELECT password_hash FROM freesia.sys_account WHERE username='bootstrap-owner'").query(String.class).single();
            assertThat(upgraded).startsWith("$argon2id$v=19$m=131072,t=3,p=4$");
            assertThat(provider.matches(password, upgraded)).isTrue();
            var repeated = call("POST", "/api/auth/login", null,
                    "{\"userName\":\"bootstrap-owner\",\"password\":\"" + password + "\"}");
            assertThat(repeated.statusCode()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT password_hash FROM freesia.sys_account WHERE username='bootstrap-owner'").query(String.class).single()).isEqualTo(upgraded);
        } finally {
            var defaults = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,4,65536,3);
            jdbc.sql("UPDATE freesia.sys_account SET password_hash=:hash WHERE username='bootstrap-owner'").param("hash",defaults.encode(password)).update();
        }
    }

    @Test
    void trustedProxyChainsDriveBothOfflineDualStackAuditLocationAndThePasswordFailurePair() throws Exception {
        String body = "{\"userName\":\"bootstrap-owner\",\"password\":\"First-admin-test-password-2026!\"}";
        var ipv4 = forwardedLogin(body, "8.8.8.8, 127.0.0.1");
        assertThat(ipv4.statusCode()).isEqualTo(200);
        var ipv6 = forwardedLogin(body, "2001:4860:4860::8888");
        assertThat(ipv6.statusCode()).isEqualTo(200);
        String token = json.readTree(ipv4.body()).path("data").path("token").asString();
        var logs = json.readTree(call("GET", "/api/log/login/list", token, null).body()).path("data").path("records");
        var v4Record = java.util.stream.StreamSupport.stream(logs.spliterator(), false)
                .filter(node -> node.path("sessionId").asString().equals(json.readTree(ipv4.body()).path("data").path("sessionId").asString())).findFirst().orElseThrow();
        var v6Record = java.util.stream.StreamSupport.stream(logs.spliterator(), false)
                .filter(node -> node.path("sessionId").asString().equals(json.readTree(ipv6.body()).path("data").path("sessionId").asString())).findFirst().orElseThrow();
        assertThat(v4Record.path("clientIp").asString()).isEqualTo("8.8.8.8");
        assertThat(v4Record.path("countryCode").asString()).isEqualTo("US");
        assertThat(v4Record.path("isp").asString()).isEqualTo("Google LLC");
        assertThat(v6Record.path("clientIp").asString()).isEqualTo("2001:4860:4860::8888");
        assertThat(v6Record.path("countryCode").asString()).isEqualTo("US");
        for (int i=0;i<5;i++) forwardedLogin("{\"userName\":\"bootstrap-owner\",\"password\":\"incorrect\"}", "8.8.8.8");
        assertThat(forwardedLogin(body, "8.8.8.8").statusCode()).isEqualTo(429);
        assertThat(forwardedLogin(body, "2001:4860:4860::8888").statusCode()).isEqualTo(200);
    }

    HttpResponse<String> forwardedLogin(String body, String chain) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Accept-Language","en").header("Content-Type","application/json")
                .header("X-Freesia-CSRF", "1")
                .header("X-Forwarded-For", chain).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Accept-Language", "en")
                .header("X-Freesia-CSRF", "1")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
