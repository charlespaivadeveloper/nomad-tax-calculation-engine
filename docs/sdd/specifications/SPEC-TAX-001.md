# SPEC-TAX-001 — Domain model and invariants

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-001  
**Last updated:** 2026-11-23  
**Requirements:** RF-001, RF-002, RF-004, RNF-001, RNF-002, RNF-006  
**All rates, classifications and fixtures in this document are SYNTHETIC and must never be used as legally certified tax calculations.**

---

## 1. Purpose

Define the pure domain model for the tax calculation engine: value objects, entities, enumerations, the rounding and scale policy, and the canonical hash contract. All types in this layer must be framework-agnostic — no Spring, JPA, Jackson, or HTTP types allowed. This layer is the dependency target of every outer layer; it may import nothing except the Java standard library and approved pure-domain utilities (e.g., `java.math`, `java.time`, `java.util`).

---

## 2. Domain types

### 2.1 Enumerations

**`Currency`**  
Values: `BRL`  
Constraint: exactly one value in MVP; unknown currencies rejected at boundary.

**`TaxType`**  
Values: `CBS`, `IBS`  
CBS = federal contribution (synthetic rate). IBS = state/municipal tax (synthetic rate).  
No legal characterisation is implied by these labels.

**`SimulationContext`**  
Values: `SYNTHETIC`  
Every `CalculationResult` produced by this engine carries `SYNTHETIC`. A result may never be presented as a legally certified calculation.

**`RuleStatus`**  
Values: `PUBLISHED`, `DRAFT`, `SUPERSEDED`  
Only `PUBLISHED` rules are eligible for resolution.

**`CalculationStatus`**  
Values: `CALCULATED`

**`ErrorCode`**  
| Code | HTTP status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Input field fails type/range/format constraint |
| `RULE_NOT_FOUND` | 422 | No PUBLISHED rule matches the query |
| `RULE_AMBIGUOUS` | 409 | More than one PUBLISHED rule matches (overlap) |
| `DEPENDENCY_UNAVAILABLE` | 503 | Persistent store unreachable |
| `TENANT_NOT_FOUND` | 403 | Tenant identity missing or unresolvable from JWT |

---

### 2.2 Value objects

All value objects are immutable. Construction fails fast with `IllegalArgumentException` if any constraint below is violated.

#### `Money`

| Field | Java type | Constraint |
|---|---|---|
| `amount` | `BigDecimal` | scale = 2, `HALF_EVEN`; `>= 0.00` |
| `currency` | `Currency` | not null |

Factory: `Money.of(String amountStr, Currency currency)` — parses `amountStr` via `new BigDecimal(amountStr)` then scales to 2 dp with `HALF_EVEN`; throws `VALIDATION_ERROR` if string is not a valid decimal or is negative.

Arithmetic: `add(Money other)` asserts same currency, returns new `Money` with `HALF_EVEN` scale-2 result. No subtraction or multiplication exposed from `Money` itself.

#### `TaxRate`

| Field | Java type | Constraint |
|---|---|---|
| `value` | `BigDecimal` | scale = 9, `HALF_EVEN`; `0.000000001 <= value <= 0.999999999` |

Factory: `TaxRate.of(String rateStr)` — parses then scales to 9 dp; throws `VALIDATION_ERROR` if out of range or not parseable.

#### `TaxPeriod`

| Field | Java type | Constraint |
|---|---|---|
| `validFrom` | `LocalDate` | not null |
| `validTo` | `LocalDate` | may be null (open-ended); if set, must be strictly after `validFrom` |

Semantics: half-open `[validFrom, validTo)`. An operation date `d` is covered when `d >= validFrom && (validTo == null || d < validTo)`.

#### `ClassificationCode`

| Field | Java type | Constraint |
|---|---|---|
| `value` | `String` | non-blank; 1–64 characters; pattern `[A-Z0-9_]+` |

#### `OperationId`

| Field | Java type | Constraint |
|---|---|---|
| `value` | `String` | non-blank; 1–255 characters |

---

### 2.3 Input boundary DTO

#### `TaxOperationInput`

Crosses the use-case input boundary. Constructed by the interface-adapters layer from the HTTP request; passed into the interactor.

