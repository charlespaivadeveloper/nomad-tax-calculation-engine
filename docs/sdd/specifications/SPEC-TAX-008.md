# SPEC-TAX-008 — Historical replay

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-009 (Implement historical replay)  
**Last updated:** 2026-11-24  
**Requirements:** RF-005, RF-008, RNF-001, RNF-005, RNF-006  
**Related specs:** SPEC-TAX-001 §4 (canonical hash), SPEC-TAX-003 §2 (calculation service), SPEC-TAX-007 §5.3 (evidence hash), SPEC-TAX-007 §3 (persistence gateway)  
**All rates, hashes and fixtures in this document are SYNTHETIC and must never be used as legally certified tax calculations.**

---

## 1. Purpose

Specify the `ReplayInteractor` use case: given a `(tenantId, calculationId)` pair, load the immutable evidence record stored at calculation time (SPEC-TAX-007), re-execute the identical arithmetic using the stored input snapshot and rule snapshot — without querying live rules — recompute the evidence hash, and return a `ReplayResult` that declares whether the stored evidence is intact.

This use case serves two distinct purposes:

1. **Determinism verification** — confirm that the engine produces identical output from the same inputs and rule versions.
2. **Tamper detection** — detect any post-commit mutation of `tax_calculation.evidence_hash`, `input_snapshot`, or `rule_snapshot` by recomputing and comparing.

The replay never re-resolves rules from live storage. It never contacts `TaxRuleGateway`. It operates exclusively on immutable stored data. Historical fidelity is preserved even after rules are superseded, amended, or deleted.

---

## 2. Input boundary DTO

### `ReplayRequest`

Lives in module `tax-usecases`, package `tax.usecases.replay`.  
Constructed by the interface-adapters layer from the HTTP path parameter and JWT; passed into the interactor.

| Field | Java type | Constraint | Source |
|---|---|---|---|
| `tenantId` | `UUID` | not null | JWT claim `tenant_id` |
| `calculationId` | `UUID` | not null | URL path parameter `{calculationId}` |

Both fields must be non-null before the interactor is called. A missing or unparseable UUID yields `ErrorCode.VALIDATION_ERROR`; detail names the failing field.  
`tenantId` must never be accepted from the request body or query parameter.

---

## 3. Output boundary DTOs

### 3.1 `ReplayVersionStatus`

Pure enum. Lives in module `tax-domain`, package `tax.domain.model`.  
No Spring, JPA, or HTTP types.

| Value | Meaning |
|---|---|
| `MATCHED` | `currentEngineVersion` equals `storedEngineVersion` — arithmetic is comparable |
| `VERSION_CHANGED` | `currentEngineVersion` differs from `storedEngineVersion` — replay completed with stored rules; result is flagged for review |

### 3.2 `ReplayResult`

Lives in module `tax-usecases`, package `tax.usecases.replay`.  
Crosses the use-case output boundary. Constructed by the interactor; consumed by the interface-adapters layer for HTTP serialisation.

| Field | Java type | Constraint |
|---|---|---|
| `calculationId` | `UUID` | not null; equals `ReplayRequest.calculationId` |
| `tenantId` | `UUID` | not null; equals `ReplayRequest.tenantId` |
| `replayedAt` | `Instant` | not null; set by the interactor at replay time; never copied from stored `createdAt` |
| `engineVersion` | `String` | not null; current engine version at replay time |
| `storedEngineVersion` | `String` | not null; from `CalculationRecord.engineVersion` |
| `versionStatus` | `ReplayVersionStatus` | not null; `MATCHED` or `VERSION_CHANGED` |
| `simulationContext` | `SimulationContext` | always `SYNTHETIC` — invariant, never read from stored record |
| `replayedComponents` | `List<TaxComponent>` | not null; non-empty; ordered CBS before IBS; computed by replay arithmetic |
| `replayedTotalTax` | `Money` | not null; exact sum of `replayedComponents[*].amount` at scale 2 |
| `storedEvidenceHash` | `String` | not null; 64-char lowercase hex; from `CalculationRecord.evidenceHash` |
| `replayedEvidenceHash` | `String` | not null; 64-char lowercase hex; recomputed by the interactor via §6 |
| `evidenceVerified` | `boolean` | `true` iff `replayedEvidenceHash.equals(storedEvidenceHash)` |

