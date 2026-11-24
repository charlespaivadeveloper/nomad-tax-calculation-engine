# SPEC-TAX-002 — Versioned Rule Resolution

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-004 (Resolve versioned rules)  
**Last updated:** 2026-11-23  
**Requirements:** RF-003, RNF-001, RNF-004, RNF-005, RNF-006  
**Rounding policy authority:** SPEC-TAX-001 §3 — this spec does not define rounding; it references that policy.  
**All rates and fixtures in this document are SYNTHETIC and must never be used as legally certified tax calculations.**

---

## 1. Purpose

Specify the `TaxRuleGateway` use-case boundary interface and the interactor-level resolution algorithm that selects **exactly one PUBLISHED `TaxRuleSnapshot`** per required `TaxType` for a given `ClassificationCode`, `operationDate`, and `tenantId`. The selection obeys half-open validity intervals (`[validFrom, validTo)`). Ambiguity (multiple matching rules for one TaxType) and absence (zero matching rules for any required TaxType) are hard errors — no silent fallback, no default rate, no partial success.

This spec covers:
- the `TaxRuleGateway` interface (lives in `tax-usecases`);
- the `RuleResolutionRequest` input type (lives in `tax-usecases`);
- the interactor-level resolution algorithm and error derivation;
- the `TaxRuleSnapshot` canonical hash algorithm (completes the stub in SPEC-TAX-001 §2.4);
- BDD acceptance scenarios and integration test requirements.

The persistence schema (table structure, indexes, Flyway migration) is a `tax-frameworks` concern and is out of scope here.

---

## 2. Gateway interface

### 2.1 `TaxRuleGateway`

Lives in module `tax-usecases`, package `tax.usecases.gateway`.

```
TaxRuleGateway
  findPublished(request: RuleResolutionRequest): List<TaxRuleSnapshot>
```

**Contract:**

| Aspect | Specification |
|---|---|
| Returns | All `PUBLISHED` `TaxRuleSnapshot` records for the given `tenantId` and `classificationCode` whose validity period covers `operationDate` (half-open: `validFrom <= operationDate < validTo`, or `validFrom <= operationDate` when `validTo` is null) |
| Empty return | Valid — means no matching rule was found; interactor derives `RULE_NOT_FOUND` |
| Multiple records per `TaxType` | Valid at this layer — interactor derives `RULE_AMBIGUOUS` |
| Exception | Throws `RuleGatewayUnavailableException` (checked domain exception, see §2.3) if the persistent store is unreachable |
| Filtering | The gateway implementation **must** apply `tenantId` as a mandatory filter — it must never return rules belonging to a different tenant |
| Non-PUBLISHED | `DRAFT` and `SUPERSEDED` records must never appear in the returned list regardless of date match |

The gateway performs no ambiguity check and no TaxType multiplicity check — those are interactor responsibilities.

### 2.2 `RuleResolutionRequest`

Lives in module `tax-usecases`, package `tax.usecases.gateway`.

| Field | Java type | Constraint |
|---|---|---|
| `tenantId` | `UUID` | not null; source: authenticated `TaxOperationInput.tenantId` |
| `classificationCode` | `ClassificationCode` | not null |
| `operationDate` | `LocalDate` | not null; already validated `<= today` by input boundary |

No additional validation is performed on this type — preconditions are enforced by the input boundary before the interactor constructs this request.

### 2.3 `RuleGatewayUnavailableException`

Domain checked exception. Lives in `tax-usecases`. Contains no stack-trace details that could expose infrastructure topology.

| Field | Type | Value |
|---|---|---|
| `message` | `String` | "rule store unavailable" |

The interactor catches this exception and converts it to `DomainError(DEPENDENCY_UNAVAILABLE, "rule store unavailable")` before returning to the output boundary.

---

## 3. Resolution algorithm (interactor responsibility)

The following steps are normative. An implementation is correct if and only if it produces the same outputs for the same inputs as this algorithm.

