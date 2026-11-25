# SPEC-TAX-013 — Code Quality Gates

**Status:** draft / awaiting G2 technical sign-off  
**Story:** TAX-012 (Documentation and showcase)  
**Last updated:** 2026-11-25  
**Requirements:** RNF-006 (modularity), RNF-007 (maintainability)  
**Related specs:** `docs/sdd/coding-conventions.md` (Javadoc, Streams, Builder rules)  

---

## 1. Purpose

Specify the static-analysis and style tooling that runs as part of the Maven `verify` lifecycle on every build. All tools must produce zero violations before any gate (G2, G3) is considered passing. No suppression annotations may be added solely to silence a tool without fixing the underlying issue; each suppression requires a comment citing a specific, documented reason.

Three tools are in scope: **PMD** (bug patterns and code style), **Checkstyle** (formatting and Javadoc), **SpotBugs** (bytecode-level bug detection).

---

## 2. PMD

### 2.1 Configuration

Configuration file: `quality/pmd.xml`  
Maven plugin: `maven-pmd-plugin` (bound to `verify` phase, `failOnViolation = true`).  
Ruleset base: PMD 7 — `category/java/bestpractices.xml`, `category/java/errorprone.xml`, `category/java/design.xml`, `category/java/codestyle.xml`.

### 2.2 Required rules (P0 — build-breaking)

| Rule | Rationale |
|---|---|
| `AvoidReassigningParameters` | Prevents subtle mutation bugs in domain methods |
| `GuardLogStatement` | Prevents string concatenation in disabled log levels |
| `CloseResource` | Ensures JDBC connections, streams returned to pool |
| `AvoidCatchingGenericException` | Forces explicit catch of `DataAccessException`, `RuleGatewayUnavailableException` |
| `SystemPrintln` | No `System.out/err` in production code; use SLF4J |
| `AvoidLiteralsInIfCondition` | Magic numbers must be named constants |
| `SimplifyBooleanReturns` | Eliminates `if (x) return true; else return false;` |
| `CollapsibleIfStatements` | Removes unnecessary nesting |
| `UseUtilityClass` | Utility classes (e.g. `CanonicalHashUtil`) must have private constructors |
| `NullAssignment` | No `x = null` assignments; use `Optional` |

### 2.3 Exclusions

- Test sources (`src/test/java/**`) are excluded from P0 rules except `CloseResource` and `AvoidCatchingGenericException`.
- Generated sources (`target/generated-sources/**`) are excluded entirely.
- Lombok-generated code is excluded via `suppressMarker` in `pom.xml`.

### 2.4 Severity levels

| Level | Action |
|---|---|
| P0 (error) | Build fails immediately |
| P1 (warning) | Build succeeds; listed in report; count tracked in CI trend |

---

## 3. Checkstyle

### 3.1 Configuration

Configuration file: `quality/checkstyle.xml`  
Maven plugin: `maven-checkstyle-plugin` (bound to `verify` phase, `failOnViolation = true`).  
Base style: Google Java Style Guide with the following project-specific overrides.

### 3.2 Required checks (P0 — build-breaking)

| Check | Setting | Rationale |
|---|---|---|
| `FileTabCharacter` | No tabs | Consistent indentation |
| `LineLength` | max 120 | Readable in side-by-side diffs |
| `LeftCurly` | `eol` | Opening braces on the same line |
| `NeedBraces` | all control structures | No braceless `if`/`for` |
| `WhitespaceAround` | operators, keywords | Consistent spacing |
| `UnusedImports` | error | No dead imports |
| `RedundantImport` | error | No duplicate imports |
| `JavadocMethod` | scope `public` | Public methods in `tax-domain`, `tax-usecases`, `tax-interface-adapters` must have Javadoc with `@param`, `@return`, `@throws` (see `coding-conventions.md`) |
| `JavadocType` | scope `public` | Public types must have a type-level Javadoc comment |
| `JavadocVariable` | scope `public` | Public constants must have Javadoc |
| `MissingDeprecated` | error | `@Deprecated` requires `@deprecated` Javadoc tag and vice versa |
| `ConstantName` | `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$` | Uppercase constants |
| `LocalVariableName` | `^[a-z][a-zA-Z0-9]*$` | camelCase locals |
| `MethodName` | `^[a-z][a-zA-Z0-9_]*$` | camelCase methods |