A `ReplayResult` with `evidenceVerified = false` is a valid, non-error return from the interactor. It indicates potential data integrity concern; the caller decides how to handle it. The interactor does not throw or return `DomainError` solely because `evidenceVerified = false`.

### 3.3 `DomainError` (replay context)

Reuses `DomainError` from SPEC-TAX-001 §2.5. For replay, the following `ErrorCode` values apply:

| ErrorCode | HTTP status | Trigger |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `calculationId` or `tenantId` missing or not a valid UUID |
| `CALCULATION_NOT_FOUND` | 404 | No `CalculationRecord` exists for `(tenantId, calculationId)`; also returned when the record belongs to a different tenant (non-disclosing) |
| `DEPENDENCY_UNAVAILABLE` | 503 | Persistent store unreachable during load |

`CALCULATION_NOT_FOUND` is a new `ErrorCode` value extending the enumeration defined in SPEC-TAX-001 §2.1.

---

## 4. `SnapshotDeserializerPort`

Lives in module `tax-usecases`, package `tax.usecases.replay`.  
Implemented in module `tax-frameworks`. No Spring, JPA, or SQL types in the interface.

```
SnapshotDeserializerPort
  deserializeTaxableBase(inputSnapshotJson: String): BigDecimal
  deserializeCurrency(inputSnapshotJson: String): Currency
  deserializeRuleSnapshots(ruleSnapshotJson: String): List<TaxRuleSnapshot>
```

**Contracts:**

| Method | Input | Output | On error |
|---|---|---|---|
| `deserializeTaxableBase` | `inputSnapshot` JSON (SPEC-TAX-007 §5.2) | `BigDecimal` with scale <= 4 | Throws `SnapshotDeserializationException` |
| `deserializeCurrency` | same JSON | `Currency` enum value | Throws `SnapshotDeserializationException` |
| `deserializeRuleSnapshots` | `ruleSnapshot` JSON array (SPEC-TAX-007 §5.2) | `List<TaxRuleSnapshot>` ordered CBS before IBS | Throws `SnapshotDeserializationException` |

### `SnapshotDeserializationException`

Domain checked exception. Lives in `tax-usecases`.  
Indicates stored JSON is malformed or structurally inconsistent with the canonical format.  
In a healthy system this must never occur; it signals storage corruption.

The interactor converts this to `DomainError(DEPENDENCY_UNAVAILABLE, "stored snapshot is unreadable", correlationId)`.

---

## 5. Interactor algorithm

The following steps are normative. An implementation is correct if and only if it produces the same outputs as this algorithm for all valid inputs.