```
function resolveRules(input: TaxOperationInput, gateway: TaxRuleGateway,
                      requiredTaxTypes: Set<TaxType>): Either<DomainError, List<TaxRuleSnapshot>>

  1. Build request:
       request = RuleResolutionRequest(
                   tenantId           = input.tenantId,
                   classificationCode = input.classificationCode,
                   operationDate      = input.operationDate
                 )

  2. Query gateway:
       try:
         candidates = gateway.findPublished(request)
       catch RuleGatewayUnavailableException:
         return Left(DomainError(DEPENDENCY_UNAVAILABLE, "rule store unavailable", correlationId))

  3. Group by TaxType:
       grouped = candidates.groupBy(snapshot -> snapshot.taxType())
       // grouped: Map<TaxType, List<TaxRuleSnapshot>>

  4. For each taxType in requiredTaxTypes (in TaxType.ordinal order: CBS, then IBS):
       matches = grouped.getOrDefault(taxType, emptyList())

       if matches.isEmpty():
         return Left(DomainError(
           RULE_NOT_FOUND,
           "no published rule for " + input.classificationCode.value()
             + " [" + taxType.name() + "] on " + input.operationDate,
           correlationId
         ))

       if matches.size() > 1:
         return Left(DomainError(
           RULE_AMBIGUOUS,
           matches.size() + " published rules match "
             + input.classificationCode.value()
             + " [" + taxType.name() + "] on " + input.operationDate
             + "; resolve overlap before proceeding",
           correlationId
         ))

  5. Collect exactly one snapshot per required TaxType (in CBS-first order):
       resolved = requiredTaxTypes
                    .stream()
                    .sorted(Comparator.comparingInt(TaxType::ordinal))
                    .map(t -> grouped.get(t).get(0))
                    .collect(toUnmodifiableList())

  6. return Right(resolved)
```

**MVP fixed value:** `requiredTaxTypes = EnumSet.of(CBS, IBS)`. The interactor must not hardcode this value inline — it must be injected so the set can be changed without altering the algorithm. If `requiredTaxTypes` is empty the interactor must throw `IllegalStateException` (programming error, not a domain error).

---

## 4. Half-open validity semantics

A `TaxRuleSnapshot` with period `[validFrom, validTo)` covers operation date `d` when:

```
d >= validFrom
  AND
(validTo == null OR d < validTo)
```

| Case | Covered? |
|---|---|
| `d == validFrom` | YES (inclusive lower bound) |
| `d == validTo - 1 day` | YES (last day before exclusive upper bound) |
| `d == validTo` | NO (exclusive upper bound) |
| `d > validTo` | NO |
| `d < validFrom` | NO |
| `validTo == null` and `d >= validFrom` | YES (open-ended, no expiry) |

The gateway implementation must translate this semantics into the persistence query. For PostgreSQL, the equivalent SQL predicate is:

```sql
WHERE tenant_id = :tenantId
  AND classification_code = :classificationCode
  AND status = 'PUBLISHED'
  AND valid_from <= :operationDate
  AND (valid_to IS NULL OR valid_to > :operationDate)
```

The domain layer does not contain SQL — this predicate is specified here as the normative reference for the adapter implementation test.

---

## 5. Snapshot hash algorithm

Completes `TaxRuleSnapshot.snapshotHash` defined in SPEC-TAX-001 §2.4. The hash is computed when a rule is first stored and must not change thereafter. It identifies the immutable content of a rule version for audit purposes.

**Algorithm:**

1. Build a `TreeMap<String, String>` (sorted ascending by key) with entries:
   - `"classificationCode"` → `classificationCode.value()`
   - `"rateDecimal"` → `rateDecimal.value().toPlainString()` (no scientific notation; plain decimal string)
   - `"status"` → `status.name()` (always `"PUBLISHED"` for a valid snapshot)
   - `"taxType"` → `taxType.name()`
   - `"tenantId"` → `tenantId.toString()` (lowercase UUID)
   - `"validFrom"` → `period.validFrom().toString()` (ISO-8601: `yyyy-MM-dd`)
   - `"validTo"` → `period.validTo() == null ? "open" : period.validTo().toString()`
2. Serialise to compact JSON (no whitespace, keys in `TreeMap` order):  
   `{"classificationCode":"...","rateDecimal":"...","status":"PUBLISHED","taxType":"...","tenantId":"...","validFrom":"...","validTo":"..."}`
3. Compute SHA-256 of the UTF-8 bytes of that string.
4. Encode as lowercase hex (64 characters).

The `snapshotHash` is **not** the same as `TaxOperationInput.inputCanonicalHash` (defined in SPEC-TAX-001 §4). One identifies a rule version; the other identifies a specific calculation request.

---

## 6. Invariants

The following must hold at all times and must be verifiable without any framework wiring.

