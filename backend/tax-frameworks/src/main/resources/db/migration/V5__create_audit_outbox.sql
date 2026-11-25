-- Migration: V5__create_audit_outbox.sql
-- SPEC-TAX-006 §2.5 / SPEC-TAX-007 §5.1 — Transactional outbox for audit events.

CREATE TABLE audit_outbox (
    id              UUID            NOT NULL,
    calculation_id  UUID            NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

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

-- Partial index: excludes published rows — supports findUnpublished() efficiently
CREATE INDEX audit_outbox_pending_idx
    ON audit_outbox (created_at ASC)
    WHERE published_at IS NULL;