### 3.3 Suppressions

Suppression filter file: `quality/checkstyle-suppressions.xml`.  
Lombok-generated classes (identified by presence of `@Builder`, `@Data`, `@Value` on the class) may suppress `JavadocMethod` for generated builder/accessor methods only. The class-level Javadoc (`JavadocType`) remains mandatory.

### 3.4 Scope

- Applies to `src/main/java/**` in all four Maven modules.
- `JavadocMethod` and `JavadocType` checks apply to `tax-domain`, `tax-usecases`, and `tax-interface-adapters` only; `tax-frameworks` is exempt from Javadoc checks but not from formatting checks.
- Test sources are exempt from `JavadocMethod` and `JavadocType`.

---

## 4. SpotBugs

### 4.1 Configuration

Configuration file: `quality/spotbugs-exclude.xml`  
Maven plugin: `spotbugs-maven-plugin` (bound to `verify` phase, `failOnError = true`).  
Effort: `Max`; Threshold: `Medium` (reports Medium and High confidence findings).

### 4.2 Required detector categories (P0)

| Category | Key patterns caught |
|---|---|
| `CORRECTNESS` | Null dereference, incorrect `equals`/`hashCode`, uninitialized reads |
| `BAD_PRACTICE` | `equals`/`hashCode` inconsistency, `close()` not in `finally`/try-with-resources |
| `PERFORMANCE` | Inefficient string concatenation in loops, unnecessary boxing |
| `SECURITY` | SQL injection risk, trust-boundary violation, hardcoded credentials |
| `MT_CORRECTNESS` | Incorrect synchronization, unsynchronized access to shared mutable state |

### 4.3 Known acceptable exclusions

The `quality/spotbugs-exclude.xml` excludes:

| Pattern | Reason |
|---|---|
| Lombok-generated `equals`/`hashCode` in domain records | SpotBugs cannot trace Lombok-generated bytecode correctly; domain `@Builder` records are immutable value objects |
| JPA entity `@NoArgsConstructor(access=PROTECTED)` classes in `tax-frameworks` | Required for Hibernate proxy; SpotBugs incorrectly flags the unused protected constructor |
| Testcontainers lifecycle fields (`static PostgreSQLContainer<?>`) | Static lifecycle fields are intentional; `close()` is managed by the JUnit extension |

Each exclusion must cite this spec section and the SpotBugs bug code (e.g. `EQ_DOESNT_OVERRIDE_EQUALS`).

---

## 5. Maven build integration

All three tools are bound to the `verify` phase via the parent `pom.xml`:

```xml
<!-- quality/pom-fragment.xml — to be merged into root pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-pmd-plugin</artifactId>
    <configuration>
        <rulesets><ruleset>quality/pmd.xml</ruleset></rulesets>
        <failOnViolation>true</failOnViolation>
        <printFailingErrors>true</printFailingErrors>
        <excludeRoots>
            <excludeRoot>target/generated-sources</excludeRoot>
        </excludeRoots>
    </configuration>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
</plugin>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <configuration>
        <configLocation>quality/checkstyle.xml</configLocation>
        <suppressionsLocation>quality/checkstyle-suppressions.xml</suppressionsLocation>
        <failOnViolation>true</failOnViolation>
        <violationSeverity>warning</violationSeverity>
    </configuration>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
</plugin>

<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <configuration>
        <effort>Max</effort>
        <threshold>Medium</threshold>
        <excludeFilterFile>quality/spotbugs-exclude.xml</excludeFilterFile>
        <failOnError>true</failOnError>
    </configuration>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
</plugin>
```

