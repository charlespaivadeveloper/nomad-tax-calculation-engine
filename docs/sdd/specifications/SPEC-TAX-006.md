# SPEC-TAX-006 — PostgreSQL Schema and Flyway Migrations

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-003 (Design PostgreSQL and Flyway)  
**Last updated:** 2026-11-23  
**Requirements:** RF-005, RF-007, RF-008, RNF-001, RNF-004, RNF-005, RNF-006  
**Related specs:** SPEC-TAX-002 (rule resolution query), SPEC-TAX-005 (idempotency table), SPEC-TAX-007 (calculation and outbox tables)  
**All fixtures are SYNTHETIC. No fiscal compliance is implied.**

---

## 1. Purpose

Define the complete PostgreSQL schema for the tax calculation engine: all tables, column types, constraints (PRIMARY KEY, UNIQUE, FOREIGN KEY, CHECK, NOT NULL), indexes, and the Flyway migration plan. This spec is the normative DDL reference; the actual migration files must match it exactly. Any schema change requires a new migration file — no manual DDL against a running environment.

Five tables are in scope:

| Table | Owned by spec | Purpose |
|---|---|---|
| `tax_rule` | SPEC-TAX-002 (resolved by this spec) | Published versioned tax rules; read-only by this engine |
| `idempotency_record` | SPEC-TAX-005 §3.2 | Deduplication for POST /v1/tax-calculations |
| `tax_calculation` | SPEC-TAX-007 §5.1 (extended here) | Immutable calculation evidence header |
| `tax_calculation_item` | SPEC-TAX-007 §5.1 | Per-component calculation amounts |
| `audit_outbox` | SPEC-TAX-007 §5.1 | At-least-once audit event delivery |

---

## 2. Table definitions (normative DDL)

All tables use `UUID` primary keys, explicit `NOT NULL` constraints, and `TIMESTAMPTZ` for temporal columns. `NUMERIC` is used for all monetary and rate values — no `FLOAT`, `REAL`, or `DOUBLE PRECISION`.

### 2.1 `tax_rule`

This table is populated by an administrative process outside the scope of this engine. The engine reads from it; it does not insert or update rows through any path specified in this spec.

```sql
-- Migration: V1__create_tax_rule.sql

CREATE TABLE tax_rule (
    id                  UUID            NOT NULL,
    tenant_id           UUID            NOT NULL,
    tax_type            VARCHAR(8)      NOT NULL,
    classification_code VARCHAR(64)     NOT NULL,
    valid_from          DATE            NOT NULL,
    valid_to            DATE,                          -- NULL = open-ended (no expiry)
    rate_decimal        NUMERIC(19, 9)  NOT NULL,
    snapshot_hash       CHAR(64)        NOT NULL,      -- SHA-256 hex; see SPEC-TAX-002 §5
    status              VARCHAR(16)     NOT NULL,
    engine_version      VARCHAR(64)     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT tax_rule_pk
        PRIMARY KEY (id),

    CONSTRAINT tax_rule_tax_type_check
        CHECK (tax_type IN ('CBS', 'IBS')),

    CONSTRAINT tax_rule_status_check
        CHECK (status IN ('PUBLISHED', 'DRAFT', 'SUPERSEDED')),

    CONSTRAINT tax_rule_rate_range_check
        CHECK (rate_decimal > 0 AND rate_decimal < 1),

    CONSTRAINT tax_rule_valid_period_check
        CHECK (valid_to IS NULL OR valid_to > valid_from),

    CONSTRAINT tax_rule_snapshot_hash_format_check
        CHECK (snapshot_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE  tax_rule                IS 'Published versioned tax rules; read-only by calculation engine. SYNTHETIC rates only.';
COMMENT ON COLUMN tax_rule.valid_to       IS 'Exclusive upper bound of the half-open period [valid_from, valid_to). NULL = no expiry.';
COMMENT ON COLUMN tax_rule.snapshot_hash  IS 'SHA-256 of canonical rule fields per SPEC-TAX-002 §5. Immutable after INSERT.';
COMMENT ON COLUMN tax_rule.status         IS 'Only PUBLISHED rules are eligible for resolution. DRAFT and SUPERSEDED are excluded.';
```

### 2.2 `idempotency_record`