| Field | Java type | Constraint |
|---|---|---|
| `tenantId` | `UUID` | not null; source: JWT claim `tenant_id` |
| `operationId` | `OperationId` | not null |
| `idempotencyKey` | `String` | non-blank; 1–128 characters; source: `Idempotency-Key` header |
| `operationDate` | `LocalDate` | not null; must be `<= LocalDate.now()` (no future-dated calculations) |
| `currency` | `Currency` | not null; must be `BRL` in MVP |
| `classificationCode` | `ClassificationCode` | not null |
| `taxableBase` | `BigDecimal` | parsed from string; `> 0.00`; maximum scale 4 |
| `simulationContext` | `SimulationContext` | not null; must be `SYNTHETIC` |

Validation: all constraints above are checked before the input enters any use-case logic. A single violated constraint must result in `ErrorCode.VALIDATION_ERROR`; the `detail` field names the first failing field.

---

### 2.4 Domain entities

#### `TaxRuleSnapshot`

Immutable record of a rule version selected during resolution. The entity may only be created by the rule-resolution gateway (TAX-004 concern); the domain model defines its contract.

| Field | Java type | Constraint |
|---|---|---|
| `id` | `UUID` | not null; stable across replays |
| `taxType` | `TaxType` | not null |
| `classificationCode` | `ClassificationCode` | not null |
| `period` | `TaxPeriod` | not null |
| `rateDecimal` | `TaxRate` | not null |
| `snapshotHash` | `String` | SHA-256 hex string, exactly 64 lowercase hex chars |
| `status` | `RuleStatus` | must be `PUBLISHED` for a snapshot to be used in a calculation |
| `engineVersion` | `String` | non-blank semantic version string |

Invariant: a `TaxRuleSnapshot` with `status != PUBLISHED` must never be accepted by any calculation use case.

#### `TaxComponent`

A single tax-type contribution within a calculation result. Computed, not stored separately at domain level (persistence representation is a concern of TAX-003).

| Field | Java type | Constraint |
|---|---|---|
| `taxType` | `TaxType` | not null |
| `taxableBase` | `BigDecimal` | scale 4; > 0 |
| `rate` | `BigDecimal` | scale 9; same value as `TaxRuleSnapshot.rateDecimal.value` |
| `amount` | `Money` | `taxableBase × rate`, rounded to scale 2 `HALF_EVEN` |

Computation contract (see section 3 for the full rounding policy):

```
amount = (taxableBase.multiply(rate, MathContext.DECIMAL128))
             .setScale(2, RoundingMode.HALF_EVEN)
```

---

### 2.5 Output boundary DTO

#### `CalculationResult`

Crosses the use-case output boundary. Constructed by the interactor; consumed by the interface-adapters layer for HTTP serialisation.

| Field | Java type | Constraint |
|---|---|---|
| `calculationId` | `UUID` | not null; stable identifier for this calculation |
| `status` | `CalculationStatus` | always `CALCULATED` |
| `currency` | `Currency` | matches request currency |
| `components` | `List<TaxComponent>` | non-null; non-empty; ordered CBS before IBS |
| `totalTax` | `Money` | exact sum of all `component.amount` values; scale 2 |
| `ruleVersionId` | `UUID` | references the `TaxRuleSnapshot.id` used |
| `engineVersion` | `String` | matches `TaxRuleSnapshot.engineVersion` |
| `simulationContext` | `SimulationContext` | always `SYNTHETIC` |
| `inputCanonicalHash` | `String` | SHA-256 hex of the canonical input (see section 4) |

#### `DomainError`

Returned by use-case interactors instead of throwing unchecked exceptions across boundaries.

| Field | Java type | Constraint |
|---|---|---|
| `code` | `ErrorCode` | not null |
| `detail` | `String` | non-blank human-readable explanation |
| `correlationId` | `UUID` | not null; propagated from request context |

---

## 3. Rounding and scale policy

**This policy is authoritative for the domain layer. Any deviation requires an ADR amendment.**