Configuration files must exist at `quality/pmd.xml`, `quality/checkstyle.xml`, `quality/checkstyle-suppressions.xml`, and `quality/spotbugs-exclude.xml` before this spec is closed.

---

## 6. Acceptance criteria

### 6.1 Gate requirement

No G3 gate approval may be granted if any of the following produce violations:

| Tool | Zero-tolerance scope |
|---|---|
| PMD P0 rules | All production source (`src/main/java/**`) in all four modules |
| Checkstyle formatting | All production source |
| Checkstyle Javadoc | Public API types/methods in `tax-domain`, `tax-usecases`, `tax-interface-adapters` |
| SpotBugs CORRECTNESS + SECURITY | All production source; especially `MT_CORRECTNESS` for concurrent gateway paths |

### 6.2 BDD acceptance scenarios

```
Scenario QG-001 — Build fails on PMD violation
Given  a P0 PMD violation is introduced (e.g. System.out.println in production code)
When   mvn verify runs
Then   build exits non-zero before the test phase completes
  And  the report names the file, line, and rule

Scenario QG-002 — Build fails on missing public Javadoc
Given  a public method in tax-usecases has no Javadoc comment
When   mvn verify runs
Then   Checkstyle reports a JavadocMethod violation
  And  build exits non-zero

Scenario QG-003 — Build fails on SpotBugs null-dereference
Given  a potential null dereference is introduced in a gateway return path
When   mvn verify runs
Then   SpotBugs reports a NP_NULL_ON_SOME_PATH violation at Medium or High confidence
  And  build exits non-zero

Scenario QG-004 — Lombok-generated code does not cause false positives
Given  a @Builder record exists in tax-domain
When   mvn verify runs
Then   PMD, Checkstyle and SpotBugs all pass
  And  no suppression annotation is required on the production record class
```

---

## 7. Architecture constraints

| Item | Detail |
|---|---|
| Configuration location | `quality/` directory in repository root |
| Suppression policy | Every suppression must cite this spec section and the specific rule/bug code; undocumented suppressions are a G3 defect |
| Incrementalism | Violations introduced on a branch must be resolved before merge; the baseline on `main` is zero violations |
| Reporting | HTML reports published to `target/site/` via `mvn site`; CI must archive reports as build artifacts |

---

## 8. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-QG-001 | Confirm Checkstyle line-length limit: 120 (current) vs. 100 (stricter) vs. 150 (lenient for JPQL strings) | Architect |
| OQ-QG-002 | Confirm whether SpotBugs `PERFORMANCE` category should be P0 (build-breaking) or P1 (warning) for MVP — performance hot-spots may not be reachable in synthetic-only context | SRE |
| OQ-QG-003 | Confirm whether the Javadoc Checkstyle requirement applies to `tax-frameworks` package or stays exempt — argument for inclusion: `TaxRuleJpaGateway` and `TaxRuleEntity` are referenced in SPEC-TAX-002 and should carry self-documenting comments | Architect |
| OQ-QG-004 | Confirm whether PMD and SpotBugs should also cover test sources (`src/test/java/**`) with a reduced ruleset, or remain production-only | QA lead |

---

## 9. Traceability

| Requirement | Covered by |
|---|---|
| RNF-006 (modularity) | §4 SpotBugs `MT_CORRECTNESS` for concurrent paths; §3.2 Checkstyle ArchUnit alignment |
| RNF-007 (maintainability) | §2 PMD best practices; §3 Checkstyle formatting + Javadoc |
| `coding-conventions.md` Javadoc rule | §3.2 `JavadocMethod` / `JavadocType` Checkstyle checks |
| `coding-conventions.md` Streams rule | §2.2 PMD `ForLoopCanBeForeach` / `AvoidInstantiatingObjectsJustToCallGetClass` |
| ADR-008 (clean architecture) | §4.2 SpotBugs CORRECTNESS catches cross-layer null dereferences at gateway boundaries |
| TAX-012 | This document |