Specified in SPEC-TAX-005 §3.2. Reproduced here as the normative DDL.

```sql
-- Migration: V2__create_idempotency_record.sql

CREATE TABLE idempotency_record (
    id                  UUID            NOT NULL,
    tenant_id           UUID            NOT NULL,
    idempotency_key     VARCHAR(128)    NOT NULL,
    request_hash        CHAR(64)        NOT NULL,      -- SHA-256 hex of canonical input
    status              VARCHAR(16)     NOT NULL,
    calculation_id      UUID,                          -- NULL until COMMITTED
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_record_pk
        PRIMARY KEY (id),

    CONSTRAINT idempotency_record_tenant_key_unique
        UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT idempotency_record_status_check
        CHECK (status IN ('PROCESSING', 'COMMITTED', 'FAILED')),

    CONSTRAINT idempotency_record_request_hash_format_check
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT idempotency_record_committed_has_calculation_check
        CHECK (
            status <> 'COMMITTED'
            OR calculation_id IS NOT NULL
        )
);

COMMENT ON TABLE  idempotency_record                IS 'Deduplication records for POST /v1/tax-calculations. Status lifecycle: PROCESSING → COMMITTED | FAILED.';
COMMENT ON COLUMN idempotency_record.idempotency_key IS 'Client-supplied; never logged at INFO level.';
COMMENT ON COLUMN idempotency_record.request_hash    IS 'SHA-256 of canonical input (SPEC-TAX-001 §4). Used to detect body mismatches.';
COMMENT ON COLUMN idempotency_record.calculation_id  IS 'Populated atomically with status=COMMITTED in Phase 2 transaction (SPEC-TAX-005 §5).';
```

### 2.3 `tax_calculation`

Extends the data shape in SPEC-TAX-007 §5.1 with columns required for `loadById()` to reconstruct `CalculationResponse` without parsing the JSONB blobs.

```sql
-- Migration: V3__create_tax_calculation.sql

CREATE TABLE tax_calculation (
    id                   UUID            NOT NULL,
    tenant_id            UUID            NOT NULL,
    operation_id         VARCHAR(255)    NOT NULL,
    operation_date       DATE            NOT NULL,
    currency             VARCHAR(8)      NOT NULL,
    classification_code  VARCHAR(64)     NOT NULL,
    simulation_context   VARCHAR(16)     NOT NULL,
    total_tax_amount     NUMERIC(19, 2)  NOT NULL,
    rule_version_id      UUID            NOT NULL,     -- id of the CBS TaxRuleSnapshot used
    engine_version       VARCHAR(64)     NOT NULL,
    input_canonical_hash CHAR(64)        NOT NULL,     -- SPEC-TAX-001 §4; enables hash lookup
    input_snapshot       JSONB           NOT NULL,     -- verbatim canonical input JSON (SPEC-TAX-007 §5.2)
    rule_snapshot        JSONB           NOT NULL,     -- verbatim rule snapshot JSON array (SPEC-TAX-007 §5.2)
    evidence_hash        CHAR(64)        NOT NULL,     -- SPEC-TAX-007 §5.3
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT tax_calculation_pk
        PRIMARY KEY (id),

    CONSTRAINT tax_calculation_rule_version_fk
        FOREIGN KEY (rule_version_id) REFERENCES tax_rule (id),

    CONSTRAINT tax_calculation_currency_check
        CHECK (currency IN ('BRL')),

    CONSTRAINT tax_calculation_simulation_context_check
        CHECK (simulation_context IN ('SYNTHETIC')),

    CONSTRAINT tax_calculation_total_tax_non_negative_check
        CHECK (total_tax_amount >= 0),

    CONSTRAINT tax_calculation_input_canonical_hash_format_check
        CHECK (input_canonical_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT tax_calculation_evidence_hash_format_check
        CHECK (evidence_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE  tax_calculation                     IS 'Immutable evidence header for each calculation. Append-only after commit (SPEC-TAX-007 INV-OUT-006).';
COMMENT ON COLUMN tax_calculation.rule_version_id     IS 'References the CBS rule used. If only IBS applies in a future variant, this column references IBS. See OQ-DB-003.';
COMMENT ON COLUMN tax_calculation.input_snapshot      IS 'Verbatim canonical JSON for tamper detection. Must match the string used to compute input_canonical_hash.';
COMMENT ON COLUMN tax_calculation.rule_snapshot       IS 'Verbatim JSON array of all TaxRuleSnapshot objects used; ordered CBS first. Immutable; enables offline audit.';
COMMENT ON COLUMN tax_calculation.evidence_hash       IS 'SHA-256 chaining inputCanonicalHash and all snapshotHash values per SPEC-TAX-007 §5.3.';
```

