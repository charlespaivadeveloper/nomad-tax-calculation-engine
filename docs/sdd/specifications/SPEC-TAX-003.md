# SPEC-TAX-003 — Decimal calculation engine

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-005 (Implement decimal calculation)  
**Last updated:** 2026-11-23  
**Requirements:** RF-004, RNF-001, RNF-002, RNF-006  
**Rounding policy authority:** SPEC-TAX-001 §3 — this spec applies that policy, never overrides it.  
**All rates and fixtures in this document are SYNTHETIC and must never be used as legally certified tax calculations.**

---

## 1. Purpose

Specify the pure deterministic `TaxCalculationService` domain service: the function that receives a validated taxable base and a resolved list of published rule snapshots, applies the rounding policy from SPEC-TAX-001 §3 to each component, sums the results, and returns a typed `CalculationComponents` value. This service contains no I/O, no persistence, no framework types, and no randomness. It is the innermost calculable unit of the engine and must be exercisable in total isolation from Spring, JPA, and HTTP.

---

## 2. Domain service interface

Lives in module `tax-domain`, package `tax.domain.calculation`.

```
TaxCalculationService
  calculate(taxableBase: BigDecimal, rules: List<TaxRuleSnapshot>): CalculationComponents
```

**Preconditions** (validated by caller before invoking; violated preconditions cause `DomainException(VALIDATION_ERROR)`):

| Precondition | Rule |
|---|---|
| `taxableBase` not null | — |
| `taxableBase > 0` | must be strictly positive |
| `taxableBase.scale() <= 4` | enforced at input boundary (SPEC-TAX-001 §2.3) |
| `rules` not null and not empty | at least one PUBLISHED snapshot required |
| every element of `rules` has `status == PUBLISHED` | non-PUBLISHED snapshots must never reach the calculator |
| no two elements of `rules` share the same `taxType` | one snapshot per tax type; duplicates indicate resolver failure |

**Postconditions** (guaranteed by any correct implementation):

| Postcondition | Rule |
|---|---|
| `components` non-null, non-empty | one entry per input snapshot |
| `components` ordered CBS before IBS | see §4 |
| each `component.amount` computed per SPEC-TAX-001 §3 | `HALF_EVEN`, scale 2 |
| `totalTax.amount == Σ component.amount` | exact addition at scale 2 |
| `totalTax.currency` matches all component currencies | homogeneous |
| result is a pure function of inputs | same `taxableBase` + same `rules` → identical result |

---

## 3. Output types

### `CalculationComponents`

Value object in `tax-domain`. Immutable.

| Field | Java type | Constraint |
|---|---|---|
| `components` | `List<TaxComponent>` | unmodifiable; ordered CBS first; non-empty |
| `totalTax` | `Money` | scale 2, `>= 0.00`; equals sum of `component.amount` values |

`TaxComponent` is defined in SPEC-TAX-001 §2.4. Repeated here for self-containment:

| Field | Java type | Constraint |
|---|---|---|
| `taxType` | `TaxType` | not null |
| `taxableBase` | `BigDecimal` | scale 4; equals the input `taxableBase` |
| `rate` | `BigDecimal` | scale 9; equals `snapshot.rateDecimal.value()` |
| `amount` | `Money` | `taxableBase × rate`, rounded scale 2 `HALF_EVEN` |

### `DomainException`

Thrown (not returned) by the calculator for precondition violations only.

| Field | Type | Value |
|---|---|---|
| `code` | `ErrorCode` | `VALIDATION_ERROR` |
| `detail` | `String` | names the violated precondition |

---

## 4. Computation algorithm

The following steps are normative. An implementation is correct if and only if its output matches these steps for all inputs.