| Step | Operation | Scale | RoundingMode |
|---|---|---|---|
| Input parse — taxable base | `new BigDecimal(inputStr)` → reject if scale > 4 | 4 | — |
| Input parse — rate | `new BigDecimal(rateStr).setScale(9, HALF_EVEN)` | 9 | `HALF_EVEN` |
| Intermediate multiplication | `taxableBase.multiply(rate, MathContext.DECIMAL128)` | DECIMAL128 (34 sig. digits) | — |
| Component amount final | `.setScale(2, HALF_EVEN)` | 2 | `HALF_EVEN` |
| Total tax | sum of already-scale-2 component amounts; `setScale(2, HALF_EVEN)` | 2 | `HALF_EVEN` |

Rationale for `HALF_EVEN` (banker's rounding): statistical neutrality over large batches; standard in ISO 20022, Java `BigDecimal` default for financial arithmetic. Must not be changed without fiscal specialist review.

No `double` or `float` is used anywhere in domain calculations. No implicit narrowing or widening conversions.

---

## 4. Canonical input hash

The canonical hash is used by idempotency (TAX-007) and audit evidence (TAX-008). It must be reproducible from the same logical inputs regardless of serialization library or key ordering.

**Algorithm:**

1. Build a `TreeMap<String, String>` (sorted ascending by key) with entries:
   - `"classificationCode"` → `classificationCode.value()`
   - `"currency"` → `currency.name()`
   - `"operationDate"` → `operationDate.toString()` (ISO-8601, `yyyy-MM-dd`)
   - `"operationId"` → `operationId.value()`
   - `"simulationContext"` → `simulationContext.name()`
   - `"taxableBase"` → `taxableBase.toPlainString()` (no scientific notation)
   - `"tenantId"` → `tenantId.toString()` (lowercase UUID)
2. Serialise to JSON with no whitespace and keys in `TreeMap` order:  
   `{"classificationCode":"...","currency":"BRL","operationDate":"...","operationId":"...","simulationContext":"SYNTHETIC","taxableBase":"...","tenantId":"..."}`
3. Compute SHA-256 of the UTF-8 bytes of that string.
4. Encode as lowercase hex (64 chars).

The `idempotencyKey` is **not** included in the canonical hash — it is a client-supplied deduplication key tracked separately.

---

## 5. Invariants

The following must hold at all times and must be verifiable by unit test without any framework wiring.

| ID | Invariant |
|---|---|
| INV-001 | No `double` or `float` in any domain arithmetic path |
| INV-002 | Every `CalculationResult` carries `simulationContext = SYNTHETIC` |
| INV-003 | A missing or ambiguous rule is a hard error; no silent default rate is substituted |
| INV-004 | Equal `TaxOperationInput` values (same canonical hash) produced by the same rule version and engine version yield identical `CalculationResult` values |
| INV-005 | `totalTax` equals the exact sum of `component.amount` values at scale 2 |
| INV-006 | `components` list is never empty; CBS appears before IBS when both are present |
| INV-007 | `TaxRuleSnapshot` with `status != PUBLISHED` must never be used in a calculation |
| INV-008 | `tenantId` is always propagated from the authenticated JWT; it must never be accepted from the request body |
| INV-009 | No PII, token bytes, or raw secret values may appear in domain error details |
| INV-010 | All `Money` and `TaxRate` constructions from invalid strings throw before any state mutation |

---

## 6. Failure modes

| Trigger | ErrorCode | Detail example |
|---|---|---|
| Required field null or blank | `VALIDATION_ERROR` | "operationDate must not be null" |
| `taxableBase` string not parseable | `VALIDATION_ERROR` | "taxableBase '1,000.00' is not a valid decimal" |
| `taxableBase` scale > 4 | `VALIDATION_ERROR` | "taxableBase scale 5 exceeds maximum 4" |
| `taxableBase <= 0` | `VALIDATION_ERROR` | "taxableBase must be positive" |
| `operationDate` in the future | `VALIDATION_ERROR` | "operationDate 2027-01-01 must not be in the future" |
| `currency` not `BRL` | `VALIDATION_ERROR` | "currency USD is not supported" |
| `simulationContext` not `SYNTHETIC` | `VALIDATION_ERROR` | "simulationContext REAL is not permitted" |
| `classificationCode` pattern mismatch | `VALIDATION_ERROR` | "classificationCode 'goods-general' fails pattern [A-Z0-9_]+" |
| No PUBLISHED rule found for date and code | `RULE_NOT_FOUND` | "no published rule for GOODS_GENERAL on 2026-01-15" |
| Multiple PUBLISHED rules found | `RULE_AMBIGUOUS` | "2 rules match GOODS_GENERAL on 2026-01-15; resolve overlap" |
| Persistence store unreachable | `DEPENDENCY_UNAVAILABLE` | "rule store unavailable" |
| JWT missing `tenant_id` claim | `TENANT_NOT_FOUND` | "tenant identity not resolvable from token" |

---

## 7. BDD acceptance scenarios (synthetic fixtures)

All rates in scenarios below are **SYNTHETIC and illustrative only**.

### Scenario S-001 — Valid two-component calculation

```
Given  a PUBLISHED TaxRuleSnapshot for CBS
         classificationCode = "GOODS_GENERAL"
         period = [2026-01-01, 2026-07-01)
         rateDecimal = 0.088000000  (synthetic)
  And  a PUBLISHED TaxRuleSnapshot for IBS
         classificationCode = "GOODS_GENERAL"
         period = [2026-01-01, 2026-07-01)
         rateDecimal = 0.177000000  (synthetic)
When   a TaxOperationInput is submitted
         tenantId          = <any valid UUID>
         operationId       = "OP-2026-001"
         idempotencyKey    = "idem-001"
         operationDate     = 2026-01-15
         currency          = BRL
         classificationCode = "GOODS_GENERAL"
         taxableBase       = "1000.00"
         simulationContext = SYNTHETIC
Then   CalculationResult is returned with status CALCULATED
  And  components contains exactly 2 entries ordered [CBS, IBS]
  And  components[CBS].taxableBase = 1000.0000
  And  components[CBS].rate        = 0.088000000
  And  components[CBS].amount      = 88.00  (1000.00 × 0.088000000 = 88.000000000 → scale-2 HALF_EVEN → 88.00)
  And  components[IBS].taxableBase = 1000.0000
  And  components[IBS].rate        = 0.177000000
  And  components[IBS].amount      = 177.00 (1000.00 × 0.177000000 = 177.000000000 → scale-2 HALF_EVEN → 177.00)
  And  totalTax = 265.00  (88.00 + 177.00)
  And  simulationContext = SYNTHETIC
  And  inputCanonicalHash is a 64-char lowercase hex string
```

### Scenario S-002 — Rounding with HALF_EVEN

```
Given  a PUBLISHED CBS rule rateDecimal = 0.088000000 for "GOODS_GENERAL" on 2026-01-15
When   taxableBase = "100.01"
Then   components[CBS].amount = 8.80
       (100.01 × 0.088000000 = 8.80088000000 → setScale(2, HALF_EVEN) → 8.80)
```

### Scenario S-003 — HALF_EVEN tie-break (round half to even)

```
Given  a PUBLISHED CBS rule rateDecimal = 0.050000000 for "TEST_CODE" on 2026-01-15
When   taxableBase = "11.11"
Then   components[CBS].amount = 0.56
       (11.11 × 0.05 = 0.5555 → HALF_EVEN → 0.56, since digit before 5 is 5 which is odd)
```

### Scenario S-004 — Missing rule returns RULE_NOT_FOUND

```
Given  no PUBLISHED rule exists for classificationCode "UNKNOWN_CODE" on 2026-01-15
When   TaxOperationInput submitted with classificationCode = "UNKNOWN_CODE", operationDate = 2026-01-15
Then   DomainError returned with code = RULE_NOT_FOUND
  And  detail contains "UNKNOWN_CODE" and "2026-01-15"
```

### Scenario S-005 — Overlapping rules returns RULE_AMBIGUOUS

```
Given  two PUBLISHED CBS rules both matching "GOODS_GENERAL" on 2026-01-15
         Rule A: period [2026-01-01, 2026-07-01)
         Rule B: period [2026-01-01, 2026-04-01)
When   TaxOperationInput submitted
Then   DomainError returned with code = RULE_AMBIGUOUS
  And  detail references the conflict (count or rule IDs)
```

### Scenario S-006 — Rule period boundary (exclusive upper bound)

```
Given  a PUBLISHED CBS rule period = [2026-01-01, 2026-07-01)
When   operationDate = 2026-07-01   (equal to validTo → outside half-open interval)
Then   DomainError with code = RULE_NOT_FOUND
When   operationDate = 2026-06-30   (last valid day)
Then   CalculationResult with status = CALCULATED
```

### Scenario S-007 — Future date rejected

```
Given  operationDate = 2099-01-01
When   TaxOperationInput submitted
Then   DomainError with code = VALIDATION_ERROR
  And  detail references "operationDate"
```

### Scenario S-008 — Invalid taxableBase string

```
When   taxableBase = "1.000,00"  (locale-formatted, comma as thousands separator)
Then   DomainError with code = VALIDATION_ERROR, detail references "taxableBase"

When   taxableBase = "0.00000"  (scale 5 exceeds maximum 4)
Then   DomainError with code = VALIDATION_ERROR, detail references "taxableBase scale"

When   taxableBase = "-50.00"   (negative)
Then   DomainError with code = VALIDATION_ERROR, detail references "taxableBase"
```

### Scenario S-009 — Determinism (repeatability)

```
Given  the same TaxOperationInput values submitted twice with different idempotencyKey values
  And  the same PUBLISHED rule versions apply both times
Then   both results have identical:
         components[*].amount, totalTax, ruleVersionId, engineVersion, inputCanonicalHash
```

### Scenario S-010 — simulationContext enforced

```
When   simulationContext field is absent or set to any value other than SYNTHETIC
Then   DomainError with code = VALIDATION_ERROR, detail references "simulationContext"
```

---

## 8. Architecture constraints

- `TaxOperationInput`, `CalculationResult`, and `DomainError` live in module `tax-usecases` (input/output boundary interfaces).
- `Money`, `TaxRate`, `TaxPeriod`, `ClassificationCode`, `OperationId`, `TaxComponent`, `TaxRuleSnapshot`, all enumerations live in module `tax-domain`.
- `tax-domain` has zero external dependencies (no Spring, no JPA, no Jackson).
- `tax-usecases` depends only on `tax-domain`.
- Hash algorithm (`SHA-256`) and canonical serialisation reside in `tax-domain` as a pure utility — no JSON library may be injected; the canonical form is produced by string concatenation with the fixed key ordering specified in section 4.
- ArchUnit rule: no class in `tax.domain.*` or `tax.usecases.*` may import from `org.springframework.*`, `jakarta.persistence.*`, `com.fasterxml.jackson.*`, or any HTTP package.

---

## 9. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-001 | Confirm `HALF_EVEN` rounding is acceptable for synthetic CBS/IBS totals (no fiscal specialist requirement violated at synthetic level) | Fiscal analyst |
| OQ-002 | Confirm `taxableBase` max scale 4 is sufficient for all anticipated commodity classifications | Domain owner |
| OQ-003 | Confirm CBS and IBS are the only `TaxType` values needed in MVP (no ISS or others) | Fiscal analyst |
| OQ-004 | Confirm `operationDate <= today` constraint — does replay of old operations require past-date inputs? (Expected: yes, past-date allowed) | Domain owner |
| OQ-005 | Confirm canonical hash must include `tenantId` (required for cross-tenant uniqueness in idempotency store) | Security reviewer |

---

## 10. Traceability

| Requirement | Covered by |
|---|---|
| RF-001 (receive) | `TaxOperationInput` field definitions, section 2.3 |
| RF-002 (validate) | Validation constraints and failure modes, sections 2.3, 6 |
| RF-004 (calculate) | `TaxComponent` computation, rounding policy, sections 2.4, 3 |
| RNF-001 (determinism) | INV-004, S-009 |
| RNF-002 (decimal precision) | Section 3, INV-001 |
| RNF-006 (modularity) | Section 8, Clean Architecture module assignments |
| ADR-002 (decimal engine) | Section 3 — explicit `HALF_EVEN`, `DECIMAL128`, no float/double |
| ADR-007 (synthetic boundary) | INV-002, INV-003, `SimulationContext.SYNTHETIC`, S-010 |
| ADR-008 (clean architecture) | Section 8 |
| TAX-001 | This document |

Test evidence: to be attached after G3 implementation. Each scenario S-001 … S-010 maps to a named JUnit 5 test method in `TaxDomainInvariantTest` (class to be created in `tax-domain` module).