### 2.4 `tax_calculation_item`

One row per `TaxComponent` within a calculation. FK to `tax_calculation`.

```sql
-- Migration: V4__create_tax_calculation_item.sql

CREATE TABLE tax_calculation_item (
    id              UUID            NOT NULL,
    calculation_id  UUID            NOT NULL,
    tax_type        VARCHAR(8)      NOT NULL,
    taxable_base    NUMERIC(19, 4)  NOT NULL,
    rate            NUMERIC(19, 9)  NOT NULL,
    tax_amount      NUMERIC(19, 2)  NOT NULL,
    sort_order      SMALLINT        NOT NULL,           -- 0 = CBS, 1 = IBS; enforces CBS-before-IBS ordering

    CONSTRAINT tax_calculation_item_pk
        PRIMARY KEY (id),

    CONSTRAINT tax_calculation_item_calculation_fk
        FOREIGN KEY (calculation_id) REFERENCES tax_calculation (id),

    CONSTRAINT tax_calculation_item_tax_type_check
        CHECK (tax_type IN ('CBS', 'IBS')),

    CONSTRAINT tax_calculation_item_taxable_base_positive_check
        CHECK (taxable_base > 0),

    CONSTRAINT tax_calculation_item_rate_range_check
        CHECK (rate > 0 AND rate < 1),

    CONSTRAINT tax_calculation_item_tax_amount_non_negative_check
        CHECK (tax_amount >= 0),

    CONSTRAINT tax_calculation_item_sort_order_check
        CHECK (sort_order >= 0)
);

COMMENT ON TABLE  tax_calculation_item            IS 'Per-component tax amounts within a calculation. Ordered by sort_order ascending.';
COMMENT ON COLUMN tax_calculation_item.sort_order IS 'Deterministic ordering: CBS=0, IBS=1. Derived from TaxType.ordinal(). Query must ORDER BY sort_order ASC.';
COMMENT ON COLUMN tax_calculation_item.rate       IS 'Stored at scale 9 (NUMERIC(19,9)); matches TaxRate domain value.';
```

### 2.5 `audit_outbox`

Specified in SPEC-TAX-007 §5.1. FK to `tax_calculation`. Written in the same Phase 2 transaction.

```sql
-- Migration: V5__create_audit_outbox.sql

CREATE TABLE audit_outbox (
    id              UUID            NOT NULL,
    calculation_id  UUID            NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,                        -- NULL = pending; set after at-least-once delivery

    CONSTRAINT audit_outbox_pk
        PRIMARY KEY (id),

    CONSTRAINT audit_outbox_calculation_fk
        FOREIGN KEY (calculation_id) REFERENCES tax_calculation (id),

    CONSTRAINT audit_outbox_event_type_check
        CHECK (event_type IN ('TAX_CALCULATION_COMPLETED'))
);

COMMENT ON TABLE  audit_outbox              IS 'Transactional outbox for audit events. At-least-once delivery; published_at set by background publisher (SPEC-TAX-007 §6).';
COMMENT ON COLUMN audit_outbox.published_at IS 'NULL = unpublished. Set atomically in a separate short transaction after delivery. Never set in Phase 2 calculation transaction.';
COMMENT ON COLUMN audit_outbox.payload      IS 'AuditEventPayload JSON (SPEC-TAX-007 §4). Must not contain idempotency_key or JWT fragments.';
```

---

## 3. Index definitions

All indexes are created in the same migration file as their table (DDL shown here as a consolidated reference).

### 3.1 `tax_rule` indexes

```sql
-- Supports the half-open interval range query in SPEC-TAX-002 §4:
-- WHERE tenant_id = ? AND classification_code = ? AND status = 'PUBLISHED'
--   AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
CREATE INDEX tax_rule_resolution_idx
    ON tax_rule (tenant_id, classification_code, status, valid_from, valid_to);

-- Supports snapshot hash lookup for audit verification:
CREATE INDEX tax_rule_snapshot_hash_idx
    ON tax_rule (snapshot_hash);
```

