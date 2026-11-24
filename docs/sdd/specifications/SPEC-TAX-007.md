# SPEC-TAX-007 — Audit outbox and evidence persistence

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-008 (Persist evidence and outbox)  
**Last updated:** 2026-11-23  
**Requirements:** RF-005, RF-009, RNF-005, RNF-006  
**Related specs:** SPEC-TAX-001 §4 (canonical hash), SPEC-TAX-005 §5 (Phase 2 transaction — outbox is co-located there)  
**All fixtures in this document are SYNTHETIC. No fiscal compliance is implied.**

---

## 1. Purpose

Specify two coupled concerns that share a single commit transaction:

1. **Evidence persistence** — storing the canonical input snapshot, resolved rule snapshot, evidence hash, engine version, and all computed items in `tax_calculation` and `tax_calculation_item` so that any future replay or audit can reconstruct and verify the original calculation without re-querying mutable data.

2. **Audit outbox** — atomically writing a `TAX_CALCULATION_COMPLETED` event to `audit_outbox` in the same transaction, then delivering it to consumers via a background publisher with at-least-once semantics.

Both must succeed or fail together. A calculation may never be committed without its outbox record, and an outbox record may never exist without a matching committed calculation.

---

## 2. Concepts

| Term | Definition |
|---|---|
| **Evidence persistence** | The write of `tax_calculation`, `tax_calculation_item` rows capturing the immutable record of what was computed and from which inputs and rules |
| **Input snapshot** | The canonical JSON string used to derive `inputCanonicalHash` (SPEC-TAX-001 §4); stored verbatim in `tax_calculation.input_snapshot` |
| **Rule snapshot** | A JSON array of all `TaxRuleSnapshot` objects used in the calculation; stored verbatim in `tax_calculation.rule_snapshot`; immutable after write |
| **Evidence hash** | SHA-256 derived from `inputCanonicalHash` and all `snapshotHash` values; allows tamper detection without re-reading source rules |
| **Audit event** | A structured record in `audit_outbox` representing the completion of a calculation; published at-least-once to downstream consumers |
| **At-least-once delivery** | The publisher may deliver an event more than once; consumers must be idempotent on `calculationId` |
| **Pending event** | An `audit_outbox` row with `published_at IS NULL` |

---

## 3. Persistence gateway interface (use-case boundary)

Lives in `tax-usecases`, package `tax.usecases.port`. Implemented in `tax-frameworks`. No Spring, JPA, or SQL types in the interface.

### `CalculationPersistenceGateway`

```
saveWithAudit(record: CalculationRecord): void
```
Called **inside** Phase 2 transaction (SPEC-TAX-005 §5). Atomically writes:
- one `tax_calculation` row
- one or more `tax_calculation_item` rows (one per `TaxComponent`)
- one `audit_outbox` row with `published_at = NULL`

Must be called in the same `@Transactional` scope as `IdempotencyGateway.commit()`.  
Throws `PersistenceException` on any DB error; the calling interactor aborts the transaction.

```
loadById(tenantId: UUID, calculationId: UUID): Optional<CalculationRecord>
```
Loads a fully hydrated `CalculationRecord` for the given tenant. Returns `Optional.empty()` if not found or if `calculationId` belongs to a different tenant (non-disclosing).

### `AuditOutboxGateway`

```
findUnpublished(limit: int): List<PendingAuditEvent>
```
Called by the background publisher. Returns up to `limit` outbox rows with `published_at IS NULL`, ordered ascending by `created_at`.  
`limit` must be in range `[1, 1000]`.

```
markPublished(outboxId: UUID): void
```
Sets `published_at = now()` for the given outbox record.  
Idempotent: a no-op if `published_at` is already set.  
Called in its own short transaction, independent of any calculation transaction.

---

## 4. Use-case types

### `CalculationRecord`

