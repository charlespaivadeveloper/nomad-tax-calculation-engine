-- Migration: V2__create_idempotency_record.sql
-- SPEC-TAX-006 §2.2 / SPEC-TAX-005 §3.2 — Deduplication for POST /v1/tax-calculations.

CREATE TABLE idempotency_record (
    id                  UUID            NOT NULL,
    tenant_id           UUID            NOT NULL,
    idempotency_key     VARCHAR(128)    NOT NULL,
    request_hash        CHAR(64)        NOT NULL,
    status              VARCHAR(16)     NOT NULL,
    calculation_id      UUID,
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

COMMENT ON TABLE  idempotency_record                 IS 'Deduplication records for POST /v1/tax-calculations. Status lifecycle: PROCESSING → COMMITTED | FAILED.';
COMMENT ON COLUMN idempotency_record.idempotency_key  IS 'Client-supplied; never logged at INFO level.';
COMMENT ON COLUMN idempotency_record.request_hash     IS 'SHA-256 of canonical input (SPEC-TAX-001 §4). Used to detect body mismatches.';
COMMENT ON COLUMN idempotency_record.calculation_id   IS 'Populated atomically with status=COMMITTED in Phase 2 transaction (SPEC-TAX-005 §5).';

-- Covering index: status/request_hash/calculation_id read without heap fetch
CREATE INDEX idempotency_record_covering_idx
    ON idempotency_record (tenant_id, idempotency_key)
    INCLUDE (status, request_hash, calculation_id);