```
function replay(request: ReplayRequest,
                persistenceGateway: CalculationPersistenceGateway,
                snapshotDeserializer: SnapshotDeserializerPort,
                calculationService: TaxCalculationService,
                currentEngineVersion: String,
                clock: Clock): Either<DomainError, ReplayResult>

  1. Validate request:
       if request.calculationId == null:
         return Left(DomainError(VALIDATION_ERROR, "calculationId must not be null", correlationId))
       if request.tenantId == null:
         return Left(DomainError(VALIDATION_ERROR, "tenantId must not be null", correlationId))

  2. Load record:
       try:
         optional = persistenceGateway.loadById(request.tenantId, request.calculationId)
       catch PersistenceException:
         return Left(DomainError(DEPENDENCY_UNAVAILABLE, "rule store unavailable", correlationId))

       if optional.isEmpty():
         return Left(DomainError(CALCULATION_NOT_FOUND,
                                 "calculation not found", correlationId))
         // Non-disclosing: same error whether record is absent or belongs to a different tenant.
         // loadById contract (SPEC-TAX-007 §3) guarantees it returns empty for wrong-tenant ID.

       record = optional.get()

  3. Deserialize stored snapshots:
       try:
         taxableBase     = snapshotDeserializer.deserializeTaxableBase(record.inputSnapshot())
         currency        = snapshotDeserializer.deserializeCurrency(record.inputSnapshot())
         resolvedRules   = snapshotDeserializer.deserializeRuleSnapshots(record.ruleSnapshot())
       catch SnapshotDeserializationException:
         return Left(DomainError(DEPENDENCY_UNAVAILABLE,
                                 "stored snapshot is unreadable", correlationId))

  4. Re-execute arithmetic:
       // Uses ONLY stored data — never contacts TaxRuleGateway or live storage.
       components = calculationService.calculate(taxableBase, resolvedRules, currency)
       // calculationService.calculate is deterministic (SPEC-TAX-003 §2 postconditions)

  5. Recompute evidence hash:
       replayedEvidenceHash = computeEvidenceHash(
                                record.inputCanonicalHash(),
                                resolvedRules
                              )
       // Algorithm identical to SPEC-TAX-007 §5.3; pure function; see §6 below.

  6. Determine version status:
       versionStatus = currentEngineVersion.equals(record.engineVersion())
                       ? MATCHED : VERSION_CHANGED

  7. Build and return result:
       return Right(ReplayResult(
         calculationId       = request.calculationId,
         tenantId            = request.tenantId,
         replayedAt          = clock.instant(),
         engineVersion       = currentEngineVersion,
         storedEngineVersion = record.engineVersion(),
         versionStatus       = versionStatus,
         simulationContext   = SYNTHETIC,                      // invariant: never from stored value
         replayedComponents  = components.components(),
         replayedTotalTax    = components.totalTax(),
         storedEvidenceHash  = record.evidenceHash(),
         replayedEvidenceHash = replayedEvidenceHash,
         evidenceVerified    = replayedEvidenceHash.equals(record.evidenceHash())
       ))
```

---

## 6. Evidence hash re-verification

The replay interactor recomputes the evidence hash using the identical algorithm defined in SPEC-TAX-007 §5.3. This section reproduces it for self-containment.

**Input:** `inputCanonicalHash` (from `CalculationRecord`), `resolvedRules: List<TaxRuleSnapshot>` (from deserialized `ruleSnapshot` JSON, ordered CBS before IBS).

**Step 1 — Rule aggregate hash:**

```
if resolvedRules.size() == 1:
    ruleAggregateHash = resolvedRules[0].snapshotHash()

if resolvedRules.size() == 2 (CBS at [0], IBS at [1]):
    ruleAggregateInput = '{"cbs":"' + cbsSnapshotHash + '","ibs":"' + ibsSnapshotHash + '"}'
    ruleAggregateHash  = SHA-256(UTF-8(ruleAggregateInput)) → 64-char lowercase hex
```

**Step 2 — Evidence hash:**

```
evidenceInput = '{"inputHash":"' + inputCanonicalHash + '","ruleHash":"' + ruleAggregateHash + '"}'
replayedEvidenceHash = SHA-256(UTF-8(evidenceInput)) → 64-char lowercase hex
```

This is a pure function. The implementation lives in `tax-domain` as a static utility  
(`EvidenceHashUtil.compute(String inputCanonicalHash, List<TaxRuleSnapshot> rules)`), co-located with the existing canonical hash utility.

If `resolvedRules` is empty the method throws `IllegalArgumentException` (programming error).  
If `resolvedRules.size() > 2` the method throws `IllegalArgumentException` (not supported in MVP).

---

## 7. Invariants

The following must hold at all times and must be verifiable without any framework wiring.

| ID | Invariant |
|---|---|
| INV-HR-001 | The replay interactor must never call `TaxRuleGateway.findPublished()`; it uses only `CalculationPersistenceGateway.loadById()` and `SnapshotDeserializerPort` |
| INV-HR-002 | `ReplayResult.simulationContext` is always `SYNTHETIC`; it must never be set from the stored record value |
| INV-HR-003 | `ReplayResult.replayedAt` is always set from the clock at replay execution time; it must never be copied from `CalculationRecord.createdAt` |
| INV-HR-004 | A `CALCULATION_NOT_FOUND` error must be returned whether the record is absent or belongs to a different tenant; the two cases are indistinguishable in the response |
| INV-HR-005 | `evidenceVerified = false` is a valid non-error outcome; the interactor must not throw or return `DomainError` solely because the hashes differ |
| INV-HR-006 | `ReplayResult.replayedEvidenceHash` must always be recomputed by the interactor; it must never be copied from `storedEvidenceHash` |
| INV-HR-007 | The re-execution arithmetic in step 4 must use the rounding policy from SPEC-TAX-001 §3 (`HALF_EVEN`, `DECIMAL128`); no alternative rounding mode is permitted |
| INV-HR-008 | No PII, JWT fragment, `idempotencyKey`, or raw secret value may appear in `ReplayResult` or in any `DomainError.detail` produced by the replay interactor |
| INV-HR-009 | For identical `(calculationId, tenantId)` and identical DB state, repeated calls to `replay()` must return identical `replayedComponents`, `replayedTotalTax`, and `replayedEvidenceHash` |
| INV-HR-010 | `ReplayResult.replayedComponents` is ordered CBS before IBS, enforced by `TaxCalculationService.calculate()` (SPEC-TAX-003 §4) |

