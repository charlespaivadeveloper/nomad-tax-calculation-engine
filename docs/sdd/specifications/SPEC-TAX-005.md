# SPEC-TAX-005 — Idempotency

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-007 (Enforce idempotency)  
**Last updated:** 2026-11-23  
**Requirements:** RF-007, RNF-004, RNF-005, RNF-008  
**Related specs:** SPEC-TAX-001 §4 (canonical hash), SPEC-TAX-007 (audit outbox — shares commit transaction)  
**All fixtures in this document are SYNTHETIC. No fiscal compliance is implied.**

---

## 1. Purpose

Specify the idempotency contract for `POST /v1/tax-calculations`: given the same `(tenantId, Idempotency-Key)` pair, the system must return the original result regardless of how many times the request is retried, and must reject requests that reuse the key with a different body. The mechanism must be safe under concurrent submission from multiple client instances and must survive network timeouts that occur after a successful database commit.

---

## 2. Concepts

| Term | Definition |
|---|---|
| **Idempotency key** | Client-supplied opaque string (header `Idempotency-Key`), 1–128 chars. Deduplication scope: `(tenantId, idempotencyKey)`. Same key from different tenants is an independent calculation. |
| **Request hash** | SHA-256 of the canonical input as defined in SPEC-TAX-001 §4. Used to detect body mismatches when the key is reused. The idempotency key is **not** included in the hash. |
| **Reservation** | A short-lived DB record claiming the `(tenantId, idempotencyKey)` slot with status `PROCESSING`. Created atomically via a unique-constraint INSERT. |
| **Commit** | Transition of the reservation to `COMMITTED` with the resulting `calculationId`, performed in the same DB transaction as the calculation persistence and audit outbox write. |
| **Conflict** | A second request presents the same key but a different request hash. Rejected with `409 IDEMPOTENCY_CONFLICT`. |
| **In-progress collision** | A second request presents the same key and hash while the first is still in `PROCESSING` state. Responded to with `503` and `Retry-After: 2`. |

---

## 3. Gateway interface (use-case boundary)

Lives in module `tax-usecases`, package `tax.usecases.port`. Implemented in `tax-frameworks`.  
No Spring, JPA, or SQL types in the interface.

### `IdempotencyGateway`

```
reserve(tenantId: UUID, idempotencyKey: String, requestHash: String): IdempotencyOutcome
```
Attempts to claim the `(tenantId, idempotencyKey)` slot.  
Outcomes defined in §3.1.

```
commit(tenantId: UUID, idempotencyKey: String, calculationId: UUID): void
```
Called **inside** the calculation commit transaction (see §5). Transitions status `PROCESSING → COMMITTED` and stores `calculationId`.  
Throws `IdempotencyCommitException` if the slot is not in `PROCESSING` state for this tenant/key pair (indicates a serialization anomaly — caller must abort the transaction).

```
markFailed(tenantId: UUID, idempotencyKey: String): void
```
Called in a **separate** transaction after the calculation transaction rolls back. Transitions status `PROCESSING → FAILED`.  
Idempotent: a no-op if the record is already `COMMITTED` (guards against a race where the commit transaction succeeded but the caller did not receive the result).

```
loadResult(tenantId: UUID, idempotencyKey: String): Optional<CalculationResult>
```
Fetches the persisted `CalculationResult` for a `COMMITTED` reservation.  
Returns `Optional.empty()` if the key is not found or not committed for this tenant.  
Used by the interactor to build the response after an `ALREADY_COMMITTED` outcome.

---

### 3.1 `IdempotencyOutcome` (sealed type)

| Variant | Meaning | Interactor action |
|---|---|---|
| `RESERVED` | Slot claimed successfully; `PROCESSING` record created | Proceed with rule resolution → calculation → commit |
| `ALREADY_COMMITTED(calculationId)` | Same key + same hash seen before; status is `COMMITTED` | Call `loadResult`, return 200 with original `CalculationResult` |
| `CONFLICT` | Same key, different hash | Throw `DomainException(IDEMPOTENCY_CONFLICT)` → 409 |
| `IN_PROGRESS` | Same key + same hash; status is `PROCESSING` | Throw `DomainException(DEPENDENCY_UNAVAILABLE)` with retry hint → 503 + `Retry-After: 2` |
| `RETRYABLE` | Same key; status is `FAILED` | Delete failed record and re-insert as `PROCESSING`; proceed as `RESERVED` |