### 3.2 `idempotency_record` indexes

```sql
-- The UNIQUE constraint already creates an index on (tenant_id, idempotency_key).
-- A covering index is added so reserve() reads status/request_hash/calculation_id
-- without a table heap fetch:
CREATE INDEX idempotency_record_covering_idx
    ON idempotency_record (tenant_id, idempotency_key)
    INCLUDE (status, request_hash, calculation_id);
```

### 3.3 `tax_calculation` indexes

```sql
-- Supports GET /v1/tax-calculations/{id} with tenant isolation:
CREATE INDEX tax_calculation_tenant_id_idx
    ON tax_calculation (tenant_id, id);

-- Supports idempotency loadResult() by input hash (future optimisation):
CREATE INDEX tax_calculation_input_hash_idx
    ON tax_calculation (tenant_id, input_canonical_hash);
```

### 3.4 `tax_calculation_item` indexes

```sql
-- Supports JOIN from tax_calculation → items; must ORDER BY sort_order:
CREATE INDEX tax_calculation_item_calculation_id_idx
    ON tax_calculation_item (calculation_id, sort_order);
```

### 3.5 `audit_outbox` indexes

```sql
-- Supports findUnpublished(): WHERE published_at IS NULL ORDER BY created_at ASC LIMIT N
-- Partial index omits already-published rows, keeping it small as the table grows:
CREATE INDEX audit_outbox_pending_idx
    ON audit_outbox (created_at ASC)
    WHERE published_at IS NULL;
```

---

## 4. Foreign key constraints summary

| FK | Child column | References | On delete |
|---|---|---|---|
| `tax_calculation_rule_version_fk` | `tax_calculation.rule_version_id` | `tax_rule.id` | RESTRICT (default) |
| `tax_calculation_item_calculation_fk` | `tax_calculation_item.calculation_id` | `tax_calculation.id` | RESTRICT (default) |
| `audit_outbox_calculation_fk` | `audit_outbox.calculation_id` | `tax_calculation.id` | RESTRICT (default) |

`RESTRICT` is chosen because all data is append-only and must not be deleted without a formal data-retention process (OQ-DB-004). There is no CASCADE DELETE in this schema. `idempotency_record.calculation_id` is intentionally not constrained by FK (the record may exist in PROCESSING or FAILED state before `tax_calculation` is inserted).

---

## 5. Flyway migration plan

### 5.1 Naming convention

```
V{N}__{description_in_snake_case}.sql
```

- `V` prefix — versioned migration (applied once; checksum stored)
- `{N}` — integer, monotonically increasing; no gaps
- Double underscore separator
- Description: lowercase snake_case, action-first (e.g., `create_`, `add_`, `drop_`)

Repeatable migrations (`R__`) are not used in MVP.

### 5.2 Planned migration files

| File | Tables affected | Notes |
|---|---|---|
| `V1__create_tax_rule.sql` | `tax_rule` | Includes CHECK constraints and `tax_rule_resolution_idx`, `tax_rule_snapshot_hash_idx` |
| `V2__create_idempotency_record.sql` | `idempotency_record` | Includes UNIQUE constraint and `idempotency_record_covering_idx` |
| `V3__create_tax_calculation.sql` | `tax_calculation` | Includes FK to `tax_rule`, CHECK constraints, `tax_calculation_tenant_id_idx`, `tax_calculation_input_hash_idx` |
| `V4__create_tax_calculation_item.sql` | `tax_calculation_item` | Includes FK to `tax_calculation`, `tax_calculation_item_calculation_id_idx` |
| `V5__create_audit_outbox.sql` | `audit_outbox` | Includes FK to `tax_calculation`, partial `audit_outbox_pending_idx` |

### 5.3 Flyway configuration requirements

| Property | Value | Rationale |
|---|---|---|
| `spring.flyway.locations` | `classpath:db/migration` | Standard Spring Boot convention |
| `spring.flyway.validate-on-migrate` | `true` | Reject checksum-modified migrations |
| `spring.flyway.out-of-order` | `false` | Strictly sequential; no out-of-order migrations in production |
| `spring.flyway.baseline-on-empty` | `false` | Schema must start from empty DB |
| `spring.flyway.schemas` | `public` | MVP uses default schema; namespace per OQ-DB-005 |
| `spring.flyway.table` | `flyway_schema_history` | Default; do not override |

