# Test strategy (planned, not executed)

All test layers below are planned evidence requirements. No layer may be marked passing without actual execution and attached results. All fixtures must be labelled SYNTHETIC.

---

## 1. Unit tests (JUnit 5 + jqwik)

**Scope:** BigDecimal arithmetic, Money/Period/Rate invariants, rule resolution algorithm, canonical hash computation, serialisation/deserialisation utilities.

**Framework:** JUnit 5; jqwik for property-based tests (same input/rule/engine → same output; valid rate and rounding bounds).

**Location:** `src/test/java` in `tax-domain` and `tax-usecases`.

**Rules:**
- No Spring context. No database. No network.
- Gateway, persistence, and serialisation collaborators are mocked (Mockito).
- All test method names follow `snake_case_given_when_then` pattern.

---

## 2. Integration tests (Testcontainers)

**Scope:** PostgreSQL + Flyway migrations, gateway adapters, idempotency races, outbox atomicity, snapshot hash round-trips.

**Framework:** JUnit 5 + Testcontainers (`postgres:16-alpine`); Spring Boot test slices.

**Location:** `src/test/java` in `tax-frameworks`.

**Testcontainers patterns:**
- All tests that require a database use a `static PostgreSQLContainer<?>` annotated with `@Container` and `@Testcontainers`.
- DataSource is injected via `@DynamicPropertySource` before the Spring context initialises.
- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` for JPA gateway tests; prevents transactional rollback with `@Transactional(propagation = NOT_SUPPORTED)` so fixture data persists across `@Order`-sequenced test methods.
- Flyway runs manually in `@BeforeAll` via `Flyway.configure().dataSource(...).load().migrate()` when Spring Flyway autoconfiguration is disabled via `@DynamicPropertySource`.
- Each JPA integration test class gets its own container instance. Containers are never shared across test classes to avoid state leakage.
- Exception-wrapping tests (e.g. IT-RR-004) use a Mockito mock instead of a failing DataSource to validate `DataAccessException → RuleGatewayUnavailableException` without container teardown.

**Required test classes:**

| Class | Slice | Tests |
|---|---|---|
| `TaxRuleGatewayIntegrationTest` | `@DataJpaTest` | IT-RR-001..IT-RR-007 (SPEC-TAX-002 §9) |
| `FlywayMigrationTest` | plain JUnit 5 | S-DB-001, S-DB-011 (SPEC-TAX-006 §10) |
| `SchemaConstraintTest` | plain JUnit 5 | S-DB-002..S-DB-010 (SPEC-TAX-006 §10) |
| `DatabaseIntegrationTest` | plain JUnit 5 | IT-DB-001..IT-DB-007 (SPEC-TAX-006 §11) |
| `CalculationApiIntegrationTest` | `@SpringBootTest` | IT-RC-001..IT-RC-007 (SPEC-TAX-004) |
| `JwtSecurityIntegrationTest` | `@SpringBootTest` | IT-SEC-001..IT-SEC-007 (SPEC-TAX-009) |

---

## 3. BDD acceptance tests (Cucumber)

**Scope:** End-to-end acceptance scenarios as described in each `SPEC-TAX-*` §8/§9. Scenarios are the executable specification — every BDD scenario in a spec must have a corresponding step definition.

**Framework:** Cucumber 7 (JVM) + JUnit 5 Platform; feature files live in `src/test/resources/features/`.

**Location:** `src/test/java` in `tax-frameworks` for step definitions; feature files in `src/test/resources/features/`.

**Conventions:**
- Feature file names match the spec: `tax_rule_resolution.feature` ↔ SPEC-TAX-002, `tax_calculation.feature` ↔ SPEC-TAX-003, etc.
- Each `Scenario` title contains the scenario ID (e.g. `S-RR-001`, `IT-HR-003`).
- `Given` steps set up DB state via Flyway + raw JDBC fixture helpers (no Spring context leak into step definitions).
- `When` steps call the production HTTP endpoint (via `RestAssured` or `MockMvc`) or the use-case boundary directly.
- `Then` steps assert exact field values; monetary amounts use string comparison to avoid floating-point.
- Tags: `@unit` for no-infrastructure scenarios; `@integration` for Testcontainers scenarios; `@security` for JWT/scope scenarios.
- All fixtures are explicitly labelled `SYNTHETIC` in the scenario description.

**Existing feature file:** `docs/sdd/acceptance-criteria/calculation.feature` — this is the canonical source of BDD scenarios for SPEC-TAX-003 and SPEC-TAX-004; it must stay synchronised with spec changes.

**CI gate:** `mvn verify -P cucumber` runs `@integration` and `@unit` tagged scenarios; `@security` scenarios require a running Keycloak container and are gated separately.

---

## 4. Contract tests (OpenAPI)

**Scope:** OpenAPI schema validation, error response shapes, HTTP status codes, header presence.

**Framework:** Swagger Request Validator (`atlassian/swagger-request-validator-restassured`) against `specs/openapi/tax-calculation-v1.yaml`.

**Rule:** Every controller test validates both request and response against the YAML schema. The YAML is the single source of truth — no Spring `@ApiResponse` annotations diverging from the YAML.

---

## 5. Security tests (JWT/OAuth2)

**Scope:** JWT scope enforcement, tenant extraction chain, no PII in logs, DB least-privilege.

**Framework:** Spring Security test utilities + Testcontainers (Keycloak or mock JWT server).

**Reference:** SPEC-TAX-009 §10.

---

## 6. Architecture tests (ArchUnit)

**Scope:** Inward dependency rule (ADR-008), forbidden imports in `tax-domain` / `tax-usecases`, Maven module convergence.

**Framework:** ArchUnit 1.x.

**Key rules:**
- `noClasses().that().resideInAPackage("tax.usecases..").should().dependOnClassesThat().resideInAPackage("tax.frameworks..")`
- `noClasses().that().resideInAPackage("tax.domain..").should().dependOnClassesThat().resideInAPackage("org.springframework..")`
- `noClasses().that().resideInAPackage("tax.domain..").should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")`