Assembled by the interactor from `CalculationResult` (SPEC-TAX-003) and `TaxOperationInput` (SPEC-TAX-001). Crosses the persistence boundary.

| Field | Java type | Constraint |
|---|---|---|
| `calculationId` | `UUID` | not null; stable; equals `CalculationResult.calculationId` |
| `tenantId` | `UUID` | not null; from `TaxOperationInput.tenantId` |
| `operationId` | `String` | not null; from `TaxOperationInput.operationId.value()` |
| `operationDate` | `LocalDate` | not null |
| `currency` | `Currency` | not null |
| `classificationCode` | `String` | not null |
| `simulationContext` | `SimulationContext` | always `SYNTHETIC` |
| `engineVersion` | `String` | not null; from configuration |
| `ruleVersionId` | `UUID` | not null; equals `TaxRuleSnapshot.id` of the primary rule |
| `inputSnapshot` | `String` | not null; the canonical JSON from SPEC-TAX-001 §4 (verbatim) |
| `ruleSnapshot` | `String` | not null; the canonical rule JSON (see §5.2) |
| `evidenceHash` | `String` | not null; 64-char lowercase hex SHA-256 (see §5.3) |
| `components` | `List<TaxComponent>` | non-empty; from `CalculationComponents` |
| `totalTax` | `Money` | not null |
| `inputCanonicalHash` | `String` | not null; from SPEC-TAX-001 §4 |
| `createdAt` | `Instant` | set by the gateway at INSERT time (not by the interactor) |

### `AuditEventPayload`

The JSONB payload stored in `audit_outbox.payload` and published to consumers.

| Field | Type | Source |
|---|---|---|
| `calculationId` | UUID string | `CalculationRecord.calculationId` |
| `tenantId` | UUID string | `CalculationRecord.tenantId` |
| `operationId` | String | `CalculationRecord.operationId` |
| `operationDate` | ISO-8601 date string | `CalculationRecord.operationDate` |
| `currency` | String | `"BRL"` |
| `classificationCode` | String | `CalculationRecord.classificationCode` |
| `simulationContext` | String | `"SYNTHETIC"` — always |
| `engineVersion` | String | `CalculationRecord.engineVersion` |
| `ruleVersionId` | UUID string | `CalculationRecord.ruleVersionId` |
| `totalTax` | String (decimal) | `CalculationRecord.totalTax.amount().toPlainString()` |
| `evidenceHash` | String (64-char hex) | `CalculationRecord.evidenceHash` |
| `calculatedAt` | ISO-8601 instant string | gateway-set `createdAt` |

**Fields explicitly excluded from payload:** `idempotencyKey`, any JWT fragment, raw `taxableBase` components per-rule (included only at aggregate `totalTax`), rate values, internal DB row IDs other than `calculationId`.

### `PendingAuditEvent`

Returned by `findUnpublished()`. Infrastructure type; must not appear in `tax-usecases` or `tax-domain`.

| Field | Type |
|---|---|
| `outboxId` | UUID |
| `calculationId` | UUID |
| `eventType` | String |
| `payloadJson` | String (raw JSONB) |
| `createdAt` | Instant |

---

## 5. Data shapes and hash algorithms

### 5.1 Database rows

**`tax_calculation`**

| Column | DB type | Value |
|---|---|---|
| `id` | UUID PK | `calculationId` |
| `tenant_id` | UUID | `tenantId` |
| `rule_version_id` | UUID | `ruleVersionId` (primary rule; if two rules, the CBS rule id) |
| `engine_version` | VARCHAR | `engineVersion` |
| `input_snapshot` | JSONB | verbatim canonical input JSON (see §5.2) |
| `rule_snapshot` | JSONB | verbatim rule snapshot JSON (see §5.2) |
| `evidence_hash` | CHAR(64) | lowercase hex SHA-256 (see §5.3) |
| `created_at` | TIMESTAMPTZ | set by DB `now()` on INSERT |