Flyway migration files must be placed at `src/main/resources/db/migration/` in the `tax-frameworks` module.

---

## 6. Transaction isolation level

| Scenario | Isolation level | Rationale |
|---|---|---|
| Phase 1 — idempotency reserve | `READ COMMITTED` | UNIQUE-constraint INSERT is atomic; no serializable reads needed |
| Phase 2 — calculation commit | `READ COMMITTED` | All writes are INSERT; no SELECT-for-update on shared rows; idempotency commit asserts `rowsUpdated == 1` |
| Phase 2 — idempotency commit UPDATE | `READ COMMITTED` | Conditional UPDATE (`WHERE status = 'PROCESSING'`) is atomic; `rowsUpdated == 0` triggers abort |
| Background publisher — findUnpublished | `READ COMMITTED` | Read-only; at-least-once semantics tolerate re-reads |
| Background publisher — markPublished | `READ COMMITTED` | Single-row UPDATE; short transaction |

No table-level locks. No `SELECT FOR UPDATE` on `tax_rule` (read-only lookup; optimistic reads sufficient). The serialization anomaly in Phase 2 is handled by the `rowsUpdated == 1` assertion on the idempotency UPDATE, not by SERIALIZABLE isolation.

---

## 7. NUMERIC precision reference

| Column | Type | Corresponds to domain type |
|---|---|---|
| `tax_rule.rate_decimal` | NUMERIC(19, 9) | `TaxRate.value` — scale 9, range (0, 1) |
| `tax_calculation.total_tax_amount` | NUMERIC(19, 2) | `Money.amount` — scale 2 |
| `tax_calculation_item.taxable_base` | NUMERIC(19, 4) | `TaxComponent.taxableBase` — scale 4 |
| `tax_calculation_item.rate` | NUMERIC(19, 9) | `TaxComponent.rate` — scale 9 |
| `tax_calculation_item.tax_amount` | NUMERIC(19, 2) | `TaxComponent.amount.amount()` — scale 2 |

Maximum value for NUMERIC(19, 4): `999,999,999,999,999.9999` — 15 integer digits + 4 decimal. This matches SPEC-TAX-003 §8 OQ-DE-003.

---

## 8. Invariants

| ID | Invariant |
|---|---|
| INV-DB-001 | Every migration file is immutable after first application to any environment; modifications require a new versioned migration |
| INV-DB-002 | No `FLOAT`, `REAL`, or `DOUBLE PRECISION` columns exist in any table; all monetary and rate values use `NUMERIC` |
| INV-DB-003 | `tax_calculation` and `audit_outbox` rows are append-only; the only allowed post-commit write is `audit_outbox.published_at` (SPEC-TAX-007 INV-OUT-006) |
| INV-DB-004 | `idempotency_record.calculation_id` is populated only when `status = 'COMMITTED'`; enforced by CHECK constraint |
| INV-DB-005 | `tax_calculation.simulation_context` is always `'SYNTHETIC'`; enforced by CHECK constraint |
| INV-DB-006 | `tax_rule` rows that the engine reads must have `status = 'PUBLISHED'`; enforced by the gateway query filter, not a DB constraint on reads |
| INV-DB-007 | The partial index on `audit_outbox (created_at) WHERE published_at IS NULL` automatically excludes published rows; `findUnpublished()` must not add additional filters for `published_at IS NULL` |
| INV-DB-008 | FKs are RESTRICT; no CASCADE DELETE exists; row deletion requires an out-of-band data-retention process (OQ-DB-004) |
| INV-DB-009 | `tax_calculation_item.sort_order` is set by the gateway at INSERT time; `sort_order = 0` for CBS, `1` for IBS; query must include `ORDER BY sort_order ASC` |
| INV-DB-010 | `snapshot_hash` and `evidence_hash` columns are `CHAR(64)` and have a CHECK enforcing the `^[0-9a-f]{64}$` regex pattern |

---

## 9. Failure modes

