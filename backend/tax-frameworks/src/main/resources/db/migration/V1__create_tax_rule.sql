-- Migration: V1__create_tax_rule.sql
-- SPEC-TAX-006 §2.1 — Published versioned tax rules; read-only by calculation engine.
-- All rate values are SYNTHETIC and not legally certified.

CREATE TABLE tax_rule (
    id                  UUID            NOT NULL,
    tenant_id           UUID            NOT NULL,
    tax_type            VARCHAR(8)      NOT NULL,
    classification_code VARCHAR(64)     NOT NULL,
    valid_from          DATE            NOT NULL,
    valid_to            DATE,
    rate_decimal        NUMERIC(19, 9)  NOT NULL,
    snapshot_hash       CHAR(64)        NOT NULL,
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

-- Supports the half-open interval range query in SPEC-TAX-002 §4
CREATE INDEX tax_rule_resolution_idx
    ON tax_rule (tenant_id, classification_code, status, valid_from, valid_to);

-- Supports snapshot hash lookup for audit verification
CREATE INDEX tax_rule_snapshot_hash_idx
    ON tax_rule (snapshot_hash);