---

### 3.2 `IdempotencyRecord` (data shape, infrastructure concern)

The gateway implementation reads and writes this shape; it is not a domain type and must not be used in `tax-domain` or `tax-usecases`.

| Column | Type | Constraint |
|---|---|---|
| `id` | UUID | PK, generated |
| `tenant_id` | UUID | not null; FK to tenant registry (or derived from JWT) |
| `idempotency_key` | VARCHAR(128) | not null |
| `request_hash` | CHAR(64) | not null; lowercase hex SHA-256 |
| `status` | VARCHAR(16) | `PROCESSING`, `COMMITTED`, `FAILED` |
| `calculation_id` | UUID | nullable; populated on `COMMITTED` |
| `created_at` | TIMESTAMPTZ | not null; set on INSERT |

Unique constraint: `UNIQUE(tenant_id, idempotency_key)`.  
Index: `(tenant_id, idempotency_key)` covering `status`, `request_hash`, `calculation_id`.

---

## 4. Idempotency lifecycle (state machine)

```
                  reserve()
                  INSERT succeeds
         ┌────────────────────────────► PROCESSING
         │                                  │
         │                      ┌───────────┴───────────┐
  (new)──┘               commit()           markFailed()
                          same tx             new tx
                               │                   │
                               ▼                   ▼
                          COMMITTED             FAILED
                               │                   │
                               │             reserve() on
                               │             same key+hash
                               │             → RETRYABLE
                               │             (delete + re-insert)
                               │
                        reserve() on
                        same key+hash
                        → ALREADY_COMMITTED
                        
                        reserve() on
                        same key+diff hash
                        → CONFLICT (from any state)
```

**Transitions:**

| From | Event | To | Notes |
|---|---|---|---|
| _(none)_ | `reserve()` INSERT succeeds | `PROCESSING` | — |
| _(none)_ | `reserve()` INSERT fails (unique), hash matches, status=`PROCESSING` | `IN_PROGRESS` | Concurrent duplicate |
| _(none)_ | `reserve()` INSERT fails (unique), hash matches, status=`COMMITTED` | `ALREADY_COMMITTED` | Idempotent retry |
| _(none)_ | `reserve()` INSERT fails (unique), hash matches, status=`FAILED` | `RETRYABLE` | Previous attempt failed |
| _(none)_ | `reserve()` INSERT fails (unique), hash **differs** | `CONFLICT` | Any existing status |
| `PROCESSING` | `commit()` (in calc. tx) | `COMMITTED` | — |
| `PROCESSING` | `markFailed()` (new tx) | `FAILED` | Calc. tx rolled back |
| `COMMITTED` | `markFailed()` | `COMMITTED` | No-op guard |

---

## 5. Transactional semantics

Two distinct database transactions are required:

### Phase 1 — Reservation (short, auto-commit)

```
BEGIN;
  INSERT INTO idempotency_record (id, tenant_id, idempotency_key, request_hash, status, created_at)
  VALUES (newUUID(), ?, ?, ?, 'PROCESSING', now());
  -- Unique constraint enforces serial access.
  -- On conflict: SELECT existing record (FOR UPDATE) → derive outcome.
COMMIT;
```

This transaction must complete before any rule resolution or calculation begins.  
Lock held for: duration of INSERT only (~1 ms). No long-held locks in Phase 1.

### Phase 2 — Calculation commit (longer, explicit)

All of the following writes must succeed or fail atomically:

```
BEGIN;
  INSERT INTO tax_calculation        (...);
  INSERT INTO tax_calculation_item   (...) × N;
  INSERT INTO audit_outbox           (...);           -- SPEC-TAX-007
  UPDATE idempotency_record
    SET status = 'COMMITTED', calculation_id = ?
    WHERE tenant_id = ? AND idempotency_key = ? AND status = 'PROCESSING';
  -- Assert rowsUpdated == 1.
  -- If 0: another process committed this slot → abort with serialization error.
COMMIT;
```

If Phase 2 rolls back (for any reason), a **new** transaction calls `markFailed()`:

```
BEGIN;
  UPDATE idempotency_record
    SET status = 'FAILED'
    WHERE tenant_id = ? AND idempotency_key = ? AND status = 'PROCESSING';
COMMIT;
```