| Trigger | DB behaviour | Application response |
|---|---|---|
| INSERT into `idempotency_record` with duplicate `(tenant_id, idempotency_key)` | Unique constraint violation (`SQLSTATE 23505`) | Gateway catches; derives `IdempotencyOutcome` from existing row (SPEC-TAX-005 §3.1) |
| INSERT into `tax_calculation_item` with non-existent `calculation_id` | FK violation (`SQLSTATE 23503`) | Should not occur in correct Phase 2 flow; if it does, Phase 2 rolls back; `DEPENDENCY_UNAVAILABLE` → 503 |
| INSERT with `rate_decimal = 0` or `>= 1` in `tax_rule` | CHECK violation (`SQLSTATE 23514`) | Administrative error; not reachable via calculation engine |
| INSERT into `tax_calculation_item` with `taxable_base` having 5 decimal places | NUMERIC precision error (`SQLSTATE 22003`) | Should not occur; domain validates scale ≤ 4 before INSERT; if it does, Phase 2 rolls back |
| INSERT with invalid JSON into `input_snapshot` or `rule_snapshot` | JSONB parse error (`SQLSTATE 22032`) | Should not occur; gateway assembles valid JSON; if it does, Phase 2 rolls back |
| `idempotency_record` COMMITTED but `calculation_id IS NULL` | CHECK violation (`SQLSTATE 23514`) | Prevents invalid COMMITTED state; correct gateway always sets `calculation_id` before COMMIT |
| DB unreachable during any INSERT | Connection error | Interactor catches; returns `DEPENDENCY_UNAVAILABLE` → 503 |
| Flyway migration fails (e.g., syntax error in SQL file) | Application context fails to start | Fix migration file and redeploy; never edit already-applied migrations |
| Flyway detects checksum mismatch on existing migration | `FlywayValidateException` at startup | Do not edit applied migrations; create a new migration instead |

---

## 10. BDD acceptance scenarios

Scenarios in this spec test DDL constraints, index coverage, and Flyway behaviour. All data is **SYNTHETIC**.

### Scenario S-DB-001 — Flyway applies 5 migrations to empty DB

```
Given  an empty PostgreSQL database
When   the application starts with Flyway enabled
Then   Flyway applies V1, V2, V3, V4, V5 in order without errors
  And  flyway_schema_history contains 5 rows with success = true
  And  all 5 tables exist: tax_rule, idempotency_record, tax_calculation,
         tax_calculation_item, audit_outbox
  And  all 7 indexes exist as named in §3
```

### Scenario S-DB-002 — `tax_rule` CHECK constraints enforced

```
When   INSERT INTO tax_rule with status = 'INVALID'
Then   SQLSTATE 23514 (check_violation); no row inserted

When   INSERT INTO tax_rule with rate_decimal = 0.000000000
Then   SQLSTATE 23514; no row inserted

When   INSERT INTO tax_rule with rate_decimal = 1.000000000
Then   SQLSTATE 23514; no row inserted

When   INSERT INTO tax_rule with valid_to = valid_from  (not strictly after)
Then   SQLSTATE 23514; no row inserted

When   INSERT INTO tax_rule with snapshot_hash = 'not-hex-64-chars'
Then   SQLSTATE 23514; no row inserted
```

### Scenario S-DB-003 — `idempotency_record` UNIQUE constraint enforced

```
Given  idempotency_record row: (tenant_id=T1, idempotency_key='k1', status='PROCESSING')
When   INSERT INTO idempotency_record (tenant_id=T1, idempotency_key='k1', status='PROCESSING')
Then   SQLSTATE 23505 (unique_violation); second row not inserted

When   INSERT INTO idempotency_record (tenant_id=T2, idempotency_key='k1', status='PROCESSING')
         (different tenant, same key)
Then   Insert succeeds; two rows exist with the same idempotency_key but different tenant_id
```

### Scenario S-DB-004 — `idempotency_record` COMMITTED requires `calculation_id`

```
When   INSERT INTO idempotency_record
         (status='COMMITTED', calculation_id=NULL, ...)
Then   SQLSTATE 23514 (check_violation); row not inserted

When   INSERT INTO idempotency_record
         (status='COMMITTED', calculation_id=<valid-UUID>, ...)
Then   Insert succeeds
```

### Scenario S-DB-005 — `tax_calculation_item` FK enforced

