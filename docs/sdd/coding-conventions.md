# Coding conventions

Conventions that apply to all production code in this project (`src/main/java/**`). Reviewers must reject PRs that violate these rules.

## Iteration — Streams over for loops

**Rule:** Do not write `for` or enhanced-`for` loops in production source code. Always use the Java Streams API.

**Applies to:** all files under `src/main/java/` in every Maven module.

**Rationale:**
- Streams are declarative and express *what* to compute, not *how* to iterate.
- Pipeline composition (`.filter`, `.map`, `.collect`) is more composable and testable than loop bodies.
- Eliminates accidental mutation of loop variables and mutable accumulators.
- Consistent with the functional, immutable style already mandated for domain objects.

**Examples:**

```java
// NOT ALLOWED
var resolved = new ArrayList<TaxRuleSnapshot>();
for (var type : sortedRequiredTypes) {
    resolved.add(byType.get(type));
}

// REQUIRED
var resolved = sortedRequiredTypes.stream()
        .map(byType::get)
        .collect(Collectors.toList());
```

```java
// NOT ALLOWED
int count = 0;
for (var s : snapshots) {
    if (s.status() == RuleStatus.PUBLISHED) count++;
}

// REQUIRED
long count = snapshots.stream()
        .filter(s -> s.status() == RuleStatus.PUBLISHED)
        .count();
```

**Exceptions:** `for` loops are permitted in test infrastructure only when concurrent scaffolding (e.g. submitting tasks to an `ExecutorService`) or index-based assertions cannot be expressed clearly as a stream pipeline. Document the reason inline when using this exception.

---

## Instantiation — Builders over constructors

**Rule:** POJOs and records must not be instantiated directly via their constructors. All instantiation in production code must go through a Lombok `@Builder`.

**Applies to:** all `record` and class declarations under `src/main/java/` in every Maven module.

**Implementation:** add `@Builder` (Lombok) to every record/class declaration. Lombok is declared as `<optional>true</optional>` in each module pom — it is a compile-time annotation processor with no runtime footprint.

**Rationale:**
- Builders make construction sites self-documenting: field names appear at the call site, not just positional arguments.
- Adding or reordering fields in a record does not silently break call sites — the builder setter name makes the mismatch visible at compile time.
- Consistent with the named-parameter idiom adopted across the codebase.

**Examples:**

```java
// NOT ALLOWED
var snapshot = new TaxRuleSnapshot(id, TaxType.CBS, classCode, period,
        rate, hash, RuleStatus.PUBLISHED, "1.0.0");

// REQUIRED
var snapshot = TaxRuleSnapshot.builder()
        .id(id)
        .taxType(TaxType.CBS)
        .classificationCode(classCode)
        .period(period)
        .rateDecimal(rate)
        .snapshotHash(hash)
        .status(RuleStatus.PUBLISHED)
        .engineVersion("1.0.0")
        .build();
```

**Exceptions:**
- Internal self-references inside the same class (e.g. `Money.add()` returning `new Money(...)`, `TaxRate.of()` returning `new TaxRate(...)`) are exempt — the class is its own authority on its invariants.
- Test fixture helpers may use constructors when the builder adds no readability benefit for single-field wrapper types. Document inline.

---

## Javadoc — required public API documentation

**Rule:** Every `public` or `protected` type, interface, method, and constant declared in `tax-domain`, `tax-usecases`, and `tax-interface-adapters` must carry a Javadoc comment. Framework-layer types in `tax-frameworks` are encouraged but not required.

**Applies to:** `src/main/java/**` in `tax-domain`, `tax-usecases`, `tax-interface-adapters`.

**Required elements:**

| Element | Minimum requirement |
|---|---|
| Public interface / class | One-sentence purpose; `@since` with spec ID (e.g. `@since SPEC-TAX-002`) |
| Public method | One-sentence description; one `@param` per parameter; `@return` for non-void; `@throws` for every checked exception |
| Public constant / enum value | One-sentence description of its meaning in the domain |
| `@Builder`-generated class | Javadoc on the class; individual builder field setters are exempt |

**Style rules:**

- Write in English. First sentence is a summary; end with a period.
- Describe the domain semantics (the *why*), not the Java mechanics (the *what*). `/** Returns the tenant identifier. */` is better than `/** Gets tenantId. */`.
- Do not repeat the method name in the comment (`/** Finds published rules. */` for `findPublished()` adds nothing — write `/** Returns all PUBLISHED snapshots whose validity period covers the given operation date. */`).
- Do not add `@author` tags — authorship is tracked by git history.
- Do not add `@version` tags — version is tracked by the Maven POM.
- Cite the governing spec where the behaviour is prescribed: `/** ... see SPEC-TAX-002 §4 for the half-open interval semantics. */`

**Examples:**

```java
/**
 * Locates all PUBLISHED {@link TaxRuleSnapshot} records whose validity period
 * covers {@code request.operationDate()} using a half-open interval
 * {@code [validFrom, validTo)}.
 *
 * @param request non-null resolution criteria; see SPEC-TAX-002 §2.2
 * @return unordered list of matching snapshots; empty if none found
 * @throws RuleGatewayUnavailableException if the persistent store is unreachable
 * @since SPEC-TAX-002
 */
List<TaxRuleSnapshot> findPublished(RuleResolutionRequest request)
        throws RuleGatewayUnavailableException;
```

**Enforcement:** Checkstyle `JavadocMethod` and `JavadocType` rules are configured in `quality/checkstyle.xml` (SPEC-TAX-013 §3). Missing Javadoc on public API is a build-breaking violation.