```
function calculate(taxableBase, rules):

  1. Assert preconditions (see §2). Throw DomainException on failure.

  2. For each snapshot in rules (processed in TaxType.ordinal order: CBS=0, IBS=1):
       rate        ← snapshot.rateDecimal.value()          // BigDecimal, scale 9
       intermediate ← taxableBase.multiply(rate, DECIMAL128) // scale ~13–34 dp
       amount      ← intermediate.setScale(2, HALF_EVEN)   // final scale 2
       component   ← TaxComponent(
                       taxType      = snapshot.taxType,
                       taxableBase  = taxableBase.setScale(4, HALF_EVEN),
                       rate         = rate,
                       amount       = Money(amount, currency)
                     )
       append component to result list

  3. totalTax ← Money(ZERO, currency)
     for each component in result list:
       totalTax ← totalTax.add(component.amount)   // exact scale-2 addition

  4. return CalculationComponents(
               components = unmodifiableList(result list),
               totalTax   = totalTax
             )
```

**Currency source:** the currency must be passed alongside `taxableBase`; the calculator does not read it from snapshots (which carry no currency). The caller (use-case interactor) supplies it from the validated `TaxOperationInput`.

**Component ordering:** snapshots are processed in `TaxType` declaration order (`CBS` before `IBS`). The result list preserves this order regardless of the order the caller passes snapshots. Implementation must sort by `TaxType.ordinal()` before iteration (step 2).

---

## 5. Rounding policy (reference)

Authoritative definition is SPEC-TAX-001 §3. Summary for this spec:

| Step | MathContext / RoundingMode | Scale |
|---|---|---|
| Intermediate multiplication | `MathContext.DECIMAL128` | ~34 sig digits |
| Component amount | `HALF_EVEN` | 2 |
| Total (sum of exact scale-2 values) | exact (no rounding needed) | 2 |

`HALF_EVEN` examples:
- `0.5555` → `0.56` (digit before 5 is odd → rounds up)
- `0.5455` → `0.55` (digit before 5 is even → rounds down... wait, let me re-check)
- Actually HALF_EVEN: when the dropped portion is exactly 0.5, round to even last digit.
  - `0.5550` with scale 2 → digit is 5, dropped is 0.005, so: intermediate is 0.555 → last kept digit would be 5 (odd) → round up to 0.56
  - `0.5450` with scale 2 → intermediate is 0.545 → last kept digit would be 4 (even) → round down to 0.54
  - Actually, setScale(2, HALF_EVEN) on 0.555 → 0.56 (5 is odd, round up to 6 → even)
  - setScale(2, HALF_EVEN) on 0.545 → 0.54 (4 is even, stays)

No `double` or `float` type may appear anywhere in this computation path.

---

## 6. Engine versioning

The engine version identifies the specific implementation that produced a result. It is used in replay comparison (TAX-009/SPEC-TAX-008) to detect algorithmic drift.

| Aspect | Specification |
|---|---|
| Type | `String`, non-blank, semantic version (e.g., `"1.0.0"`) |
| Source | configuration value injected into the use-case interactor; not hardcoded in the calculator |
| Propagation | use-case interactor reads it and embeds it in `CalculationResult.engineVersion` |
| Scope | `TaxCalculationService` itself does not carry or return `engineVersion`; it is a pure arithmetic service |
| Immutability | once a `CalculationResult` is persisted, its `engineVersion` must not change |

The calculator (`TaxCalculationService`) is intentionally unaware of `engineVersion`. The interactor (TAX-004 / TAX-006 concern) attaches it when assembling `CalculationResult` from `CalculationComponents`.

---

## 7. Invariants

| ID | Invariant |
|---|---|
| INV-DE-001 | The calculator is a pure function: no I/O, no mutable state, no randomness |
| INV-DE-002 | No `double` or `float` in any arithmetic path |
| INV-DE-003 | `totalTax.amount` equals the exact arithmetic sum of all `component.amount` values |
| INV-DE-004 | `components` list is never empty |
| INV-DE-005 | CBS component (if present) always precedes IBS component in `components` |
| INV-DE-006 | Each `component.taxableBase` equals the input `taxableBase` with scale 4 |
| INV-DE-007 | A component with `amount == 0.00` is valid and must be included (zero rate not silently suppressed) |
| INV-DE-008 | An empty `rules` list is a programming error; the calculator throws, never returns empty `components` |
| INV-DE-009 | Duplicate `TaxType` in `rules` is a programming error; the calculator throws `VALIDATION_ERROR` |
| INV-DE-010 | The result for identical `(taxableBase, rules)` is identical across JVM restarts and thread executions |