---

## 8. Failure modes

| Trigger | ErrorCode | Detail example |
|---|---|---|
| `calculationId` null or not a valid UUID | `VALIDATION_ERROR` | "calculationId must not be null" |
| `tenantId` null (JWT claim missing) | `VALIDATION_ERROR` | "tenantId must not be null" |
| No record for `(tenantId, calculationId)` | `CALCULATION_NOT_FOUND` | "calculation not found" |
| Record found but belongs to different tenant | `CALCULATION_NOT_FOUND` | "calculation not found" (non-disclosing) |
| Persistent store unreachable on load | `DEPENDENCY_UNAVAILABLE` | "rule store unavailable" |
| Stored `inputSnapshot` JSON unparseable | `DEPENDENCY_UNAVAILABLE` | "stored snapshot is unreadable" |
| Stored `ruleSnapshot` JSON unparseable | `DEPENDENCY_UNAVAILABLE` | "stored snapshot is unreadable" |
| `replayedEvidenceHash != storedEvidenceHash` | *(not an error)* | field `evidenceVerified = false` in `ReplayResult`; no `DomainError` |
| Current `engineVersion != storedEngineVersion` | *(not an error)* | field `versionStatus = VERSION_CHANGED`; replay completes normally |

---

## 9. BDD acceptance scenarios (synthetic fixtures)

All rates, hashes and identifiers in scenarios below are **SYNTHETIC and illustrative only**.

### Scenario S-HR-001 — Valid two-component replay, evidence verified

```
Given  a CalculationRecord exists in DB for (tenantId = T1, calculationId = C1):
         inputSnapshot   = '{"classificationCode":"GOODS_GENERAL","currency":"BRL",
                             "operationDate":"2026-01-15","operationId":"OP-001",
                             "simulationContext":"SYNTHETIC","taxableBase":"1000.00",
                             "tenantId":"<T1>"}'
         ruleSnapshot    = '{"rules":[
                               {"classificationCode":"GOODS_GENERAL","engineVersion":"1.0.0",
                                "id":"<uuid-cbs>","rateDecimal":"0.088000000",
                                "snapshotHash":"<64-hex-cbs>","taxType":"CBS",
                                "tenantId":"<T1>","validFrom":"2026-01-01","validTo":"2026-07-01"},
                               {"classificationCode":"GOODS_GENERAL","engineVersion":"1.0.0",
                                "id":"<uuid-ibs>","rateDecimal":"0.177000000",
                                "snapshotHash":"<64-hex-ibs>","taxType":"IBS",
                                "tenantId":"<T1>","validFrom":"2026-01-01","validTo":"2026-07-01"}
                             ]}'
         evidenceHash    = <correctly computed 64-char hex per SPEC-TAX-007 §5.3>
         engineVersion   = "1.0.0"
  And  currentEngineVersion = "1.0.0"
When   replay(ReplayRequest(tenantId=T1, calculationId=C1)) is called
Then   ReplayResult is returned
  And  replayedComponents contains exactly 2 entries ordered [CBS, IBS]
  And  replayedComponents[CBS].amount = 88.00  (1000.00 × 0.088000000, HALF_EVEN scale 2)
  And  replayedComponents[IBS].amount = 177.00 (1000.00 × 0.177000000, HALF_EVEN scale 2)
  And  replayedTotalTax = 265.00
  And  replayedEvidenceHash = storedEvidenceHash
  And  evidenceVerified = true
  And  versionStatus = MATCHED
  And  simulationContext = SYNTHETIC
```

