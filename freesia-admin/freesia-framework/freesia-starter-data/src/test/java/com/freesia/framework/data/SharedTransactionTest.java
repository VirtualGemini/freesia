package com.freesia.framework.data;

import com.freesia.common.result.CursorPage;
import com.freesia.framework.data.cursor.Cursor;
import com.freesia.framework.data.cursor.CursorPages;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = SharedTransactionTest.TestApplication.class)
@Testcontainers
class SharedTransactionTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcClient jdbc;
    @Autowired ProbeMapper mapper;
    @Autowired SqlSessionFactory sqlSessionFactory;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void jdbcReadsSeeFlexWritesAndBothRollBackInOneTransaction() {
        assertThat(sqlSessionFactory.getConfiguration().getEnvironment().getDataSource()).isSameAs(dataSource);
        assertThat(transactionManager).isExactlyInstanceOf(JdbcTransactionManager.class);
        jdbc.sql("CREATE TABLE IF NOT EXISTS foundation_probe (id UUID DEFAULT uuidv7() PRIMARY KEY, name TEXT NOT NULL)").update();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            Probe probe = new Probe();
            probe.setName("rollback-probe");
            assertThat(mapper.insert(probe)).isEqualTo(1);
            assertThat(probe.getId()).as("database-generated UUID").isInstanceOf(UUID.class);
            assertThat(probe.getId().version()).isEqualTo(7);
            assertThat(jdbc.sql("SELECT name FROM foundation_probe WHERE id = :id").param("id", probe.getId()).query(String.class).single())
                    .as("JdbcClient reads the uncommitted Flex write")
                    .isEqualTo("rollback-probe");
            probe.setName("updated-probe");
            assertThat(mapper.update(probe)).as("Flex binds the UUID key when updating").isEqualTo(1);
            assertThat(jdbc.sql("SELECT name FROM foundation_probe WHERE id = :id").param("id", probe.getId()).query(String.class).single())
                    .isEqualTo("updated-probe");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM foundation_probe").query(Long.class).single()).isZero();
    }

    @Test
    void uuidCursorPagesFollowPostgresOrderWithoutLosingRowsAtTheSameTimestamp() {
        var first = cursorPage(null);
        var second = cursorPage(first.nextCursor());
        var third = cursorPage(second.nextCursor());
        var last = cursorPage(third.nextCursor());

        assertThat(List.of(first.hasMore(), second.hasMore(), third.hasMore(), last.hasMore()))
                .containsExactly(true, true, true, false);
        assertThat(first.records()).extracting(PositionedRow::id).containsExactly(
                UUID.fromString("00000000-0000-7000-8000-000000000001"),
                UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"));
        assertThat(second.records()).extracting(PositionedRow::id).containsExactly(
                UUID.fromString("80000000-0000-7000-8000-000000000001"),
                UUID.fromString("7fffffff-ffff-7fff-bfff-ffffffffffff"));
        assertThat(third.records()).extracting(PositionedRow::id).containsExactly(
                UUID.fromString("019a90f1-0000-7000-bfff-ffffffffffff"),
                UUID.fromString("019a90f1-0000-7000-8000-000000000001"));
        assertThat(last.records()).extracting(PositionedRow::id).containsExactly(
                UUID.fromString("ffffffff-ffff-7fff-bfff-fffffffffffe"));
        assertThat(Cursor.decode(first.nextCursor()).time()).isEqualTo(Instant.parse("2026-11-18T03:04:05.123456Z"));
        assertThat(last.nextCursor()).isNull();
    }

    private CursorPage<PositionedRow> cursorPage(String encoded) {
        // Read-only fixtures exercise real PostgreSQL UUID/tuple ordering without product tables.
        String sql = """
                WITH positions(occurred_at, id) AS (
                    VALUES
                        (TIMESTAMPTZ '2026-11-18 03:04:05.123456+00', UUID '7fffffff-ffff-7fff-bfff-ffffffffffff'),
                        (TIMESTAMPTZ '2026-11-18 03:04:04.123456+00', UUID 'ffffffff-ffff-7fff-bfff-fffffffffffe'),
                        (TIMESTAMPTZ '2026-11-18 03:04:05.123456+00', UUID '019a90f1-0000-7000-8000-000000000001'),
                        (TIMESTAMPTZ '2026-11-18 03:04:05.123456+00', UUID 'ffffffff-ffff-7fff-bfff-ffffffffffff'),
                        (TIMESTAMPTZ '2026-11-18 03:04:06.123456+00', UUID '00000000-0000-7000-8000-000000000001'),
                        (TIMESTAMPTZ '2026-11-18 03:04:05.123456+00', UUID '019a90f1-0000-7000-bfff-ffffffffffff'),
                        (TIMESTAMPTZ '2026-11-18 03:04:05.123456+00', UUID '80000000-0000-7000-8000-000000000001')
                )
                SELECT occurred_at, id FROM positions
                """ + (encoded == null ? "" : " WHERE (occurred_at, id) < (:time, :id)")
                + " ORDER BY occurred_at DESC, id DESC LIMIT 3";
        var query = jdbc.sql(sql);
        if (encoded != null) {
            Cursor cursor = Cursor.decode(encoded);
            query.param("time", cursor.time().atOffset(ZoneOffset.UTC)).param("id", cursor.id());
        }
        List<PositionedRow> rows = query.query((result, row) -> new PositionedRow(
                result.getTimestamp("occurred_at").toInstant(), result.getObject("id", UUID.class))).list();
        return CursorPages.of(rows, 2, row -> new Cursor(row.occurredAt(), row.id()));
    }

    private record PositionedRow(Instant occurredAt, UUID id) {}

    @SpringBootApplication
    @MapperScan(basePackageClasses = SharedTransactionTest.class, annotationClass = Mapper.class)
    static class TestApplication {}

    @Mapper
    interface ProbeMapper extends BaseMapper<Probe> {}

    @Table("foundation_probe")
    public static class Probe {
        @Id(keyType = KeyType.Auto) private UUID id;
        private String name;
        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