| ID | Invariant |
|---|---|
| INV-RR-001 | `findPublished` never returns a snapshot with `status != PUBLISHED` |
| INV-RR-002 | `findPublished` never returns a snapshot belonging to a `tenantId` other than `request.tenantId` |
| INV-RR-003 | `findPublished` never returns a snapshot whose period does not cover `request.operationDate` using the half-open semantics in §4 |
| INV-RR-004 | Ambiguity check is performed by the interactor; two PUBLISHED rules with the same `taxType` for the same `classificationCode` on the same date must yield `RULE_AMBIGUOUS`, never a silent winner |
| INV-RR-005 | A required `TaxType` with zero matching rules must yield `RULE_NOT_FOUND`, never a default or zero-rate substitution |
| INV-RR-006 | The resolved `List<TaxRuleSnapshot>` is ordered CBS before IBS |
| INV-RR-007 | Calling `resolveRules` with identical `(tenantId, classificationCode, operationDate)` and identical DB state returns identical snapshots (determinism) |
| INV-RR-008 | `TaxRuleSnapshot.snapshotHash` is immutable after first storage; it must never be recomputed from a mutated rule record |
| INV-RR-009 | The `TaxRuleGateway` interface contains no Spring, JPA, Jackson, or HTTP types |
| INV-RR-010 | An empty `requiredTaxTypes` set is a programming error; the interactor must throw `IllegalStateException` before querying the gateway |

---

## 7. Failure modes

| Trigger | ErrorCode | Detail example |
|---|---|---|
| No PUBLISHED rule for required TaxType on date | `RULE_NOT_FOUND` | "no published rule for GOODS_GENERAL [CBS] on 2026-01-15" |
| Multiple PUBLISHED rules for same TaxType on date | `RULE_AMBIGUOUS` | "2 published rules match GOODS_GENERAL [CBS] on 2026-01-15; resolve overlap before proceeding" |
| Persistent store unreachable | `DEPENDENCY_UNAVAILABLE` | "rule store unavailable" |
| CBS found, IBS not found (each missing TaxType reported independently) | `RULE_NOT_FOUND` | "no published rule for GOODS_GENERAL [IBS] on 2026-01-15" |
| `DRAFT` or `SUPERSEDED` rule is the only record — no PUBLISHED exists | `RULE_NOT_FOUND` | (same as missing rule) |

The interactor reports the **first** error encountered in `TaxType.ordinal()` order (CBS before IBS). It does not accumulate multiple errors.

---

## 8. BDD acceptance scenarios (synthetic fixtures)

All rates, codes and tenant identifiers in scenarios below are **SYNTHETIC and illustrative only**.

### Scenario S-RR-001 — Valid two-TaxType resolution

```
Given  tenant T1 has two PUBLISHED snapshots:
         CBS: classificationCode = "GOODS_GENERAL"
              period = [2026-01-01, 2026-07-01)
              rateDecimal = 0.088000000  (synthetic)
         IBS: classificationCode = "GOODS_GENERAL"
              period = [2026-01-01, 2026-07-01)
              rateDecimal = 0.177000000  (synthetic)
  And  requiredTaxTypes = {CBS, IBS}
When   resolveRules is called with
         tenantId = T1, classificationCode = "GOODS_GENERAL", operationDate = 2026-01-15
Then   resolved list contains exactly 2 snapshots ordered [CBS, IBS]
  And  resolved[0].taxType = CBS, rateDecimal = 0.088000000
  And  resolved[1].taxType = IBS, rateDecimal = 0.177000000
  And  no DomainError is returned
```

### Scenario S-RR-002 — Single CBS-only rule (IBS not required)

```
Given  tenant T1 has one PUBLISHED CBS snapshot for "SERVICE_A"
         period = [2026-01-01, null)  (open-ended)
         rateDecimal = 0.050000000  (synthetic)
  And  requiredTaxTypes = {CBS}
When   resolveRules called with operationDate = 2026-06-01
Then   resolved list contains exactly 1 snapshot: CBS
  And  no DomainError is returned
```

### Scenario S-RR-003 — Inclusive lower bound (operationDate = validFrom)

```
Given  CBS snapshot: period = [2026-04-01, 2026-07-01)
When   operationDate = 2026-04-01  (equals validFrom)
Then   snapshot IS returned (lower bound is inclusive)
  And  resolveRules returns that snapshot without error
```

### Scenario S-RR-004 — Exclusive upper bound (operationDate = validTo)

```
Given  CBS snapshot: period = [2026-01-01, 2026-07-01)
When   operationDate = 2026-07-01  (equals validTo)
Then   snapshot is NOT returned (upper bound is exclusive)
  And  DomainError with code = RULE_NOT_FOUND
  And  detail contains "GOODS_GENERAL", "CBS", "2026-07-01"
```

### Scenario S-RR-005 — Last valid day (operationDate = validTo - 1 day)

```
Given  CBS snapshot: period = [2026-01-01, 2026-07-01)
When   operationDate = 2026-06-30  (one day before validTo)
Then   snapshot IS returned
  And  resolveRules returns that snapshot without error
```

### Scenario S-RR-006 — Open-ended rule (validTo = null)