**`tax_calculation_item`** (one row per `TaxComponent`)

| Column | DB type | Value |
|---|---|---|
| `id` | UUID PK | generated |
| `calculation_id` | UUID FK | `calculationId` |
| `tax_type` | VARCHAR | `component.taxType.name()` |
| `taxable_base` | NUMERIC(19,4) | `component.taxableBase` |
| `rate` | NUMERIC(19,9) | `component.rate` |
| `tax_amount` | NUMERIC(19,2) | `component.amount.amount()` |

**`audit_outbox`**

| Column | DB type | Value |
|---|---|---|
| `id` | UUID PK | generated |
| `calculation_id` | UUID | `calculationId` |
| `event_type` | VARCHAR(64) | `"TAX_CALCULATION_COMPLETED"` |
| `payload` | JSONB | `AuditEventPayload` serialised to JSON |
| `created_at` | TIMESTAMPTZ | set by DB `now()` on INSERT |
| `published_at` | TIMESTAMPTZ | NULL until published |

---

### 5.2 Canonical snapshot formats

**Input snapshot** (identical to the string used to compute `inputCanonicalHash` in SPEC-TAX-001 §4):

```json
{
  "classificationCode": "<ClassificationCode.value>",
  "currency": "<Currency.name>",
  "operationDate": "<LocalDate ISO-8601>",
  "operationId": "<OperationId.value>",
  "simulationContext": "SYNTHETIC",
  "taxableBase": "<BigDecimal.toPlainString()>",
  "tenantId": "<UUID lowercase>"
}
```

Keys are in ascending alphabetical order. No whitespace between tokens. Encoded as UTF-8.

**Rule snapshot** (one element per resolved `TaxRuleSnapshot`, array sorted by `TaxType.ordinal()`):

```json
{
  "rules": [
    {
      "id": "<UUID lowercase>",
      "taxType": "<TaxType.name>",
      "classificationCode": "<ClassificationCode.value>",
      "validFrom": "<LocalDate ISO-8601>",
      "validTo": "<LocalDate ISO-8601 or null>",
      "rateDecimal": "<TaxRate.value toPlainString scale 9>",
      "snapshotHash": "<64-char hex>",
      "engineVersion": "<String>"
    }
  ]
}
```

Keys within each rule object are in ascending alphabetical order. No whitespace between tokens. Array is sorted CBS before IBS. Encoded as UTF-8.

---

### 5.3 Evidence hash algorithm

The `evidenceHash` ties the input to the rules used, enabling offline tamper detection.

**Step 1 — Rule aggregate hash:**

If one rule:
```
ruleAggregateHash = rules[0].snapshotHash
```

If two rules (CBS and IBS):
```
ruleAggregateInput = {
  "cbs": "<cbsSnapshotHash>",
  "ibs": "<ibsSnapshotHash>"
}  (no whitespace, keys alphabetical)
ruleAggregateHash = SHA-256(UTF-8(ruleAggregateInput))  → 64-char hex
```

**Step 2 — Evidence hash:**
```
evidenceInput = {
  "inputHash": "<inputCanonicalHash>",
  "ruleHash":  "<ruleAggregateHash>"
}  (no whitespace, keys alphabetical: inputHash before ruleHash)
evidenceHash = SHA-256(UTF-8(evidenceInput))  → 64-char hex
```

This is deterministic: the same calculation always produces the same `evidenceHash`. Any modification to the stored `input_snapshot` or `rule_snapshot` will produce a different hash, detectable by re-running the algorithm.

---

## 6. Background publisher

The publisher is a framework-layer component (a scheduled Spring `@Scheduled` bean or equivalent). It must not exist in `tax-domain` or `tax-usecases`.

**Polling loop (normative):**