---

## 8. Failure modes

| Trigger | Behaviour |
|---|---|
| `rules` is null or empty | `DomainException(VALIDATION_ERROR, "rules must not be empty")` |
| `rules` contains two snapshots with same `taxType` | `DomainException(VALIDATION_ERROR, "duplicate TaxType CBS in rules")` |
| `rules` contains a snapshot with `status != PUBLISHED` | `DomainException(VALIDATION_ERROR, "snapshot <id> is not PUBLISHED")` |
| `taxableBase` null | `DomainException(VALIDATION_ERROR, "taxableBase must not be null")` |
| `taxableBase <= 0` | `DomainException(VALIDATION_ERROR, "taxableBase must be positive")` |
| `taxableBase.scale() > 4` | `DomainException(VALIDATION_ERROR, "taxableBase scale 5 exceeds maximum 4")` |
| Monetary overflow (`ArithmeticException` from DECIMAL128) | `DomainException(VALIDATION_ERROR, "taxableBase magnitude causes arithmetic overflow")` — expected to be unreachable in practice given NUMERIC(19,4) input bounds, but must be caught |

The calculator does **not** handle `RULE_NOT_FOUND` or `RULE_AMBIGUOUS` — those are resolver (TAX-004) concerns. By the time `calculate()` is called, exactly the right snapshots have been selected.

---

## 9. BDD acceptance scenarios (synthetic fixtures)

All rates in scenarios below are **SYNTHETIC and illustrative only**.

### Scenario S-DE-001 — Standard two-component calculation

```
Given  rules = [
         CBS snapshot: rateDecimal = 0.088000000, status = PUBLISHED
         IBS snapshot: rateDecimal = 0.177000000, status = PUBLISHED
       ]
  And  taxableBase = 1000.0000, currency = BRL
When   calculate(taxableBase, rules) is called
Then   components has 2 entries, ordered [CBS, IBS]
  And  components[0].taxType       = CBS
  And  components[0].taxableBase   = 1000.0000
  And  components[0].rate          = 0.088000000
  And  components[0].amount.amount = 88.00
       (1000.0000 × 0.088000000 = 88.0000000000000 → scale 2 HALF_EVEN → 88.00)
  And  components[1].taxType       = IBS
  And  components[1].amount.amount = 177.00
       (1000.0000 × 0.177000000 = 177.0000000000000 → scale 2 HALF_EVEN → 177.00)
  And  totalTax.amount = 265.00   (88.00 + 177.00)
```

### Scenario S-DE-002 — Single CBS-only component

```
Given  rules = [CBS snapshot: rateDecimal = 0.088000000, status = PUBLISHED]
  And  taxableBase = 500.0000, currency = BRL
When   calculate(taxableBase, rules) is called
Then   components has exactly 1 entry
  And  components[0].taxType       = CBS
  And  components[0].amount.amount = 44.00   (500.0000 × 0.088000000 = 44.00)
  And  totalTax.amount             = 44.00
```

### Scenario S-DE-003 — Single IBS-only component

```
Given  rules = [IBS snapshot: rateDecimal = 0.177000000, status = PUBLISHED]
  And  taxableBase = 200.0000, currency = BRL
When   calculate(taxableBase, rules) is called
Then   components has exactly 1 entry with taxType = IBS
  And  components[0].amount.amount = 35.40   (200.0000 × 0.177000000 = 35.4000 → 35.40)
  And  totalTax.amount             = 35.40
```

### Scenario S-DE-004 — HALF_EVEN: round-half-up (digit before 5 is odd)

```
Given  CBS snapshot: rateDecimal = 0.088000000
  And  taxableBase = 100.0100  (scale 4 OK)
When   calculate is called
Then   components[0].amount.amount = 8.80
       Proof: 100.0100 × 0.088000000 = 8.800880000000 → setScale(2, HALF_EVEN) → 8.80
       (dropped portion 0.0088 < 0.005 → truncates; correct answer 8.80)
```

### Scenario S-DE-005 — HALF_EVEN: exact half (round to even)

