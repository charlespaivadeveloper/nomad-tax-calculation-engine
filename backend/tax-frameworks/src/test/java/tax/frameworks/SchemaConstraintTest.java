package tax.frameworks;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BDD scenarios S-DB-002..S-DB-010 from SPEC-TAX-006 §10.
 * Tests CHECK, UNIQUE, FK, NUMERIC precision and JSONB constraints
 * against a real PostgreSQL schema. All fixtures are SYNTHETIC.
 */
@Testcontainers
class SchemaConstraintTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HASH_64 = "a".repeat(64);
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    @BeforeAll
    static void migrate() {
        Flyway.configure()
              .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
              .load()
              .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Inserts a valid tax_rule and returns its UUID (non-rollbacked; called in test transactions). */
    private String insertValidTaxRule(Connection c) throws SQLException {
        var id = UUID.randomUUID().toString();
        try (var ps = c.prepareStatement(
                "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                "VALUES (?::uuid,?::uuid,'CBS','GOODS_GENERAL',CURRENT_DATE,NULL,0.088000000,?,?,?,now())")) {
            ps.setString(1, id);
            ps.setString(2, TENANT_A);
            ps.setString(3, HASH_64);
            ps.setString(4, "PUBLISHED");
            ps.setString(5, "1.0.0");
            ps.executeUpdate();
        }
        return id;
    }

    // S-DB-002: tax_rule CHECK constraints enforced

    @Test
    void s_db_002_invalid_status_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                        "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'CBS','GOODS',CURRENT_DATE,NULL,0.088,?,'INVALID',?,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, HASH_64);
                    ps.setString(3, "1.0.0");
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", ex.getSQLState(),
                    "Invalid status must produce check_violation 23514");
            c.rollback();
        }
    }

    @Test
    void s_db_002_rate_zero_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                        "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'CBS','GOODS',CURRENT_DATE,NULL,0.000000000,?,'PUBLISHED',?,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, HASH_64);
                    ps.setString(3, "1.0.0");
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", ex.getSQLState(),
                    "rate_decimal = 0 must produce check_violation 23514");
            c.rollback();
        }
    }

    @Test
    void s_db_002_rate_one_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                        "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'CBS','GOODS',CURRENT_DATE,NULL,1.000000000,?,'PUBLISHED',?,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, HASH_64);
                    ps.setString(3, "1.0.0");
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", ex.getSQLState(),
                    "rate_decimal = 1 must produce check_violation 23514");
            c.rollback();
        }
    }

    @Test
    void s_db_002_valid_to_equal_valid_from_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                        "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'CBS','GOODS',CURRENT_DATE,CURRENT_DATE,0.088,?,'PUBLISHED',?,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, HASH_64);
                    ps.setString(3, "1.0.0");
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", ex.getSQLState(),
                    "valid_to = valid_from must produce check_violation 23514");
            c.rollback();
        }
    }

    @Test
    void s_db_002_invalid_snapshot_hash_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                        "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'CBS','GOODS',CURRENT_DATE,NULL,0.088,'not-hex-64','PUBLISHED',?,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, "1.0.0");
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", ex.getSQLState(),
                    "Invalid snapshot_hash must produce check_violation 23514");
            c.rollback();
        }
    }

    // S-DB-003: idempotency_record UNIQUE constraint

    @Test
    void s_db_003_duplicate_key_same_tenant_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            insertIdempotencyRecord(c, TENANT_A, "dedup-key-001");
            var ex = assertThrows(SQLException.class,
                    () -> insertIdempotencyRecord(c, TENANT_A, "dedup-key-001"));
            assertEquals("23505", ex.getSQLState(),
                    "Duplicate (tenant_id, idempotency_key) must produce unique_violation 23505");
            c.rollback();
        }
    }

    @Test
    void s_db_003_same_key_different_tenant_allowed() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            insertIdempotencyRecord(c, TENANT_A, "cross-tenant-key");
            insertIdempotencyRecord(c, TENANT_B, "cross-tenant-key");
            c.rollback();
        }
    }

    // S-DB-004: COMMITTED requires calculation_id

    @Test
    void s_db_004_committed_without_calculation_id_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO idempotency_record (id,tenant_id,idempotency_key," +
                        "request_hash,status,calculation_id,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'idem-committed-null',?,'COMMITTED',NULL,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, HASH_64);
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", ex.getSQLState(),
                    "COMMITTED with null calculation_id must produce check_violation 23514");
            c.rollback();
        }
    }

    @Test
    void s_db_004_committed_with_calculation_id_allowed() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var calcId = UUID.randomUUID().toString();
            try (var ps = c.prepareStatement(
                    "INSERT INTO idempotency_record (id,tenant_id,idempotency_key," +
                    "request_hash,status,calculation_id,created_at) " +
                    "VALUES (gen_random_uuid(),?::uuid,'idem-committed-ok',?,'COMMITTED',?::uuid,now())")) {
                ps.setString(1, TENANT_A);
                ps.setString(2, HASH_64);
                ps.setString(3, calcId);
                ps.executeUpdate();
            }
            c.rollback();
        }
    }

    // S-DB-005: tax_calculation_item FK enforced

    @Test
    void s_db_005_tax_calculation_item_fk_violation() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_calculation_item " +
                        "(id,calculation_id,tax_type,taxable_base,rate,tax_amount,sort_order) " +
                        "VALUES (gen_random_uuid(),'00000000-0000-0000-0000-000000000000'::uuid," +
                        "'CBS',1000.0000,0.088000000,88.00,0)")) {
                    ps.executeUpdate();
                }
            });
            assertEquals("23503", ex.getSQLState(),
                    "FK violation on calculation_id must produce foreign_key_violation 23503");
            c.rollback();
        }
    }

    // S-DB-006: audit_outbox FK enforced

    @Test
    void s_db_006_audit_outbox_fk_violation() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO audit_outbox (id,calculation_id,event_type,payload,created_at) " +
                        "VALUES (gen_random_uuid(),'00000000-0000-0000-0000-000000000000'::uuid," +
                        "'TAX_CALCULATION_COMPLETED','{}'::jsonb,now())")) {
                    ps.executeUpdate();
                }
            });
            assertEquals("23503", ex.getSQLState(),
                    "FK violation on calculation_id must produce foreign_key_violation 23503");
            c.rollback();
        }
    }

    // S-DB-009: NUMERIC(19,4) behaviour for excess decimal places
    // Note: PostgreSQL rounds extra fractional digits silently (documented PG behaviour).
    // Scale enforcement is at the application layer (domain: TaxComponent.taxableBase scale ≤ 4).
    // The DB-level 22003 guard is for integer-part overflow (> 15 digits), not decimal rounding.

    @Test
    void s_db_009_taxable_base_five_decimals_rounded_not_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ruleId = insertValidTaxRule(c);
            var calcId = insertValidTaxCalculation(c, ruleId);
            // PostgreSQL NUMERIC(19,4) silently rounds 100.00001 → 100.0000 (no error)
            try (var ps = c.prepareStatement(
                    "INSERT INTO tax_calculation_item " +
                    "(id,calculation_id,tax_type,taxable_base,rate,tax_amount,sort_order) " +
                    "VALUES (gen_random_uuid(),?::uuid,'CBS',100.00001,0.088000000,8.80,0)")) {
                ps.setString(1, calcId);
                ps.executeUpdate();
            }
            c.rollback();
        }
    }

    @Test
    void s_db_009_taxable_base_integer_overflow_raises_22003() throws Exception {
        // The actual DB-level 22003 guard: integer part exceeds NUMERIC(19,4) capacity (15 digits)
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ruleId = insertValidTaxRule(c);
            var calcId = insertValidTaxCalculation(c, ruleId);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_calculation_item " +
                        "(id,calculation_id,tax_type,taxable_base,rate,tax_amount,sort_order) " +
                        "VALUES (gen_random_uuid(),?::uuid,'CBS',10000000000000000.0000,0.088000000,8.80,0)")) {
                    ps.setString(1, calcId);
                    ps.executeUpdate();
                }
            });
            assertEquals("22003", ex.getSQLState(),
                    "Integer overflow in NUMERIC(19,4) must produce 22003");
            c.rollback();
        }
    }

    @Test
    void s_db_009_taxable_base_four_decimals_accepted() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ruleId = insertValidTaxRule(c);
            var calcId = insertValidTaxCalculation(c, ruleId);
            try (var ps = c.prepareStatement(
                    "INSERT INTO tax_calculation_item " +
                    "(id,calculation_id,tax_type,taxable_base,rate,tax_amount,sort_order) " +
                    "VALUES (gen_random_uuid(),?::uuid,'CBS',100.0001,0.088000000,8.80,0)")) {
                ps.setString(1, calcId);
                ps.executeUpdate();
            }
            c.rollback();
        }
    }

    // S-DB-010: JSONB rejects invalid JSON

    @Test
    void s_db_010_invalid_jsonb_rejected() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ruleId = insertValidTaxRule(c);
            var ex = assertThrows(SQLException.class, () -> {
                try (var ps = c.prepareStatement(
                        "INSERT INTO tax_calculation " +
                        "(id,tenant_id,operation_id,operation_date,currency,classification_code," +
                        "simulation_context,total_tax_amount,rule_version_id,engine_version," +
                        "input_canonical_hash,input_snapshot,rule_snapshot,evidence_hash,created_at) " +
                        "VALUES (gen_random_uuid(),?::uuid,'OP-001',CURRENT_DATE,'BRL','GOODS_GENERAL'," +
                        "'SYNTHETIC',100.00,?::uuid,'1.0.0',?,?::jsonb,'[]'::jsonb,?,now())")) {
                    ps.setString(1, TENANT_A);
                    ps.setString(2, ruleId);
                    ps.setString(3, HASH_64);
                    ps.setString(4, "not-valid-json");
                    ps.setString(5, HASH_64);
                    ps.executeUpdate();
                }
            });
            assertTrue(
                    "22032".equals(ex.getSQLState()) || "22P02".equals(ex.getSQLState()),
                    "Invalid JSON must produce 22032/22P02, got: " + ex.getSQLState());
            c.rollback();
        }
    }

    @Test
    void s_db_010_valid_jsonb_accepted() throws Exception {
        try (var c = conn()) {
            c.setAutoCommit(false);
            var ruleId = insertValidTaxRule(c);
            try (var ps = c.prepareStatement(
                    "INSERT INTO tax_calculation " +
                    "(id,tenant_id,operation_id,operation_date,currency,classification_code," +
                    "simulation_context,total_tax_amount,rule_version_id,engine_version," +
                    "input_canonical_hash,input_snapshot,rule_snapshot,evidence_hash,created_at) " +
                    "VALUES (gen_random_uuid(),?::uuid,'OP-001',CURRENT_DATE,'BRL','GOODS_GENERAL'," +
                    "'SYNTHETIC',100.00,?::uuid,'1.0.0',?,'{\"key\":\"value\"}'::jsonb,'[]'::jsonb,?,now())")) {
                ps.setString(1, TENANT_A);
                ps.setString(2, ruleId);
                ps.setString(3, HASH_64);
                ps.setString(4, HASH_64);
                ps.executeUpdate();
            }
            c.rollback();
        }
    }

    // --- helpers ---

    private void insertIdempotencyRecord(Connection c, String tenantId, String key)
            throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO idempotency_record " +
                "(id,tenant_id,idempotency_key,request_hash,status,calculation_id,created_at) " +
                "VALUES (gen_random_uuid(),?::uuid,?,?,'PROCESSING',NULL,now())")) {
            ps.setString(1, tenantId);
            ps.setString(2, key);
            ps.setString(3, HASH_64);
            ps.executeUpdate();
        }
    }

    private String insertValidTaxCalculation(Connection c, String ruleId) throws SQLException {
        var id = UUID.randomUUID().toString();
        try (var ps = c.prepareStatement(
                "INSERT INTO tax_calculation " +
                "(id,tenant_id,operation_id,operation_date,currency,classification_code," +
                "simulation_context,total_tax_amount,rule_version_id,engine_version," +
                "input_canonical_hash,input_snapshot,rule_snapshot,evidence_hash,created_at) " +
                "VALUES (?::uuid,?::uuid,'OP-001',CURRENT_DATE,'BRL','GOODS_GENERAL'," +
                "'SYNTHETIC',100.00,?::uuid,'1.0.0',?,'{}'::jsonb,'[]'::jsonb,?,now())")) {
            ps.setString(1, id);
            ps.setString(2, TENANT_A);
            ps.setString(3, ruleId);
            ps.setString(4, HASH_64);
            ps.setString(5, HASH_64);
            ps.executeUpdate();
        }
        return id;
    }
}