```
When   INSERT INTO tax_calculation_item
         (calculation_id = '00000000-0000-0000-0000-000000000000', ...)
         where no tax_calculation row exists with that id
Then   SQLSTATE 23503 (foreign_key_violation); no item row inserted
```

### Scenario S-DB-006 — `audit_outbox` FK enforced

```
When   INSERT INTO audit_outbox
         (calculation_id = '00000000-0000-0000-0000-000000000000', ...)
         where no tax_calculation row exists with that id
Then   SQLSTATE 23503; no outbox row inserted
```

### Scenario S-DB-007 — `tax_rule` half-open interval query uses index

```
Given  10,000 tax_rule rows inserted across various tenants, dates, and codes
When   EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM tax_rule
         WHERE tenant_id = ? AND classification_code = ? AND status = 'PUBLISHED'
           AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
Then   Query plan shows Index Scan or Bitmap Heap Scan using tax_rule_resolution_idx
  And  no Seq Scan on tax_rule
```

### Scenario S-DB-008 — `audit_outbox` partial index covers findUnpublished query

```
Given  1,000 audit_outbox rows of which 800 have published_at IS NOT NULL
When   EXPLAIN (ANALYZE) SELECT * FROM audit_outbox
         WHERE published_at IS NULL ORDER BY created_at ASC LIMIT 50
Then   Query plan uses audit_outbox_pending_idx (partial index)
  And  estimated rows scanned ≈ 200 (not 1,000)
```

### Scenario S-DB-009 — NUMERIC precision rejects excess decimal places

```
When   INSERT INTO tax_calculation_item (taxable_base = 100.00001, ...)
         (5 decimal places; NUMERIC(19,4) allows at most 4)
Then   SQLSTATE 22003 (numeric_value_out_of_range); no row inserted

When   INSERT INTO tax_calculation_item (taxable_base = 100.0001, ...)
         (4 decimal places; within NUMERIC(19,4))
Then   Insert succeeds
```

### Scenario S-DB-010 — JSONB columns reject invalid JSON

```
When   INSERT INTO tax_calculation (input_snapshot = 'not-valid-json', ...)
Then   SQLSTATE 22032 (invalid_json_text) or 22P02; no row inserted

When   INSERT INTO tax_calculation (input_snapshot = '{"key": "value"}', ...)
Then   Insert succeeds
```

### Scenario S-DB-011 — Flyway is idempotent on already-migrated schema

```
Given  all 5 migrations have been applied (flyway_schema_history has 5 rows)
When   the application restarts and Flyway runs again
Then   Flyway detects 0 pending migrations; no SQL is executed
  And  application starts successfully
  And  flyway_schema_history still contains exactly 5 rows
```

### Scenario S-DB-012 — Flyway rejects checksum-modified migration

```
Given  V1__create_tax_rule.sql has been applied
  And  the file content of V1__create_tax_rule.sql is modified after application
When   the application restarts
Then   Flyway throws FlywayValidateException at startup
  And  application context does not start
  And  the original schema is unchanged
```

---

## 11. Integration test requirements

All tests require a real PostgreSQL instance (Testcontainers with the same PostgreSQL version as production — see OQ-DB-001).

| ID | What to verify |
|---|---|
| IT-DB-001 | Flyway applies V1–V5 to a fresh Testcontainers PostgreSQL without errors; all tables and indexes exist after migration |
| IT-DB-002 | `tax_rule_resolution_idx` is used by the EXPLAIN plan for the SPEC-TAX-002 §4 half-open interval query (no Seq Scan on a table with ≥ 1,000 rows) |
| IT-DB-003 | `UNIQUE(tenant_id, idempotency_key)` on `idempotency_record` is enforced: concurrent INSERTs from 50 threads with the same key result in exactly one success and 49 unique-constraint violations |
| IT-DB-004 | Phase 2 atomicity: a forced rollback mid-INSERT leaves no orphaned `tax_calculation_item` or `audit_outbox` rows |
| IT-DB-005 | `tax_calculation_item.taxable_base` NUMERIC(19,4) rejects a value with 5 decimal places at the DB level (not only at application level) |
| IT-DB-006 | `audit_outbox.payload` JSONB column rejects a string that is not valid JSON |
| IT-DB-007 | Partial index `audit_outbox_pending_idx` correctly excludes published rows: after `published_at` is set, the row does not appear in `SELECT … WHERE published_at IS NULL` |