```
Given  CBS snapshot: rateDecimal = 0.055000000
  And  taxableBase = 10.0000
When   calculate is called
Then   intermediate = 10.0000 × 0.055000000 = 0.5500000000000
  And  setScale(2, HALF_EVEN): dropped = exactly 0.005
       last retained digit would be 5 (odd) → round up → 0.56
  And  components[0].amount.amount = 0.56

Given  CBS snapshot: rateDecimal = 0.045000000
  And  taxableBase = 10.0000
When   calculate is called
Then   intermediate = 10.0000 × 0.045000000 = 0.4500000000000
  And  setScale(2, HALF_EVEN): dropped = exactly 0.005... wait:
       0.45 → digit before boundary is 4 (even) → 0.45 → no rounding needed
       Actually 0.4500 setScale(2) = 0.45 (exact, no half-case)

Given  CBS snapshot: rateDecimal = 0.055000000
  And  taxableBase = 20.0000
When   calculate is called
Then   intermediate = 20.0000 × 0.055000000 = 1.1000000000000
  And  setScale(2, HALF_EVEN) = 1.10 (exact, no rounding needed)

Given  CBS snapshot: rateDecimal = 0.075000000
  And  taxableBase = 3.0000
When   calculate is called
Then   intermediate = 3.0000 × 0.075000000 = 0.2250000000000
  And  setScale(2, HALF_EVEN): 0.225 → dropped 0.005, last kept digit 2 (even) → round down → 0.22
  And  components[0].amount.amount = 0.22
```

### Scenario S-DE-006 — Component amount rounds to 0.00 (included, not suppressed)

```
Given  CBS snapshot: rateDecimal = 0.000100000  (synthetic very low rate)
  And  taxableBase = 0.0100  (minimum non-zero scale-4 above 0)
When   calculate is called
Then   intermediate = 0.0100 × 0.000100000 = 0.0000010000000
  And  setScale(2, HALF_EVEN) = 0.00
  And  components[0].amount.amount = 0.00
  And  the component IS included in the result (INV-DE-007)
  And  totalTax.amount = 0.00
```

### Scenario S-DE-007 — Large taxable base stays precise (no overflow)

```
Given  CBS snapshot: rateDecimal = 0.088000000
  And  taxableBase = 999999999999999.9999   (maximum NUMERIC(19,4) with 15 integer digits)
When   calculate is called
Then   intermediate = 999999999999999.9999 × 0.088000000
                    = 87999999999999.999912000000000  (DECIMAL128 preserves all digits)
  And  setScale(2, HALF_EVEN) = 88000000000000.00
  And  no ArithmeticException is thrown
  And  components[0].amount.amount = 88000000000000.00
```

### Scenario S-DE-008 — Component ordering: IBS snapshot passed first, CBS still leads

```
Given  rules passed in order [IBS snapshot, CBS snapshot]
When   calculate is called
Then   components[0].taxType = CBS   (CBS always first regardless of input order)
  And  components[1].taxType = IBS
```

### Scenario S-DE-009 — Determinism across multiple calls

```
Given  taxableBase = 1234.5678
  And  CBS rule rateDecimal = 0.088000000
  And  IBS rule rateDecimal = 0.177000000
When   calculate is called 1000 times with identical inputs
Then   every result has identical components[*].amount and totalTax.amount
  And  no result differs from the first
```

### Scenario S-DE-010 — Empty rules list rejected

```
Given  rules = []  (empty list)
When   calculate is called
Then   DomainException thrown with code = VALIDATION_ERROR
  And  detail contains "rules must not be empty"
  And  no CalculationComponents is returned
```

### Scenario S-DE-011 — Duplicate TaxType in rules rejected

```
Given  rules = [CBS snapshot A, CBS snapshot B]  (two CBS snapshots)
When   calculate is called
Then   DomainException thrown with code = VALIDATION_ERROR
  And  detail references "duplicate TaxType CBS"
```

### Scenario S-DE-012 — Non-PUBLISHED snapshot rejected