```
every PUBLISHER_INTERVAL_SECONDS (default: 10):
  events ← auditOutboxGateway.findUnpublished(limit = PUBLISHER_BATCH_SIZE)  // default batch: 50
  for each event in events:
    try:
      publish(event)                       // deliver to target (see §6.1)
      auditOutboxGateway.markPublished(event.outboxId)
    catch PublishException:
      log.warn("outbox publish failed for {}", event.outboxId)
      // leave published_at NULL; next poll will retry
```

The publisher loop runs in a **separate transaction** per `markPublished()` call, not wrapping the whole batch. If `markPublished` fails, the event stays unpublished.

**Failure isolation:** one event's publish failure must not block subsequent events in the same batch.

### 6.1 Publish target (MVP)

For MVP, the publish target is a structured log entry at INFO level, plus a call to any registered `AuditEventConsumer` beans. The `AuditEventConsumer` interface is defined in `tax-frameworks` and is optional. This keeps the publisher testable without an external broker.

```
interface AuditEventConsumer {
    void consume(UUID outboxId, UUID calculationId, String payloadJson);
}
```

The structured log entry must include `outboxId`, `calculationId`, and `eventType`; it must not log `payloadJson` at INFO level (payload may contain commercially sensitive values). `payloadJson` may be logged at DEBUG level only.

### 6.2 At-least-once guarantee

| Scenario | Behaviour |
|---|---|
| `publish()` succeeds, `markPublished()` fails | Event remains pending; re-delivered on next poll cycle. Consumer must be idempotent on `calculationId`. |
| `publish()` fails | Event remains pending; retried on next poll cycle. |
| `markPublished()` called twice (race) | No-op on second call (idempotent). |
| Application restart with pending events | On restart, `findUnpublished()` picks up all rows with `published_at IS NULL`; delivery resumes. |

---

## 7. Invariants

| ID | Invariant |
|---|---|
| INV-OUT-001 | A `tax_calculation` row must never exist without a corresponding `audit_outbox` row in the same DB commit |
| INV-OUT-002 | An `audit_outbox` row must never exist without a corresponding `tax_calculation` row |
| INV-OUT-003 | `evidenceHash` stored in `tax_calculation` must equal the result of re-running §5.3 with the same `input_snapshot` and `rule_snapshot` values |
| INV-OUT-004 | `tax_calculation.input_snapshot` must equal the canonical JSON that produced `CalculationResult.inputCanonicalHash` |
| INV-OUT-005 | `audit_outbox.payload.simulationContext` must always be `"SYNTHETIC"` |
| INV-OUT-006 | No row in `tax_calculation` or `audit_outbox` may be mutated after commit (append-only); `published_at` in `audit_outbox` is the only allowed post-commit write |
| INV-OUT-007 | `audit_outbox.payload` must not contain the `idempotency_key` or any JWT fragment |
| INV-OUT-008 | The publisher's `markPublished()` is a separate, committed transaction; it must not share the Phase 2 calculation transaction |
| INV-OUT-009 | `findUnpublished()` must never return rows where `published_at IS NOT NULL` |
| INV-OUT-010 | Recomputing `evidenceHash` from `tax_calculation.input_snapshot` and `rule_snapshot` must always produce `tax_calculation.evidence_hash` |

---

## 8. Failure modes

| Trigger | Behaviour | Error code |
|---|---|---|
| `audit_outbox` INSERT fails during Phase 2 | Entire Phase 2 transaction rolls back; calculation is not persisted; idempotency record stays `PROCESSING`; `markFailed()` called | `DEPENDENCY_UNAVAILABLE` → 503 |
| `tax_calculation` INSERT fails during Phase 2 | Same as above; outbox INSERT also rolled back | `DEPENDENCY_UNAVAILABLE` → 503 |
| `findUnpublished()` DB error | Publisher logs error; skips this cycle; retries on next interval | n/a (background) |
| `publish()` consumer error | Logged as WARN; event stays pending; retried next poll | n/a (background) |
| `markPublished()` DB error | Logged as WARN; event stays pending; will be re-delivered (at-least-once) | n/a (background) |
| `loadById()` with wrong tenant | Returns `Optional.empty()` (non-disclosing) | `CALCULATION_NOT_FOUND` → 404 |
| `rule_snapshot` or `input_snapshot` JSONB exceeds column capacity | Rejected with `VALIDATION_ERROR` before INSERT | `DEPENDENCY_UNAVAILABLE` → 503 |