### Scenario S-HR-002 — Determinism: two replays return identical result

```
Given  the same CalculationRecord as S-HR-001
When   replay() is called twice with the same ReplayRequest
Then   both calls return ReplayResult with identical:
         replayedComponents[*].amount, replayedTotalTax,
         replayedEvidenceHash, evidenceVerified, versionStatus
  And  replayedAt values differ (each call sets a fresh timestamp)
```

### Scenario S-HR-003 — Rounding consistency: HALF_EVEN preserved

```
Given  a CalculationRecord with:
         taxableBase = "100.01"
         rule CBS rateDecimal = "0.088000000"
When   replay() called
Then   replayedComponents[CBS].amount = 8.80
       (100.01 × 0.088000000 = 8.80088 → setScale(2, HALF_EVEN) → 8.80)
  And  evidenceVerified = true
```

### Scenario S-HR-004 — Engine version unchanged: versionStatus MATCHED

```
Given  record.engineVersion = "1.0.0"
  And  currentEngineVersion = "1.0.0"
When   replay() called
Then   ReplayResult.versionStatus = MATCHED
  And  engineVersion = "1.0.0"
  And  storedEngineVersion = "1.0.0"
```

### Scenario S-HR-005 — Engine version changed: versionStatus VERSION_CHANGED, replay completes

```
Given  record.engineVersion = "1.0.0"
  And  currentEngineVersion = "1.1.0"
When   replay() called
Then   ReplayResult is returned (not an error)
  And  versionStatus = VERSION_CHANGED
  And  engineVersion = "1.1.0"
  And  storedEngineVersion = "1.0.0"
  And  replayedComponents and replayedTotalTax are populated
  And  evidenceVerified is computed and present (may be true or false depending on hash)
```

### Scenario S-HR-006 — Tampered evidence hash: evidenceVerified false

```
Given  a CalculationRecord where tax_calculation.evidence_hash
         has been mutated to an incorrect value after original INSERT
  And  ruleSnapshot and inputSnapshot are intact
When   replay() called
Then   replayedEvidenceHash = correctly computed hash
  And  storedEvidenceHash = tampered value
  And  evidenceVerified = false
  And  no DomainError is returned (ReplayResult is returned with evidenceVerified=false)
```

### Scenario S-HR-007 — Calculation not found

```
Given  no CalculationRecord exists in DB for calculationId = C99
When   replay(ReplayRequest(tenantId=T1, calculationId=C99)) called
Then   DomainError returned with code = CALCULATION_NOT_FOUND
  And  detail = "calculation not found"
  And  no ReplayResult is returned
```

### Scenario S-HR-008 — Tenant isolation: calculation belongs to different tenant

```
Given  a CalculationRecord exists for (tenantId=T2, calculationId=C1)
  And  no CalculationRecord exists for (tenantId=T1, calculationId=C1)
When   replay(ReplayRequest(tenantId=T1, calculationId=C1)) called
Then   DomainError with code = CALCULATION_NOT_FOUND
  And  detail = "calculation not found"  (identical to genuine missing case)
  And  T2's record is never included in the response
```

### Scenario S-HR-009 — Uses stored rules even if live rules have changed

```
Given  a CalculationRecord with ruleSnapshot containing CBS rateDecimal = "0.088000000"
  And  the live TaxRule table has been updated: CBS rate is now "0.100000000"
When   replay() called
Then   replayedComponents[CBS].amount is computed using stored rate 0.088000000
  And  NOT using the live rate 0.100000000
  And  TaxRuleGateway.findPublished() is never called during replay
```

### Scenario S-HR-010 — Single-component CBS-only replay

```
Given  a CalculationRecord with ruleSnapshot containing only one rule (CBS)
         rateDecimal = "0.050000000"
         taxableBase = "200.00"
When   replay() called
Then   replayedComponents has exactly 1 entry: CBS
  And  replayedComponents[CBS].amount = 10.00  (200.00 × 0.050000000, HALF_EVEN)
  And  replayedTotalTax = 10.00
  And  evidenceVerified = true  (assuming hash is intact)
```