**Invariant:** no calculation record exists in `tax_calculation` without a corresponding `COMMITTED` idempotency record. These two rows are always written in the same transaction.

---

## 6. Use-case interactor flow

This is the normative orchestration within `CalculateTaxInteractor` (in `tax-usecases`):

```
execute(input: TaxOperationInput) → CalculationResult:

  1.  Validate input                              (SPEC-TAX-001 §2.3)
  2.  requestHash ← canonicalHash(input)          (SPEC-TAX-001 §4)
  3.  outcome ← idempotencyGateway.reserve(
                  input.tenantId, input.idempotencyKey, requestHash)

  4.  switch outcome:
        ALREADY_COMMITTED(calculationId):
          result ← idempotencyGateway.loadResult(tenantId, key)
          return result with HTTP 200

        CONFLICT:
          throw DomainException(IDEMPOTENCY_CONFLICT,
            "idempotency key reused with different request body")

        IN_PROGRESS:
          throw DomainException(DEPENDENCY_UNAVAILABLE,
            "concurrent request in progress; retry after 2 seconds")
            with retryAfterSeconds = 2

        RETRYABLE:
          // gateway deleted failed record and created new PROCESSING record
          // fall through to RESERVED handling

        RESERVED:
          // proceed

  5.  try:
        rules      ← ruleGateway.resolve(input)         (SPEC-TAX-002)
        components ← calculationService.calculate(
                       input.taxableBase, rules)         (SPEC-TAX-003)
        result     ← assemble CalculationResult(
                       calculationId   = newUUID(),
                       requestHash     = requestHash,
                       engineVersion   = config.engineVersion,
                       simulationContext = SYNTHETIC,
                       ...)
        // Phase 2 transaction (atomic):
        persistenceGateway.saveCalculationWithIdempotencyCommit(
          result, input.tenantId, input.idempotencyKey)  // writes calc + outbox + idem commit
        return result with HTTP 201

      catch any exception:
        idempotencyGateway.markFailed(input.tenantId, input.idempotencyKey)
        rethrow
```

---

## 7. HTTP contract for idempotency

| Scenario | HTTP Status | Response body | Headers |
|---|---|---|---|
| New calculation, success | 201 Created | `CalculationResult` | — |
| Duplicate key + same body, committed | 200 OK | Original `CalculationResult` | — |
| Duplicate key + same body, in-progress | 503 Service Unavailable | RFC 9457 Problem, `DEPENDENCY_UNAVAILABLE` | `Retry-After: 2` |
| Duplicate key + different body | 409 Conflict | RFC 9457 Problem, `IDEMPOTENCY_CONFLICT` | — |
| Previous attempt failed, retry | 201 Created | New `CalculationResult` | — |
| DB unavailable during reserve | 503 Service Unavailable | RFC 9457 Problem, `DEPENDENCY_UNAVAILABLE` | — |

A client **must** treat HTTP 200 from a POST as a cache hit, not an error.  
A client **must** include `Idempotency-Key` on every mutating request; the server rejects requests without it with 400.

---

## 8. Invariants

| ID | Invariant |
|---|---|
| INV-IMP-001 | `(tenantId, idempotencyKey)` is unique across all non-FAILED records; a second INSERT for the same pair must never succeed while the first is `PROCESSING` or `COMMITTED` |
| INV-IMP-002 | A `COMMITTED` idempotency record always has a matching `tax_calculation` row with the same `calculationId`; they are written in one transaction |
| INV-IMP-003 | A `CONFLICT` is returned whenever `idempotencyKey` matches but `requestHash` differs, regardless of the existing record's status |
| INV-IMP-004 | The same `(tenantId, idempotencyKey, requestHash)` triple always returns the same `CalculationResult`, including `calculationId` and `components` |
| INV-IMP-005 | Idempotency is scoped per tenant: Tenant A and Tenant B may both use `idempotencyKey = "k"` with no interference |
| INV-IMP-006 | A `COMMITTED` record is never downgraded to `PROCESSING` or `FAILED`; `markFailed()` on a `COMMITTED` record is a no-op |
| INV-IMP-007 | `idempotencyKey` is never stored in the `tax_calculation` or `audit_outbox` tables; it lives only in `idempotency_record` |
| INV-IMP-008 | No `idempotencyKey` value, `requestHash`, or `tenantId` may appear in application logs at INFO level or above |
| INV-IMP-009 | A `FAILED` record does not block future retries; the same `(tenantId, key, hash)` on retry results in `RETRYABLE`, not a permanent failure |
| INV-IMP-010 | The Phase 2 transaction must assert `rowsUpdated == 1` for the idempotency commit UPDATE; if `0`, the transaction must be aborted |