```
Given  rules = [CBS snapshot with status = DRAFT]
When   calculate is called
Then   DomainException thrown with code = VALIDATION_ERROR
  And  detail references snapshot id and "not PUBLISHED"
```

### Scenario S-DE-013 — totalTax equals exact sum (property)

```
For any valid (taxableBase, rules) input:
  Let result = calculate(taxableBase, rules)
  Then result.totalTax.amount
       == result.components.stream()
              .map(c -> c.amount().amount())
              .reduce(ZERO, BigDecimal::add)
              .setScale(2, HALF_EVEN)
```

---

## 10. Property-based test specification

The following properties must be verified with a property-based testing library (e.g., jqwik or QuickCheck-style) in addition to the BDD scenarios above. These are not examples — the test framework should generate arbitrary inputs within the stated bounds.

| Property | Bounds | Assertion |
|---|---|---|
| P-001 Determinism | taxableBase ∈ (0, 10^15), rate ∈ (0.000000001, 0.999999999), 1–2 rules | Same inputs → same output on 1 000 runs |
| P-002 Non-negativity | Same bounds | All `component.amount.amount() >= 0` |
| P-003 Total consistency | Same bounds | `totalTax.amount == Σ component.amount.amount()` |
| P-004 Component count | 1–2 valid rules | `components.size() == rules.size()` |
| P-005 Scale integrity | Same bounds | Each `component.amount.scale() == 2`; `totalTax.scale() == 2` |
| P-006 TaxType bijection | 1–2 rules with distinct TaxType | `components[i].taxType == rules_sorted[i].taxType` |

---

## 11. Architecture constraints

| Constraint | Detail |
|---|---|
| Module | `tax-domain` |
| Package | `tax.domain.calculation` |
| Allowed imports | `java.math.*`, `java.util.*`, `java.time.*`, own domain types |
| Forbidden imports | `org.springframework.*`, `jakarta.*`, `com.fasterxml.*`, any HTTP/JPA/JSON library |
| State | `TaxCalculationService` implementation must be stateless (safe to use as a singleton) |
| Thread safety | `calculate()` must be safe to call concurrently without synchronisation |
| ArchUnit rule | `noClasses().that().resideInAPackage("tax.domain..").should().dependOnClassesThat().resideInAPackage("org.springframework..")` |

Test class to be created in `tax-domain` test sources: `TaxCalculationServiceTest` (JUnit 5 + jqwik for property tests).

---

## 12. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-DE-001 | Confirm `HALF_EVEN` is acceptable (vs. `HALF_UP` which some Brazilian fiscal systems use) | Fiscal analyst |
| OQ-DE-002 | Confirm zero-amount component (amount = 0.00) must be included rather than suppressed; some downstream consumers may not expect it | Domain owner |
| OQ-DE-003 | Confirm maximum `taxableBase` is 999,999,999,999,999.9999 (15 integer + 4 decimal digits from NUMERIC(19,4)); any higher bound requires a different DB column type | Domain owner |
| OQ-DE-004 | Confirm CBS and IBS are the only `TaxType` values in scope for MVP; any addition requires this spec and domain enum to change | Fiscal analyst |

---

## 13. Traceability

| Requirement | Covered by |
|---|---|
| RF-004 (calculate) | §2 service contract, §4 algorithm, §9 scenarios |
| RNF-001 (determinism) | INV-DE-001, INV-DE-010, S-DE-009, P-001 |
| RNF-002 (decimal precision) | §5 rounding policy, INV-DE-002, S-DE-004, S-DE-005, P-005 |
| RNF-006 (modularity) | §11 architecture constraints |
| ADR-002 (decimal engine) | §4 algorithm uses `BigDecimal` from strings, `DECIMAL128`, `HALF_EVEN`, no float |
| ADR-008 (clean architecture) | §11 module = `tax-domain`; forbidden imports listed |
| SPEC-TAX-001 §3 | §5 applies that policy; §4 algorithm cites specific classes |
| TAX-005 | This document |

Test evidence: to be attached after G3. BDD scenarios S-DE-001 … S-DE-013 and property tests P-001 … P-006 map to methods in `TaxCalculationServiceTest` (class to be created in `tax-domain` module).
