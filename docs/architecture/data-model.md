# Logical data model

- `tax_rule_version(id UUID PK, tax_type, classification_code, valid_from DATE, valid_to DATE, status, rule_definition JSONB, snapshot_hash, created_at)`; published rules immutable; non-overlapping eligibility constraints (application-level plus database strategy to be specified).
- `tax_calculation(id UUID PK, tenant_id UUID, rule_version_id UUID, engine_version, input_snapshot JSONB, rule_snapshot JSONB, evidence_hash, created_at)`.
- `tax_calculation_item(id UUID PK, calculation_id UUID, tax_type, taxable_base NUMERIC(19,4), rate NUMERIC(19,9), tax_amount NUMERIC(19,2))` — scales are **proposed** and must be checked against actual requirements.
- `idempotency_record(id UUID PK, tenant_id UUID, idempotency_key, request_hash, status, calculation_id UUID, created_at)`; UNIQUE(tenant_id,idempotency_key).
- `audit_outbox(id UUID PK, calculation_id UUID, event_type, payload JSONB, created_at, published_at)`.

Use canonical serialization for hashes, half-open effective-date intervals, explicit transactional ownership, tenant-scoped queries and indexes. No JPA entities inside domain/usecases.