---

## 9. Failure modes

| Trigger | Outcome | Error code | HTTP |
|---|---|---|---|
| `Idempotency-Key` header absent | Reject immediately | `VALIDATION_ERROR` | 400 |
| `Idempotency-Key` length > 128 chars | Reject immediately | `VALIDATION_ERROR` | 400 |
| Same key, different request body | `CONFLICT` outcome | `IDEMPOTENCY_CONFLICT` | 409 |
| Same key+hash, status `PROCESSING` | `IN_PROGRESS` outcome | `DEPENDENCY_UNAVAILABLE` | 503 + Retry-After |
| DB unavailable during `reserve()` | Cannot create reservation | `DEPENDENCY_UNAVAILABLE` | 503 |
| DB unavailable during Phase 2 commit | Transaction rolls back; `markFailed()` called | `DEPENDENCY_UNAVAILABLE` | 503 |
| `commit()` asserts 0 rows updated | Serialization anomaly; Phase 2 aborts | `DEPENDENCY_UNAVAILABLE` | 503 |
| `loadResult()` returns empty for `COMMITTED` record | Data inconsistency (should not occur) | `DEPENDENCY_UNAVAILABLE` | 503 |
| Cross-tenant key lookup | Returns 404 (non-disclosing) | `CALCULATION_NOT_FOUND` | 404 |

---

## 10. BDD acceptance scenarios

### Scenario S-IMP-001 — First request succeeds, creates 201

```
Given  no idempotency record exists for (tenantId=T1, key="key-001")
When   POST /v1/tax-calculations with Idempotency-Key: key-001 and valid body B1
Then   idempotency_record inserted: status=PROCESSING, requestHash=hash(B1)
  And  calculation proceeds and completes
  And  idempotency_record updated: status=COMMITTED, calculationId=<cid>
  And  HTTP 201 returned with CalculationResult containing calculationId=<cid>
```

### Scenario S-IMP-002 — Retry with same key+body returns 200 with original result

```
Given  idempotency_record exists: (T1, "key-001"), status=COMMITTED,
         requestHash=hash(B1), calculationId=<cid>
  And  tax_calculation row exists for <cid>
When   POST /v1/tax-calculations with Idempotency-Key: key-001 and same body B1
Then   reserve() returns ALREADY_COMMITTED(<cid>)
  And  no new calculation is performed
  And  HTTP 200 returned with original CalculationResult (identical to first response)
  And  HTTP response body matches first response body byte-for-byte in all calculable fields
```

### Scenario S-IMP-003 — Same key, different body → 409 IDEMPOTENCY_CONFLICT

```
Given  idempotency_record exists: (T1, "key-001"), status=COMMITTED, requestHash=hash(B1)
When   POST /v1/tax-calculations with Idempotency-Key: key-001 and different body B2
         (B2 differs from B1 in taxableBase: "1000.00" vs "2000.00")
Then   reserve() detects requestHash(B2) ≠ requestHash(B1) → CONFLICT outcome
  And  HTTP 409 returned with RFC 9457 body, code=IDEMPOTENCY_CONFLICT
  And  no new calculation is performed
  And  no new idempotency record is created
```

### Scenario S-IMP-004 — Race: two simultaneous identical requests (concurrent duplicate)

```
Given  no idempotency record exists for (T1, "key-race")
When   Thread A and Thread B simultaneously POST with Idempotency-Key: key-race and body B1
Then   exactly one of [Thread A, Thread B] wins the UNIQUE INSERT → outcome RESERVED
  And  the other sees INSERT conflict, finds status=PROCESSING, requestHash matches
         → outcome IN_PROGRESS → HTTP 503 with Retry-After: 2
  And  the winning thread completes normally → HTTP 201
When   the losing thread retries after 2 seconds
Then   idempotency_record is now COMMITTED → outcome ALREADY_COMMITTED
  And  HTTP 200 returned with original CalculationResult
```