```
Given  CBS snapshot: period = [2026-01-01, null)
When   operationDate = 2030-12-31  (far future)
Then   snapshot IS returned (open-ended rules have no expiry)
  And  resolveRules returns that snapshot without error

When   operationDate = 2025-12-31  (before validFrom)
Then   snapshot is NOT returned
  And  DomainError with code = RULE_NOT_FOUND
```

### Scenario S-RR-007 — No PUBLISHED rule → RULE_NOT_FOUND

```
Given  no PUBLISHED rule exists for "UNKNOWN_CODE" for any TaxType on 2026-01-15
When   resolveRules called with classificationCode = "UNKNOWN_CODE", operationDate = 2026-01-15
Then   DomainError with code = RULE_NOT_FOUND
  And  detail contains "UNKNOWN_CODE" and "CBS" and "2026-01-15"
```

### Scenario S-RR-008 — DRAFT rule not eligible

```
Given  a DRAFT CBS snapshot for "GOODS_GENERAL" that covers 2026-01-15
  And  no PUBLISHED snapshot exists for the same code and date
When   resolveRules called
Then   DomainError with code = RULE_NOT_FOUND
  And  the DRAFT snapshot is never returned
```

### Scenario S-RR-009 — SUPERSEDED rule not eligible

```
Given  a SUPERSEDED CBS snapshot for "GOODS_GENERAL" that covers 2026-01-15
  And  no PUBLISHED snapshot exists for the same code and date
When   resolveRules called
Then   DomainError with code = RULE_NOT_FOUND
  And  the SUPERSEDED snapshot is never returned
```

### Scenario S-RR-010 — Overlapping PUBLISHED rules → RULE_AMBIGUOUS

```
Given  two PUBLISHED CBS snapshots for "GOODS_GENERAL", both covering 2026-01-15:
         Rule A: period = [2026-01-01, 2026-07-01), rateDecimal = 0.088000000
         Rule B: period = [2026-01-01, 2026-04-01), rateDecimal = 0.095000000
When   resolveRules called with operationDate = 2026-02-01 (covered by both)
Then   DomainError with code = RULE_AMBIGUOUS
  And  detail contains "2" and "GOODS_GENERAL" and "CBS" and "2026-02-01"
  And  no snapshot is returned as resolved
```

### Scenario S-RR-011 — Determinism across repeated calls

```
Given  one PUBLISHED CBS and one PUBLISHED IBS snapshot for "GOODS_GENERAL" on 2026-01-15
When   resolveRules is called 100 times with identical inputs
Then   every call returns the identical resolved list
  And  no call returns a different snapshot or a different ordering
```

### Scenario S-RR-012 — CBS found, IBS missing → RULE_NOT_FOUND for IBS

```
Given  one PUBLISHED CBS snapshot for "GOODS_GENERAL" on 2026-01-15
  And  no PUBLISHED IBS snapshot for "GOODS_GENERAL" on 2026-01-15
  And  requiredTaxTypes = {CBS, IBS}
When   resolveRules called
Then   DomainError with code = RULE_NOT_FOUND
  And  detail contains "IBS" (not "CBS")
  And  no partial resolved list is returned
```

### Scenario S-RR-013 — Gateway unavailable → DEPENDENCY_UNAVAILABLE

```
Given  the persistent store is unreachable (gateway throws RuleGatewayUnavailableException)
When   resolveRules called
Then   DomainError with code = DEPENDENCY_UNAVAILABLE
  And  detail = "rule store unavailable"
  And  no snapshot is returned
```

### Scenario S-RR-014 — Tenant isolation: rule from another tenant not returned

```
Given  PUBLISHED CBS and IBS snapshots for "GOODS_GENERAL" on 2026-01-15
         belonging to tenant T2
  And  no PUBLISHED snapshots belonging to tenant T1
When   resolveRules called with tenantId = T1
Then   DomainError with code = RULE_NOT_FOUND
  And  T2's snapshots are never included in the returned list
```

---

## 9. Integration test requirements

Each test must run against a real PostgreSQL instance (Testcontainers) with Flyway migrations applied. No mocked gateways for integration tests.

