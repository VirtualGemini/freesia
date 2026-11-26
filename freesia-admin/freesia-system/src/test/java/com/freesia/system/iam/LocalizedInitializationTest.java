package com.freesia.system.iam;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Initialization scripts are public installation artifacts, observed through real PostgreSQL. */
@Testcontainers
class LocalizedInitializationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("baseline_contract").withUsername("freesia").withPassword("baseline-test-only");

    @Test
    void bothLanguagesInstallEquivalentStructuresAndStableCodesWithLocalizedCommentsAndLabels() throws Exception {
        Map<String, List<String>> chinese = installAndInspect("zh");
        Map<String, List<String>> english = installAndInspect("en");

        assertThat(english).as("only comments, display labels and the profile language default may differ")
                .isEqualTo(chinese);
    }

    @Test
    void selectingBothAlternativesFailsInsteadOfApplyingTwoInitializationScripts() {
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("public").locations("classpath:db/migration/zh", "classpath:db/migration/en").load();
        assertThatThrownBy(() -> flyway.migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("Found more than one migration with version 1");
    }

    @Test
    void anUnknownLanguageFailsInsteadOfSilentlySkippingInitialization() {
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("public").locations("classpath:db/migration/unknown").failOnMissingLocations(true).load();
        assertThatThrownBy(() -> flyway.migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("db/migration/unknown");
    }

    private Map<String, List<String>> installAndInspect(String language) throws Exception {
        // The identifier comes only from the two literals above, never external input.
        String database = "baseline_" + language;
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl().replace("/baseline_contract", "/" + database),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = JdbcClient.create(dataSource);
        var flyway = Flyway.configure().dataSource(dataSource).defaultSchema("public")
                .locations("classpath:db/migration/" + language).failOnMissingLocations(true).load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().all()).extracting(migration -> migration.getScript())
                .containsExactly("V1__create_product_schema.sql");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM freesia.sys_account").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM freesia.sys_bootstrap WHERE purpose='first-super' AND completed_at IS NULL")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema='freesia' AND table_name='sys_profile' AND column_name='language'
                """).query(String.class).single()).startsWith("'" + language + "'");
        assertThat(jdbc.sql("SELECT role_name FROM freesia.sys_role ORDER BY role_code").query(String.class).list())
                .containsExactlyElementsOf(language.equals("en")
                        ? List.of("Super administrator")
                        : List.of("超级管理员"));
        assertThat(jdbc.sql("SELECT title FROM freesia.sys_menu WHERE name='Organization'").query(String.class).single())
                .isEqualTo(language.equals("en") ? "Organizations" : "组织管理");

        List<String> tableComments = jdbc.sql("""
                SELECT obj_description(c.oid, 'pg_class') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='freesia' AND c.relkind='r' ORDER BY c.relname
                """).query(String.class).list();
        List<String> columnComments = jdbc.sql("""
                SELECT col_description(c.oid, a.attnum)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                WHERE n.nspname='freesia' AND c.relkind='r' ORDER BY c.relname, a.attnum
                """).query(String.class).list();
        assertThat(tableComments).hasSize(14).allSatisfy(comment -> assertThat(comment).isNotBlank());
        assertThat(columnComments).hasSize(112).allSatisfy(comment -> assertThat(comment).isNotBlank());
        if (language.equals("en")) {
            assertThat(tableComments).allSatisfy(comment -> assertThat(comment.chars().allMatch(value -> value < 128)).isTrue());
            assertThat(columnComments).allSatisfy(comment -> assertThat(comment.chars().allMatch(value -> value < 128)).isTrue());
        } else {
            assertThat(jdbc.sql("SELECT col_description('freesia.sys_account'::regclass, 1)")
                    .query(String.class).single()).contains("账号主键");
        }
        var changedLanguage = Flyway.configure().dataSource(dataSource).defaultSchema("public")
                .locations("classpath:db/migration/" + (language.equals("en") ? "zh" : "en")).load();
        assertThatThrownBy(() -> changedLanguage.migrate()).isInstanceOf(FlywayException.class)
                .as("changing the baseline language is not an existing-database translation")
                .hasMessageContaining("checksum mismatch");

        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        snapshot.put("columns", jdbc.sql("""
                SELECT c.relname || '.' || a.attname || '|' || format_type(a.atttypid, a.atttypmod)
                    || '|' || a.attnotnull || '|' || CASE
                        WHEN c.relname='sys_profile' AND a.attname='language' THEN '<baseline-language>'
                        ELSE coalesce(pg_get_expr(d.adbin, d.adrelid), '') END
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
                WHERE n.nspname='freesia' AND c.relkind='r' ORDER BY c.relname, a.attnum
                """).query(String.class).list());
        snapshot.put("constraints", jdbc.sql("""
                SELECT c.relname || '|' || k.conname || '|' || pg_get_constraintdef(k.oid)
                FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='freesia' ORDER BY c.relname, k.conname
                """).query(String.class).list());
        snapshot.put("indexes", jdbc.sql("""
                SELECT indexdef FROM pg_indexes WHERE schemaname='freesia' ORDER BY indexname
                """).query(String.class).list());
        snapshot.put("roles", jdbc.sql("""
                SELECT (to_jsonb(r) - ARRAY['id','role_name','created_at','updated_at'])::text
                FROM freesia.sys_role r ORDER BY role_code
                """).query(String.class).list());
        snapshot.put("menus", jdbc.sql("""
                SELECT ((to_jsonb(m) - ARRAY['id','parent_id','title','created_at','updated_at'])
                    || jsonb_build_object('parent_name', p.name))::text
                FROM freesia.sys_menu m LEFT JOIN freesia.sys_menu p ON p.id=m.parent_id ORDER BY m.name
                """).query(String.class).list());
        snapshot.put("permissions", jdbc.sql("""
                SELECT r.role_code || '|' || m.name FROM freesia.sys_role_menu_permission p
                JOIN freesia.sys_role r ON r.id=p.role_id JOIN freesia.sys_menu m ON m.id=p.menu_id
                ORDER BY r.role_code, m.name
                """).query(String.class).list());
        snapshot.put("grant-permissions", jdbc.sql("""
                SELECT r.role_code || '|' || m.name FROM freesia.sys_role_grant_permission p
                JOIN freesia.sys_role r ON r.id=p.role_id JOIN freesia.sys_menu m ON m.id=p.menu_id ORDER BY r.role_code,m.name
                """).query(String.class).list());
        snapshot.put("organizations",jdbc.sql("""
                SELECT (to_jsonb(o) - ARRAY['id','parent_id','name','created_at','updated_at'])::text
                FROM freesia.sys_organization o ORDER BY code
                """).query(String.class).list());
        snapshot.put("access-policy", jdbc.sql("""
                SELECT (to_jsonb(p) - 'id')::text FROM freesia.sys_access_control p ORDER BY scope_key
                """).query(String.class).list());
        snapshot.put("verification-policy", jdbc.sql("""
                SELECT to_jsonb(p)::text FROM freesia.sys_verification_policy p ORDER BY scene
                """).query(String.class).list());
        return snapshot;
    }
}