---

## 9. BDD acceptance scenarios

All rates and fixtures are **SYNTHETIC and illustrative only**.

### Scenario S-OUT-001 — Evidence and outbox written atomically with calculation

```
Given  Phase 2 transaction begins with:
         tenantId = T1, calculationId = C1
         taxableBase = "1000.00", CBS rate = 0.088000000 (SYNTHETIC)
When   saveWithAudit(record) completes and transaction commits
Then   tax_calculation row exists: id=C1, tenant_id=T1, engine_version="1.0.0"
  And  tax_calculation_item rows exist: exactly 1 row (CBS)
         tax_type="CBS", taxable_base=1000.0000, rate=0.088000000, tax_amount=88.00
  And  audit_outbox row exists: calculation_id=C1, event_type="TAX_CALCULATION_COMPLETED"
         published_at IS NULL
  And  audit_outbox.payload.simulationContext = "SYNTHETIC"
  And  audit_outbox.payload.totalTax = "88.00"
  And  audit_outbox.payload contains no idempotency_key
```

### Scenario S-OUT-002 — Phase 2 rollback leaves no orphaned rows

```
Given  Phase 2 transaction begins writing tax_calculation and audit_outbox
  And  the transaction is forced to roll back (simulated DB error on audit_outbox INSERT)
When   the transaction completes with rollback
Then   no tax_calculation row exists for calculationId C1
  And  no tax_calculation_item rows exist for C1
  And  no audit_outbox row exists for C1
  And  idempotency_record for (T1, key) remains PROCESSING
  And  markFailed() is subsequently called → status becomes FAILED
```

### Scenario S-OUT-003 — Evidence hash is verifiable

```
Given  tax_calculation row for C1 with:
         input_snapshot  = <canonical JSON S>
         rule_snapshot   = <canonical rule JSON R>
         evidence_hash   = H
When   re-running §5.3 with S and R:
         inputCanonicalHash   = SHA-256(UTF-8(S))
         cbsSnapshotHash      = ruleSnapshot.rules[0].snapshotHash
         ibsSnapshotHash      = ruleSnapshot.rules[1].snapshotHash
         ruleAggregateInput   = '{"cbs":"<cbsHash>","ibs":"<ibsHash>"}'
         ruleAggregateHash    = SHA-256(UTF-8(ruleAggregateInput))
         evidenceInput        = '{"inputHash":"<inputHash>","ruleHash":"<ruleAggHash>"}'
         recomputedHash       = SHA-256(UTF-8(evidenceInput))
Then   recomputedHash == H   (invariant INV-OUT-003 holds)
```

### Scenario S-OUT-004 — Outbox single-rule evidence hash (CBS only)

```
Given  only a CBS rule applies; no IBS rule
When   evidenceHash is computed:
         ruleAggregateHash = cbsSnapshotHash  (§5.3 single-rule path)
         evidenceInput     = '{"inputHash":"...","ruleHash":"<cbsSnapshotHash>"}'
         evidenceHash      = SHA-256(UTF-8(evidenceInput))
Then   evidenceHash stored in tax_calculation matches the above computation
```

### Scenario S-OUT-005 — Publisher delivers pending event and marks published

```
Given  audit_outbox row: id=OB1, calculation_id=C1, published_at=NULL
When   publisher poll runs:
         findUnpublished(limit=50) returns [OB1]
         publish(OB1) succeeds
         markPublished(OB1) sets published_at = now()
Then   audit_outbox row for OB1: published_at IS NOT NULL
  And  next findUnpublished() does NOT include OB1
```