| ID | Scenario | Assertion |
|---|---|---|
| IT-RR-001 | Insert two PUBLISHED snapshots (CBS + IBS) for the same classification and date; call gateway | Returns exactly 2 snapshots; both cover the query date per §4 semantics |
| IT-RR-002 | Insert PUBLISHED snapshots for tenant T1 and T2; call gateway with T1 | Only T1 snapshots returned; T2 never visible |
| IT-RR-003 | Insert two overlapping PUBLISHED CBS snapshots; call interactor | `RULE_AMBIGUOUS` returned; no calculation proceeds |
| IT-RR-004 | Shut down the DB container; call gateway | `RuleGatewayUnavailableException` thrown; interactor returns `DEPENDENCY_UNAVAILABLE` |
| IT-RR-005 | Insert open-ended snapshot (`valid_to = NULL`); call gateway with date far in future | Snapshot returned correctly |
| IT-RR-006 | Retrieve stored snapshot; compute hash per §5; compare to `snapshotHash` column | Computed hash matches stored value exactly (64 lowercase hex chars) |
| IT-RR-007 | 50 concurrent threads call gateway with identical inputs against same DB rows | All threads receive identical snapshots; no deadlock; no data corruption |

---

## 10. Architecture constraints

| Constraint | Detail |
|---|---|
| `TaxRuleGateway` interface | Module `tax-usecases`, package `tax.usecases.gateway` |
| `RuleResolutionRequest` | Module `tax-usecases`, package `tax.usecases.gateway` |
| `RuleGatewayUnavailableException` | Module `tax-usecases`; not a `RuntimeException` subtype |
| `TaxRuleSnapshot` | Module `tax-domain` (defined in SPEC-TAX-001 §2.4) |
| Gateway implementation | Module `tax-frameworks` (JPA/JDBC adapter); must not leak persistence types across the boundary |
| Interactor | Module `tax-usecases`; calls `TaxRuleGateway` and delegates arithmetic to `TaxCalculationService` |
| Forbidden imports in `tax-usecases` | `org.springframework.*`, `jakarta.persistence.*`, `com.fasterxml.jackson.*`, any HTTP package |
| ArchUnit rule | `noClasses().that().resideInAPackage("tax.usecases..").should().dependOnClassesThat().resideInAPackage("tax.frameworks..")` |
| Snapshot hash computation | Pure Java (`MessageDigest`, `TreeMap`, string concatenation); no JSON library in `tax-domain` |

Test class to be created in `tax-usecases` test sources: `RuleResolutionServiceTest` (JUnit 5 unit tests for the interactor algorithm, with a mock gateway). Integration tests in `tax-frameworks` test sources: `TaxRuleGatewayIntegrationTest` (Testcontainers).

---

## 11. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-RR-001 | Confirm that in MVP both CBS and IBS are always required; partial resolution (one type missing) must always fail rather than yielding a single-component result | Fiscal analyst |
| OQ-RR-002 | Confirm `RULE_AMBIGUOUS` reports the first conflicting TaxType only (CBS before IBS) rather than accumulating all conflicts in one error response | Domain owner |
| OQ-RR-003 | Confirm snapshot `engineVersion` field semantics: is it the engine version that *created* the rule record (audit trail), or the *minimum compatible* engine version for replay? | Architect |
| OQ-RR-004 | Confirm `tenantId` is included in `snapshotHash` (§5): without it, identical rules for two tenants share the same hash, which may be intentional (rule content equality) or a bug (tenant leakage in audit) | Security reviewer |
| OQ-RR-005 | Confirm whether the gateway must enforce a maximum number of returned rows (e.g., cap at 10) to guard against accidental table scan on a missing index | SRE / Platform |

---

## 12. Traceability

| Requirement | Covered by |
|---|---|
| RF-003 (resolve rule) | §2 gateway interface, §3 resolution algorithm, §8 scenarios |
| RNF-001 (determinism) | INV-RR-007, S-RR-011 |
| RNF-004 (OAuth2 tenant isolation) | INV-RR-002, S-RR-014, IT-RR-002 |
| RNF-005 (auditability) | §5 snapshot hash algorithm, INV-RR-008 |
| RNF-006 (modularity) | §10 architecture constraints |
| ADR-003 (versioned rules) | §4 half-open validity, §5 snapshot hash, INV-RR-008 |
| ADR-007 (synthetic boundary) | INV-RR-005 (no default rate), S-RR-007 through S-RR-009 |
| ADR-008 (clean architecture) | §10 — gateway interface in `tax-usecases`, implementation in `tax-frameworks` |
| SPEC-TAX-001 §2.4 | `TaxRuleSnapshot` type reused; §5 completes `snapshotHash` specification |
| SPEC-TAX-003 §2 | `TaxCalculationService.calculate` receives the resolved `List<TaxRuleSnapshot>` from this spec |
| TAX-004 | This document |

Test evidence: to be attached after G3. BDD scenarios S-RR-001 … S-RR-014 map to named JUnit 5 test methods in `RuleResolutionServiceTest`. Integration tests IT-RR-001 … IT-RR-007 map to `TaxRuleGatewayIntegrationTest`.