---

## 12. Architecture constraints

| Constraint | Detail |
|---|---|
| Migration file location | `tax-frameworks` module: `src/main/resources/db/migration/` |
| No manual DDL | All schema changes must go through Flyway migration files; no `psql` commands executed against running environments |
| No ORM-generated DDL | `spring.jpa.hibernate.ddl-auto = validate` (never `create`, `create-drop`, or `update`); Flyway owns the schema |
| NUMERIC only | No `FLOAT`, `REAL`, `DOUBLE PRECISION` for monetary or rate columns; enforced by review and ArchUnit (no JPA `@Column` with `columnDefinition = "FLOAT"`) |
| Tenant isolation | Every SELECT on `tax_calculation` and `tax_rule` must include `tenant_id = ?` as a predicate; no cross-tenant reads; enforced by integration test IT-DB-003 and SPEC-TAX-002 INV-RR-002 |
| FK on tax_rule | `tax_calculation.rule_version_id` has a FK to `tax_rule.id`; gateway implementation must insert the CBS rule id (see OQ-DB-003 for multi-rule consideration) |
| sort_order | `tax_calculation_item` rows must always be queried with `ORDER BY sort_order ASC`; no unordered reads of items |

---

## 13. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-DB-001 | Confirm target PostgreSQL major version for production (e.g., 16); Testcontainers must use the same version | SRE |
| OQ-DB-002 | Confirm that `tax_rule` is managed entirely outside this engine (pre-seeded by admin); if this engine needs a CREATE/UPDATE rule endpoint, additional tables and migrations are required | Domain owner |
| OQ-DB-003 | Confirm `tax_calculation.rule_version_id` references only the CBS rule (current spec); if multi-rule reference is needed, a join table `tax_calculation_rule` is required (mentioned in SPEC-TAX-007 OQ-OUT-005) | Domain owner |
| OQ-DB-004 | Define retention period and deletion strategy for `tax_calculation`, `audit_outbox`, and `idempotency_record` rows under LGPD; agree whether deletion or anonymisation is the approach and when a data-retention migration is needed | Legal / Compliance |
| OQ-DB-005 | Confirm single `public` schema is sufficient for MVP, vs. a tenant-specific schema-per-tenant isolation strategy | Architect / SRE |

---

## 14. Traceability

| Requirement | Covered by |
|---|---|
| RF-005 (persist evidence) | §2.3 `tax_calculation` table with `input_snapshot`, `rule_snapshot`, `evidence_hash`; §2.4 `tax_calculation_item` |
| RF-007 (idempotency) | §2.2 `idempotency_record` table, UNIQUE constraint in §4 |
| RF-008 (query) | §3.3 `tax_calculation_tenant_id_idx`; `loadById()` query path |
| RNF-001 (determinism) | §6 transaction isolation; §7 NUMERIC types prevent float rounding |
| RNF-004 (tenant isolation) | §12 tenant predicate constraint; §3 tenant-scoped indexes on `tax_rule` and `tax_calculation` |
| RNF-005 (auditability) | §2.3 `input_snapshot`, `rule_snapshot`, `evidence_hash` columns; INV-DB-003 (append-only) |
| RNF-006 (modularity) | §12 — migration files in `tax-frameworks`; schema owned by one module |
| ADR-003 (versioned rules) | §2.1 `tax_rule` immutable content columns; `snapshot_hash` CHECK constraint |
| ADR-004 (PostgreSQL) | §1 — entire spec |
| ADR-005 (transactional outbox) | §2.5 `audit_outbox` table; §4 FK to `tax_calculation` |
| SPEC-TAX-002 §4 | §3.1 `tax_rule_resolution_idx`; §10 S-DB-007 (index coverage scenario) |
| SPEC-TAX-005 §3.2 | §2.2 `idempotency_record` DDL (normative) |
| SPEC-TAX-007 §5.1 | §2.3–2.5 DDL (normative and extended) |
| TAX-003 | This document |

Test evidence: to be attached after G3. BDD scenarios S-DB-001 … S-DB-012 map to methods in `SchemaConstraintTest` and `FlywayMigrationTest` (Testcontainers, `tax-frameworks` module). Integration tests IT-DB-001 … IT-DB-007 form a `DatabaseIntegrationTest` class.