### Scenario S-OUT-006 — Publisher fails to publish; event retried next cycle

```
Given  audit_outbox row: id=OB2, published_at=NULL
When   publisher poll runs:
         findUnpublished(limit=50) returns [OB2]
         publish(OB2) throws PublishException (simulated consumer failure)
         markPublished is NOT called
Then   audit_outbox row for OB2: published_at IS NULL
  And  next poll cycle: findUnpublished(limit=50) again returns OB2
  And  if publish succeeds on second attempt: markPublished called → published
```

### Scenario S-OUT-007 — At-least-once: markPublished fails after successful publish

```
Given  OB3 published successfully to consumer
  And  markPublished(OB3) fails (simulated DB error)
Then   audit_outbox row for OB3: published_at IS NULL
  And  next poll cycle: OB3 re-delivered to consumer
  And  consumer receives OB3 twice; consumer is idempotent on calculationId → no duplicate effect
```

### Scenario S-OUT-008 — markPublished is idempotent

```
Given  OB4 already has published_at = '2026-11-23T10:00:00Z'
When   markPublished(OB4) is called again
Then   no exception is thrown
  And  published_at remains '2026-11-23T10:00:00Z' (unchanged)
```

### Scenario S-OUT-009 — findUnpublished respects limit and ordering

```
Given  200 audit_outbox rows with published_at=NULL,
         created_at ranging from 2026-11-23T09:00:00Z to 2026-11-23T09:03:19Z
When   findUnpublished(limit=10) is called
Then   exactly 10 rows are returned
  And  they are ordered by created_at ascending (oldest first)
  And  all 10 have the earliest created_at values in the set
```

### Scenario S-OUT-010 — Payload excludes idempotency key

```
Given  a calculation completed with Idempotency-Key: "my-secret-key"
When   audit_outbox.payload is inspected
Then   payload does NOT contain the string "my-secret-key"
  And  payload does NOT contain the key name "idempotencyKey"
```

### Scenario S-OUT-011 — Payload includes simulationContext = SYNTHETIC

```
Given  any committed calculation
When   audit_outbox.payload is inspected
Then   payload.simulationContext == "SYNTHETIC"
  And  no other simulationContext value is possible
```

### Scenario S-OUT-012 — Cross-tenant: loadById returns empty for wrong tenant

```
Given  calculationId C1 belongs to tenantId T1
When   loadById(tenantId=T2, calculationId=C1) is called
Then   Optional.empty() is returned
  And  no information about T1 or C1 is included in any exception
```

### Scenario S-OUT-013 — Rule snapshot stored verbatim and immutable

```
Given  calculation C1 used CBS rule R1 (snapshotHash=SH1) at operationDate=2026-01-15
  And  rule R1 is later SUPERSEDED and its definition replaced by rule R2
When   tax_calculation.rule_snapshot for C1 is read
Then   rule_snapshot still contains R1's definition with snapshotHash=SH1
  And  the supersession of R1 does not modify the stored snapshot
```

---

## 10. Integration test requirements

All tests require a real PostgreSQL instance (Testcontainers).

| Test ID | What to verify |
|---|---|
| IT-OUT-001 | Phase 2 atomic write: `tax_calculation` + `audit_outbox` succeed together or fail together (force rollback mid-INSERT) |
| IT-OUT-002 | `evidenceHash` can be recomputed from `input_snapshot` and `rule_snapshot` and matches `evidence_hash` column |
| IT-OUT-003 | `findUnpublished(limit=N)` returns at most N rows ordered by `created_at` ascending |
| IT-OUT-004 | `markPublished()` is idempotent: calling twice on same `outboxId` does not throw and does not change `published_at` |
| IT-OUT-005 | `loadById()` with wrong `tenantId` returns empty (non-disclosing) |
| IT-OUT-006 | After application restart (new Spring context), `findUnpublished()` still returns events from before the restart |
| IT-OUT-007 | `audit_outbox.payload` JSONB does not contain `idempotency_key` field (enforced by integration assertion) |