### Scenario S-HR-011 — Persistent store unavailable

```
Given  the DB is unreachable when the interactor calls persistenceGateway.loadById()
When   replay() called
Then   DomainError with code = DEPENDENCY_UNAVAILABLE
  And  detail = "rule store unavailable"
  And  no ReplayResult is returned
```

### Scenario S-HR-012 — simulationContext always SYNTHETIC

```
Given  a CalculationRecord (regardless of what simulationContext is stored)
When   replay() called successfully
Then   ReplayResult.simulationContext = SYNTHETIC
  And  the value is never read from the stored record
```

### Scenario S-HR-013 — Missing calculationId

```
When   replay() called with calculationId = null
Then   DomainError with code = VALIDATION_ERROR
  And  detail references "calculationId"

When   replay() called with calculationId that is not a valid UUID string
Then   DomainError with code = VALIDATION_ERROR
  And  detail references "calculationId"
```

### Scenario S-HR-014 — No PII or sensitive data in result or errors

```
Given  any replay invocation (success or error)
Then   ReplayResult contains no idempotencyKey, no JWT fragments, no raw secret values
  And  DomainError.detail contains no idempotencyKey, no JWT fragments, no tenant PII
  And  CALCULATION_NOT_FOUND detail is non-disclosing regardless of actual reason
```

### Scenario S-HR-015 — replayedAt is set at replay time

```
Given  a CalculationRecord with createdAt = 2026-01-15T08:00:00Z
  And  replay is called at 2026-11-24T10:00:00Z
When   replay() returns ReplayResult
Then   ReplayResult.replayedAt = 2026-11-24T10:00:00Z  (the replay time)
  And  ReplayResult.replayedAt != 2026-01-15T08:00:00Z  (not the original createdAt)
```

---

## 10. Integration test requirements

Each test must run against a real PostgreSQL instance (Testcontainers) with Flyway migrations applied. No mocked gateways for integration tests.

| ID | Scenario | Assertion |
|---|---|---|
| IT-HR-001 | Insert a full `CalculationRecord` (via `CalculationPersistenceGateway.saveWithAudit()`); call `replay()` | `ReplayResult` returned; `replayedComponents` match stored components exactly; `evidenceVerified = true` |
| IT-HR-002 | Insert record for tenant T1 and tenant T2; call `replay()` with T1 and C1 that belongs to T2 | `CALCULATION_NOT_FOUND` returned; T2 record untouched |
| IT-HR-003 | Insert record; manually compute expected `replayedEvidenceHash` per §6; call `replay()` | `replayedEvidenceHash` matches the manually computed value exactly (64 lowercase hex chars) |
| IT-HR-004 | Insert one record; 50 concurrent threads call `replay()` with the same `(tenantId, calculationId)` | All threads return identical `replayedComponents`, `replayedTotalTax`, `replayedEvidenceHash`; no deadlock |
| IT-HR-005 | Shut down DB container after Flyway setup; call `replay()` | `DEPENDENCY_UNAVAILABLE` returned |
| IT-HR-006 | Insert record; UPDATE `evidence_hash` column to a wrong value directly in DB; call `replay()` | `ReplayResult` returned with `evidenceVerified = false`; `storedEvidenceHash` = tampered value; `replayedEvidenceHash` = correct value |
| IT-HR-007 | Insert record with `engineVersion = "1.0.0"`; configure current engine version as `"1.1.0"`; call `replay()` | `versionStatus = VERSION_CHANGED`; `storedEngineVersion = "1.0.0"`; `engineVersion = "1.1.0"`; replay completes |

---

## 11. Architecture constraints

