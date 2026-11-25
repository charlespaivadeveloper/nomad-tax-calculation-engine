-- Migration: V3__create_tax_calculation.sql
-- SPEC-TAX-006 §2.3 / SPEC-TAX-007 §5.1 — Immutable calculation evidence header.

CREATE TABLE tax_calculation (
    id                   UUID            NOT NULL,
    tenant_id            UUID            NOT NULL,
    operation_id         VARCHAR(255)    NOT NULL,
    operation_date       DATE            NOT NULL,
    currency             VARCHAR(8)      NOT NULL,
    classification_code  VARCHAR(64)     NOT NULL,
    simulation_context   VARCHAR(16)     NOT NULL,
    total_tax_amount     NUMERIC(19, 2)  NOT NULL,
    rule_version_id      UUID            NOT NULL,
    engine_version       VARCHAR(64)     NOT NULL,
    input_canonical_hash CHAR(64)        NOT NULL,
    input_snapshot       JSONB           NOT NULL,
    rule_snapshot        JSONB           NOT NULL,
    evidence_hash        CHAR(64)        NOT NULL,
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
COMMENT ON COLUMN tax_calculation.rule_version_id     IS 'References the CBS rule used. See OQ-DB-003 for multi-rule consideration.';
COMMENT ON COLUMN tax_calculation.input_snapshot      IS 'Verbatim canonical JSON for tamper detection. Must match the string used to compute input_canonical_hash.';
COMMENT ON COLUMN tax_calculation.rule_snapshot       IS 'Verbatim JSON array of all TaxRuleSnapshot objects used; ordered CBS first. Immutable; enables offline audit.';
COMMENT ON COLUMN tax_calculation.evidence_hash       IS 'SHA-256 chaining inputCanonicalHash and all snapshotHash values per SPEC-TAX-007 §5.3.';

-- Supports GET /v1/tax-calculations/{id} with tenant isolation
CREATE INDEX tax_calculation_tenant_id_idx
    ON tax_calculation (tenant_id, id);

-- Supports idempotency loadResult() by input hash
CREATE INDEX tax_calculation_input_hash_idx
    ON tax_calculation (tenant_id, input_canonical_hash);
