package com.freesia.admin;

import cn.dev33.satoken.SaManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FreesiaAdminApplicationTest extends IntegrationTest {
    @LocalServerPort int port;

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StringRedisTemplate valkey;
    @Autowired Flyway flyway;
    @Autowired ObjectProvider<CacheManager> cacheManagers;

    @Test
    void theStartupModuleLoadsBusinessEndpointsAndTranslationsFromTheSystemDependency() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        var product = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/product")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(product.statusCode()).isEqualTo(200);
        assertThat(product.body()).contains("\"productName\":\"Freesia\"", "\"applicationName\":\"Freesia Admin\"");
        var login = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Accept-Language", "en").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"userName\":\"unknown-assembly-account\",\"password\":\"not-a-real-password\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(400);
        assertThat(login.body()).isEqualTo("{\"code\":10001,\"msg\":\"Invalid username or password\",\"data\":null}");
        assertThat(login.headers().firstValue("X-Trace-Id")).isPresent();
    }

    @Test
    void standardCacheManagerIsAvailableForExplicitUse() {
        CacheManager manager = cacheManagers.getIfAvailable();
        assertThat(manager).as("the configured standard cache manager exists at startup").isNotNull();
        Cache cache = manager.getCache("foundation-probe");
        assertThat(cache).isNotNull();
        try {
            cache.put("key", "explicit-value");
            assertThat(cache.get("key", String.class)).isEqualTo("explicit-value");
            cache.evict("key");
            assertThat(cache.get("key")).isNull();
        } finally {
            cache.clear();
        }
    }

    @Test
    void migrationsRunAndSpringOwnsTheOnlyTransactionManager() {
        assertThat(transactionManager).isExactlyInstanceOf(JdbcTransactionManager.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'freesia'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void aFreshMigrationRunnerReusesHistoryAfterTheAccountSchemaExists() {
        var restarted = Flyway.configure().configuration(flyway.getConfiguration()).load();

        assertThat(restarted.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.sql("SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history'")
                .query(String.class).list()).containsExactly("public");
    }

    @Test
    void sessionStateIsWrittenToValkeyNotToProcessMemory() {
        SaManager.getSaTokenDao().set("freesia:foundation-probe", "session-value", 60);
        try {
            assertThat(valkey.opsForValue().get("freesia:foundation-probe")).isEqualTo("session-value");
        } finally {
            SaManager.getSaTokenDao().delete("freesia:foundation-probe");
        }
    }

    @Test
    void failuresOutsideAnyModuleStillUseTheResponseEnvelopeInTheDefaultLanguage() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/nowhere")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"code\":404,\"msg\":\"请求的资源不存在\",\"data\":null}");
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
    }

    @Test
    void applicationErrorsNegotiateRegionalLanguagesWithoutLeakingLanguageAcrossRequests() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI missing = URI.create("http://localhost:" + port + "/api/nowhere");
        var english = client.send(HttpRequest.newBuilder(missing).header("Accept-Language", "en-US").build(),
                HttpResponse.BodyHandlers.ofString());
        var chinese = client.send(HttpRequest.newBuilder(missing).header("Accept-Language", "zh,en;q=0.5").build(),
                HttpResponse.BodyHandlers.ofString());
        var unsupported = client.send(HttpRequest.newBuilder(missing).header("Accept-Language", "fr-FR").build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(english.statusCode()).isEqualTo(404);
        assertThat(english.body()).isEqualTo("{\"code\":404,\"msg\":\"Resource not found\",\"data\":null}");
        assertThat(chinese.statusCode()).isEqualTo(404);
        assertThat(chinese.body()).isEqualTo("{\"code\":404,\"msg\":\"请求的资源不存在\",\"data\":null}");
        assertThat(unsupported.body()).isEqualTo(chinese.body());
    }
}