---

## 11. Architecture constraints

| Constraint | Detail |
|---|---|
| `CalculationPersistenceGateway` module | `tax-usecases` (interface only) |
| `AuditOutboxGateway` module | `tax-usecases` (interface only) |
| Implementations | `tax-frameworks` (JPA/JDBC adapters) |
| Background publisher | `tax-frameworks` (Spring `@Scheduled` bean or equivalent) |
| `AuditEventPayload` / `CalculationRecord` | `tax-usecases` types; must not appear in `tax-domain` |
| `PendingAuditEvent` | `tax-frameworks` only; must not cross into `tax-usecases` |
| `saveWithAudit()` transaction scope | Must be called inside the same `@Transactional` as `IdempotencyGateway.commit()`; both are part of SPEC-TAX-005 Phase 2 |
| `markPublished()` transaction scope | Separate, short `@Transactional`; must never share the Phase 2 transaction |
| Payload logging | `payloadJson` must NOT appear in application logs at INFO or above; DEBUG only |
| LGPD retention | `audit_outbox` and `tax_calculation` rows require a data retention policy (duration to be agreed with legal/compliance; not specified in this spec; tracked as OQ-OUT-003) |

---

## 12. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-OUT-001 | Confirm `taxableBase` per component is excluded from outbox payload (only `totalTax` included); if audit requires per-component base, payload schema must be extended | Compliance officer |
| OQ-OUT-002 | Confirm MVP publish target is structured log + optional in-process consumer; no external broker in MVP | Domain owner / SRE |
| OQ-OUT-003 | Define retention period for `audit_outbox` and `tax_calculation` rows under LGPD; agree whether `operationId` may contain PII and if so define masking strategy | Legal / Compliance |
| OQ-OUT-004 | Confirm `PUBLISHER_INTERVAL_SECONDS = 10` and `PUBLISHER_BATCH_SIZE = 50` are acceptable for MVP at 100 RPS | SRE |
| OQ-OUT-005 | Confirm single `rule_version_id` column in `tax_calculation` referencing the CBS rule is sufficient, vs. a separate `tax_calculation_rule` join table when multiple rules apply | Domain owner |

---

## 13. Traceability

| Requirement | Covered by |
|---|---|
| RF-005 (persist evidence) | §4 `CalculationRecord`, §5.1 DB rows, §5.2 snapshot formats, §5.3 evidenceHash, INV-OUT-003/004 |
| RF-009 (observability) | §6 background publisher, §6.1 structured log, INV-OUT-001/002 |
| RNF-005 (auditability) | §5.3 evidenceHash, INV-OUT-010, S-OUT-003/004; consumer can verify data integrity offline |
| RNF-006 (modularity) | §11 architecture constraints; gateway interfaces in `tax-usecases`, implementations in `tax-frameworks` |
| failure-scenarios.md #6 | §6.2 at-least-once guarantee; S-OUT-006/007 |
| ADR-005 (transactional outbox) | §1 purpose, §5.1 `audit_outbox` table, §5 Phase 2 atomicity; publisher in §6 |
| SPEC-TAX-001 §4 | §5.2 input snapshot format; §5.3 uses `inputCanonicalHash` |
| SPEC-TAX-005 §5 | Phase 2 transaction scope; `saveWithAudit()` and `IdempotencyGateway.commit()` share one transaction |
| TAX-008 | This document |

Test evidence: to be attached after G3. BDD scenarios S-OUT-001…S-OUT-013 map to methods in `AuditOutboxIntegrationTest` (Testcontainers, `tax-frameworks` module). Integration tests IT-OUT-001…IT-OUT-007 form a separate `CalculationPersistenceTest` class verifying DB-level constraints and hash correctness.
