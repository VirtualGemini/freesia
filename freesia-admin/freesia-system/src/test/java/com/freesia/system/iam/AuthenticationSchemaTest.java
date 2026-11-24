package com.freesia.system.iam;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Migration contracts may be observed directly; authentication behavior is tested over HTTP. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AuthenticationSchemaTest {
    @Container
    static final PostgreSQLContainer EMPTY = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("empty_installation").withUsername("freesia").withPassword("schema-only");
    @Container
    static final org.testcontainers.containers.GenericContainer<?> VALKEY =
            new org.testcontainers.containers.GenericContainer<>("valkey/valkey:8.1.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EMPTY::getJdbcUrl);
        registry.add("spring.datasource.username", EMPTY::getUsername);
        registry.add("spring.datasource.password", EMPTY::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;

    @Test
    void theUnreleasedSchemaIsInitializedByOneMigration() {
        assertThat(flyway.info().all()).extracting(migration -> migration.getScript())
                .containsExactly("V1__create_product_schema.sql");
    }

    @Test
    void everyProductTableAndColumnHasAPersistedDatabaseComment() {
        assertThat(jdbc.sql("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='freesia' AND c.relkind='r'
                AND nullif(btrim(obj_description(c.oid, 'pg_class')), '') IS NULL
                """).query(String.class).list()).as("tables without comments").isEmpty();
        assertThat(jdbc.sql("""
                SELECT c.relname || '.' || a.attname
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                WHERE n.nspname='freesia' AND c.relkind='r'
                AND nullif(btrim(col_description(c.oid, a.attnum)), '') IS NULL
                """).query(String.class).list()).as("columns without comments").isEmpty();
    }

    @Test
    void anEmptyInstallationHasBuiltInRolesAndPoliciesButNoSeededAccountOrCredential() {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("empty_installation");
        assertThat(jdbc.sql("SELECT role_code FROM freesia.sys_role ORDER BY role_code").query(String.class).list())
                .containsExactly("R_SUPER");
        assertThat(jdbc.sql("SELECT id FROM freesia.sys_role").query(UUID.class).list())
                .allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        assertThat(jdbc.sql("SELECT count(*) FROM freesia.sys_account").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM freesia.sys_account WHERE password_hash NOT LIKE '$argon2id$%'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT access_seconds FROM freesia.sys_access_control WHERE scope_key='global'")
                .query(Integer.class).single()).isEqualTo(1800);
        assertThat(jdbc.sql("SELECT refresh_seconds FROM freesia.sys_access_control WHERE scope_key='global'")
                .query(Integer.class).single()).isEqualTo(1209600);
        assertThat(jdbc.sql("SELECT count(*) FROM freesia.sys_bootstrap WHERE purpose='first-super'")
                .query(Long.class).single()).isEqualTo(1);
    }
}