---

## 7. Postman collection (API documentation + exploratory testing)

**Scope:** All three MVP endpoints from SPEC-TAX-004 §2, structured by user story.

**Tool:** Postman (exported as `postman/nomad-tax-engine.postman_collection.json` in the repository root).

**Collection structure:**

```
Nomad Tax Calculation Engine
├── TAX-002 / TAX-006 — Tax Calculation API
│   ├── [S-RC-001] POST Calculate — CBS + IBS (SYNTHETIC)
│   ├── [S-RC-002] POST Calculate — CBS only (SYNTHETIC)
│   ├── [S-RC-003] GET Retrieve stored calculation
│   ├── [S-RC-004] POST Calculate — idempotency: second call returns stored result
│   ├── [S-RC-005] POST Calculate — RULE_NOT_FOUND (no rule for date)
│   ├── [S-RC-006] POST Calculate — RULE_AMBIGUOUS (overlapping rules)
│   └── [S-RC-007] POST Replay — evidenceVerified=true
├── TAX-010 — Security
│   ├── [S-SEC-001] POST Calculate — missing JWT → 401
│   ├── [S-SEC-002] POST Calculate — invalid JWT → 401
│   └── [S-SEC-003] GET — wrong tenant → 404 (non-disclosing)
└── Environments
    ├── local.postman_environment.json  (localhost:8080, test JWT)
    └── staging.postman_environment.json
```

**Variables per environment:** `base_url`, `jwt_token`, `tenant_id`, `idempotency_key`.

**Conventions:**
- Each request has a `Tests` tab with JavaScript assertions on status code, `Content-Type`, presence of `X-Correlation-ID`, and key response fields.
- Monetary values in assertions use `===` on string representation (e.g. `"88.00"`), never `parseFloat`.
- Pre-request scripts generate a unique `Idempotency-Key` via `pm.variables.set("idempotency_key", pm.variables.replaceIn("{{$guid}}")`.
- Request names include the BDD scenario ID in brackets (e.g. `[S-RC-001]`) to maintain traceability to SPEC-TAX-004.
- All bodies use `SYNTHETIC` as `simulationContext`; comments in the description field note that rates are not legally certified.

**CI integration:** `newman run postman/nomad-tax-engine.postman_collection.json -e postman/local.postman_environment.json` runs as a post-integration-test step in the `verify` lifecycle phase.

---

## 8. Performance tests (k6)

**Scope:** 100 RPS sustained load on `POST /v1/tax-calculations`; P95 ≤ 150 ms target.

**Tool:** k6; script at `k6/tax-calculation-load.js`.

**Report:** Must include hardware spec, P50/P95/P99, error rate, DB pool saturation, CPU utilisation. Do not claim pass without executing.

**Reference:** SPEC-TAX-012.

---

## 9. Quality gate

No gate is passed without:
- All unit tests green.
- All `@integration` Cucumber scenarios green against a Testcontainers PostgreSQL.
- ArchUnit suite green.
- OpenAPI contract validation green.
- PMD, Checkstyle, SpotBugs — zero P0 violations (SPEC-TAX-013).
- Evidence links from each test method name to its `SPEC-TAX-*` scenario ID.