| Constraint | Detail |
|---|---|
| `ReplayRequest` | Module `tax-usecases`, package `tax.usecases.replay` |
| `ReplayResult` | Module `tax-usecases`, package `tax.usecases.replay` |
| `ReplayVersionStatus` | Module `tax-domain`, package `tax.domain.model` |
| `SnapshotDeserializerPort` | Module `tax-usecases`, package `tax.usecases.replay`; interface only; no Spring/JPA/Jackson types |
| `SnapshotDeserializationException` | Module `tax-usecases`; not a `RuntimeException` subtype |
| `ReplayInteractor` | Module `tax-usecases`; depends on `CalculationPersistenceGateway` (SPEC-TAX-007 §3), `SnapshotDeserializerPort`, `TaxCalculationService` (SPEC-TAX-003 §2), `EvidenceHashUtil` (new pure utility in `tax-domain`) |
| `EvidenceHashUtil` | Module `tax-domain`; pure static utility; no injected dependencies; uses only `java.security.MessageDigest`, `java.util.TreeMap`, `java.util.List` |
| SnapshotDeserializerPort implementation | Module `tax-frameworks`; may use Jackson or any JSON library — the JSON library never crosses into `tax-usecases` or `tax-domain` |
| ArchUnit rule | `noClasses().that().resideInAPackage("tax.usecases..").should().dependOnClassesThat().resideInAPackage("tax.frameworks..")` (inherited from ADR-008) |
| Forbidden imports in `tax-usecases`, `tax-domain` | `org.springframework.*`, `jakarta.persistence.*`, `com.fasterxml.jackson.*`, any HTTP package |
| No live rule query | The `ReplayInteractor` must never import or depend on `TaxRuleGateway` |

Test classes (to be created after G3):
- `ReplayInteractorTest` — JUnit 5 unit tests for the interactor algorithm, mock `CalculationPersistenceGateway`, `SnapshotDeserializerPort`, `TaxCalculationService`; exercises S-HR-001 through S-HR-015 scenarios.
- `ReplayIntegrationTest` — Testcontainers integration tests for IT-HR-001 through IT-HR-007.

---

## 12. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-HR-001 | Confirm that `evidenceVerified = false` should be returned as a successful `200 OK` with the flag set, rather than as an error response — the distinction has implications for client alerting and monitoring | Domain owner |
| OQ-HR-002 | Confirm `VERSION_CHANGED` is informational only and does not block replay; if future compliance requires blocking on version mismatch, an ADR amendment is needed | Architect |
| OQ-HR-003 | Confirm single-rule (CBS-only) replay is in scope for MVP, or whether the engine always requires both CBS and IBS | Fiscal analyst |
| OQ-HR-004 | Confirm `SnapshotDeserializationException` → `DEPENDENCY_UNAVAILABLE` mapping is correct, or whether a distinct `DATA_CORRUPTION` error code should be added to `ErrorCode` for discoverability | Domain owner |
| OQ-HR-005 | Confirm that `CALCULATION_NOT_FOUND` must be non-disclosing (same error for absent vs. wrong-tenant), which prevents clients from enumerating calculations across tenants | Security reviewer |

---

## 13. Traceability

| Requirement | Covered by |
|---|---|
| RF-005 (persist evidence) | INV-HR-006: `replayedEvidenceHash` recomputed, never copied; uses SPEC-TAX-007 §5.3 algorithm |
| RF-008 (historical replay) | §5 interactor algorithm — full replay from stored snapshots |
| RNF-001 (determinism) | INV-HR-009, S-HR-002 |
| RNF-005 (auditability) | §6 evidence hash re-verification, S-HR-006 (tamper detection) |
| RNF-006 (modularity) | §11 architecture constraints |
| ADR-007 (synthetic boundary) | INV-HR-002 (`simulationContext` always `SYNTHETIC`), S-HR-012 |
| ADR-008 (clean architecture) | §11 — no Spring/JPA in `tax-usecases` or `tax-domain` |
| SPEC-TAX-001 §4 | `inputCanonicalHash` reused in evidence hash re-verification |
| SPEC-TAX-003 §2 | `TaxCalculationService.calculate()` called in step 4 of interactor |
| SPEC-TAX-007 §3 | `CalculationPersistenceGateway.loadById()` called in step 2 |
| SPEC-TAX-007 §5.3 | Evidence hash algorithm reproduced in §6 |
| TAX-009 | This document |

Test evidence: to be attached after G3. BDD scenarios S-HR-001 … S-HR-015 map to named JUnit 5 test methods in `ReplayInteractorTest`. Integration tests IT-HR-001 … IT-HR-007 map to `ReplayIntegrationTest`.