### Scenario S-IMP-005 — Race: two simultaneous conflicting requests

```
Given  no idempotency record exists for (T1, "key-conflict")
When   Thread A POSTs with Idempotency-Key: key-conflict and body B1 (hash H1)
  And  Thread B POSTs simultaneously with Idempotency-Key: key-conflict and body B2 (hash H2, H2 ≠ H1)
Then   the thread that wins INSERT proceeds normally
  And  the thread that loses INSERT reads existing record:
         requestHash mismatch (H1 ≠ H2 or H2 ≠ H1) → CONFLICT → HTTP 409
```

### Scenario S-IMP-006 — Network timeout after commit: retry succeeds

```
Given  POST /v1/tax-calculations with Idempotency-Key: key-timeout, body B1
  And  Phase 2 transaction commits successfully (all DB writes persisted)
  And  the HTTP response is lost (simulated network error)
  And  client receives a connection timeout
When   client retries with same Idempotency-Key: key-timeout and same body B1
Then   reserve() finds status=COMMITTED → ALREADY_COMMITTED(<cid>)
  And  HTTP 200 returned with original CalculationResult
  And  no duplicate calculation exists in tax_calculation table
```

### Scenario S-IMP-007 — Calculation fails after reservation: retry allowed

```
Given  POST /v1/tax-calculations with Idempotency-Key: key-fail, body B1
  And  reserve() succeeds → PROCESSING
  And  rule resolution fails (RULE_NOT_FOUND)
  And  markFailed() sets status=FAILED
When   client retries with same Idempotency-Key: key-fail and same body B1
Then   reserve() finds status=FAILED → RETRYABLE
  And  FAILED record is replaced with new PROCESSING record
  And  calculation proceeds fresh
  And  if successful: HTTP 201 with new CalculationResult (new calculationId)
```

### Scenario S-IMP-008 — Tenant isolation: same key across tenants is independent

```
Given  Tenant T1 has idempotency_record (T1, "shared-key"), status=COMMITTED, calculationId=C1
When   Tenant T2 POSTs with Idempotency-Key: shared-key and body B2
Then   reserve() for (T2, "shared-key") finds no existing record → RESERVED
  And  Tenant T2's calculation proceeds independently
  And  Tenant T2 receives HTTP 201 with new CalculationResult (calculationId ≠ C1)
  And  Tenant T1's record is not modified
```

### Scenario S-IMP-009 — Key length boundaries

```
When   Idempotency-Key is a string of exactly 128 characters
Then   reserve() accepted; proceeds normally

When   Idempotency-Key is a string of 129 characters
Then   HTTP 400 returned, code=VALIDATION_ERROR, detail references "Idempotency-Key"
  And  no idempotency_record inserted

When   Idempotency-Key header is absent entirely
Then   HTTP 400 returned, code=VALIDATION_ERROR, detail references "Idempotency-Key"
```

### Scenario S-IMP-010 — Cross-tenant access: non-disclosing 404

```
Given  Tenant T1 has COMMITTED calculation with id=C1
When   Tenant T2 calls GET /v1/tax-calculations/C1 (a read, not idempotency concern)
Then   HTTP 404 returned (non-disclosing; does not reveal C1 belongs to T1)
  And  no information about T1 or C1 is included in the error body
```

### Scenario S-IMP-011 — Phase 2 serialization anomaly: 0 rows updated on idem commit

```
Given  Thread A holds PROCESSING record for (T1, "key-anomaly")
  And  Thread A reaches Phase 2 commit transaction
  And  between Phase 1 and Phase 2, the record is externally modified to COMMITTED
         (simulated pathological condition)
When   Phase 2 UPDATE sets status=COMMITTED WHERE status='PROCESSING' → 0 rows
Then   Phase 2 transaction aborts (rollback)
  And  HTTP 503 returned to Thread A
  And  no duplicate tax_calculation row is created
```

### Scenario S-IMP-012 — DB unavailable during reserve

```
Given  the database is unreachable
When   POST /v1/tax-calculations with any valid request
Then   reserve() throws DEPENDENCY_UNAVAILABLE
  And  HTTP 503 returned; no idempotency record created; no calculation attempted
```

---

## 11. Integration test requirements

The following tests require a real PostgreSQL instance (via Testcontainers). They cannot be satisfied by mocks.

