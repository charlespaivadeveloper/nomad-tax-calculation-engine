package tax.frameworks;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BDD scenarios S-DB-001, S-DB-011 from SPEC-TAX-006 §10.
 * Verifies Flyway applies exactly 5 migrations, all tables and indexes exist,
 * and re-running is idempotent. Requires Docker (Testcontainers).
 * All fixtures are SYNTHETIC.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static int migrationsExecuted;

    @BeforeAll
    static void runMigrations() {
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        migrationsExecuted = result.migrationsExecuted;
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // S-DB-001: Flyway applies 5 migrations to empty DB
    @Test
    @Order(1)
    void s_db_001_five_migrations_executed() {
        assertEquals(5, migrationsExecuted,
                "Flyway must execute exactly V1..V5");
    }

    @Test
    @Order(2)
    void s_db_001_all_five_tables_exist() throws Exception {
        var expected = Set.of(
                "tax_rule", "idempotency_record", "tax_calculation",
                "tax_calculation_item", "audit_outbox");
        try (var c = conn();
             var ps = c.prepareStatement(
                     "SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'public' AND table_name = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", expected.toArray()));
            var rs = ps.executeQuery();
            var found = new HashSet<String>();
            while (rs.next()) found.add(rs.getString(1));
            assertEquals(expected, found, "All 5 tables must exist after migration");
        }
    }

    @Test
    @Order(3)
    void s_db_001_seven_named_indexes_exist() throws Exception {
        var expected = Set.of(
                "tax_rule_resolution_idx",
                "tax_rule_snapshot_hash_idx",
                "idempotency_record_covering_idx",
                "tax_calculation_tenant_id_idx",
                "tax_calculation_input_hash_idx",
                "tax_calculation_item_calculation_id_idx",
                "audit_outbox_pending_idx");
        try (var c = conn();
             var ps = c.prepareStatement(
                     "SELECT indexname FROM pg_indexes " +
                     "WHERE schemaname = 'public' AND indexname = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", expected.toArray()));
            var rs = ps.executeQuery();
            var found = new HashSet<String>();
            while (rs.next()) found.add(rs.getString(1));
            assertEquals(expected, found, "All 7 named indexes must exist after migration");
        }
    }

    @Test
    @Order(4)
    void s_db_001_flyway_history_has_five_rows() throws Exception {
        try (var c = conn();
             var ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")) {
            var rs = ps.executeQuery();
            rs.next();
            assertEquals(5, rs.getInt(1),
                    "flyway_schema_history must have exactly 5 successful rows");
        }
    }

    // S-DB-011: Flyway is idempotent on already-migrated schema
    @Test
    @Order(5)
    void s_db_011_flyway_idempotent_on_migrated_schema() {
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        assertEquals(0, result.migrationsExecuted,
                "Second Flyway run must execute 0 migrations");
        assertTrue(result.success, "Second Flyway run must report success");
    }
}