| Test ID | What to verify |
|---|---|
| IT-IMP-001 | UNIQUE constraint on `(tenant_id, idempotency_key)` is enforced at DB level |
| IT-IMP-002 | Phase 2 rollback leaves no orphaned `tax_calculation` row (atomic write confirmed) |
| IT-IMP-003 | Concurrent duplicate: 50 threads submit same key simultaneously; exactly 1 produces 201; rest produce 503 or 200 |
| IT-IMP-004 | markFailed() after Phase 2 rollback correctly sets status=FAILED |
| IT-IMP-005 | RETRYABLE path: failed-then-retry produces a fresh 201 |
| IT-IMP-006 | Tenant isolation: two tenants with identical keys do not interfere |
| IT-IMP-007 | COMMITTED record survives application restart; retry after restart returns 200 |

---

## 12. Architecture constraints

| Constraint | Detail |
|---|---|
| `IdempotencyGateway` module | `tax-usecases` (interface only) |
| `IdempotencyGateway` implementation | `tax-frameworks` (JPA/JDBC adapter) |
| `idempotency_record` table | Owned exclusively by the idempotency adapter; no other module reads it directly |
| Domain types | `IdempotencyOutcome` and `IdempotencyGateway` are use-case types; must not appear in `tax-domain` |
| No application-level lock | Concurrency safety derives from the DB `UNIQUE` constraint; no Java `synchronized`, `ReentrantLock`, or Redis lock |
| `Idempotency-Key` not logged | Key value must be masked or excluded from all log statements; hashed version may appear in DEBUG logs only |
| Phase 2 atomicity | The `IdempotencyGateway.commit()` call must be made within the same `@Transactional` scope as `persistenceGateway.saveCalculation()`; must never be called in a separate transaction |

---

## 13. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-IMP-001 | Confirm `IN_PROGRESS` returns 503 + Retry-After: 2 (vs. 202 Accepted with polling endpoint) | Domain owner |
| OQ-IMP-002 | Confirm `FAILED` records allow automatic retry (RETRYABLE path) vs. requiring a different key | Domain owner |
| OQ-IMP-003 | Confirm `FAILED` records should be cleaned up after a TTL (e.g., 24 h) to prevent key exhaustion by permanently failing operations | Domain owner |
| OQ-IMP-004 | Confirm idempotency key scope is `(tenantId, key)` with no expiry, vs. `(tenantId, key, date)` to allow reuse across days | Domain owner |
| OQ-IMP-005 | Confirm the `idempotency_record.request_hash` check should be performed even for `COMMITTED` records (current spec: yes; it protects against hash collision attacks) | Security reviewer |

---

## 14. Traceability

| Requirement | Covered by |
|---|---|
| RF-007 (idempotency) | §6 interactor flow, §10 scenarios S-IMP-001…S-IMP-012 |
| RNF-004 (OAuth2 tenant isolation) | INV-IMP-005, S-IMP-008, S-IMP-010 |
| RNF-005 (auditability) | INV-IMP-002, INV-IMP-007 — idempotency commit is part of the audit transaction |
| RNF-008 (safe retries) | §4 state machine, S-IMP-002, S-IMP-006, S-IMP-007 |
| failure-scenarios.md #3 | S-IMP-003 (conflict), S-IMP-002 (same body) |
| failure-scenarios.md #4 | S-IMP-012 (DB unavailable) |
| failure-scenarios.md #5 | S-IMP-006 (timeout-after-commit) |
| failure-scenarios.md #8 | S-IMP-010 (cross-tenant non-disclosing 404) |
| ADR-004 (PostgreSQL) | §3.2 idempotency_record table; §5 UNIQUE constraint approach |
| ADR-005 (transactional outbox) | §5 Phase 2 — outbox INSERT in same transaction as idempotency commit |
| SPEC-TAX-001 §4 | request_hash = canonical input hash defined there |
| SPEC-TAX-007 | audit_outbox INSERT co-located in Phase 2 transaction |
| TAX-007 | This document |

Test evidence: to be attached after G3. BDD scenarios S-IMP-001…S-IMP-012 map to methods in `IdempotencyIntegrationTest` (Testcontainers, `tax-frameworks` module). Integration tests IT-IMP-001…IT-IMP-007 are a separate `IdempotencyDatabaseTest` class verifying DB-level constraints.
