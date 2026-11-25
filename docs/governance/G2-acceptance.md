# G2 evidence checklist — technical acceptance pending

- [x] Every P0 story has complete inputs/outputs and acceptance criteria.
  - [x] SPEC-TAX-001 refined 2026-11-23: typed inputs/outputs, BDD S-001…S-010, invariants INV-001…INV-010.
  - [x] SPEC-TAX-003 refined 2026-11-23: calculator interface, algorithm, BDD S-DE-001…S-DE-013, property tests P-001…P-006.
  - [x] SPEC-TAX-002 refined 2026-11-23: gateway interface, resolution algorithm, half-open validity semantics, snapshot hash, BDD S-RR-001…S-RR-014, integration tests IT-RR-001…IT-RR-007, 5 open questions.
  - [x] SPEC-TAX-004 refined 2026-11-23: full HTTP contract (3 endpoints), wire formats, status code matrix, header contracts, decimal serialisation policy, BDD S-RC-001…S-RC-016, IT-RC-001…IT-RC-007, 5 open questions. OpenAPI YAML updated.
  - [x] SPEC-TAX-006 refined 2026-11-23: normative DDL for all 5 tables, 7 indexes, FK/CHECK/UNIQUE constraints, Flyway migration plan (V1–V5), transaction isolation policy, NUMERIC precision reference, BDD S-DB-001…S-DB-012, IT-DB-001…IT-DB-007, 5 open questions.
  - [x] SPEC-TAX-009 refined 2026-11-23: JWT validation rules (RS256/ES256 only), scope-to-endpoint mapping (tax:calculate/read/replay), tenant extraction chain, 12-row threat model, secret-safe logging policy, DB least-privilege policy, BDD S-SEC-001…S-SEC-016, IT-SEC-001…IT-SEC-007, 7 open questions. Closes OQ-RC-001 from SPEC-TAX-004.
  - [x] SPEC-TAX-008 refined 2026-11-24: `ReplayRequest`/`ReplayResult` typed DTOs, `SnapshotDeserializerPort` interface, `ReplayVersionStatus` enum, interactor algorithm, evidence hash re-verification (§6), BDD S-HR-001…S-HR-015, integration tests IT-HR-001…IT-HR-007, 5 open questions.
- [x] Domain rounding and scale policies explicitly reviewed.
  - [x] SPEC-TAX-001 §3: HALF_EVEN, DECIMAL128, scale table defined.
  - [x] SPEC-TAX-003 §5 applies that policy; algorithm cites MathContext.DECIMAL128 and setScale(2, HALF_EVEN).
  - [x] OQ-001/OQ-DE-001: HALF_EVEN confirmed by domain owner 2026-11-24 for synthetic context.
- [x] Fiscal source matrix or synthetic-only boundary signed off.
  - [x] ADR-007 accepted 2026-11-24: synthetic-only boundary confirmed by domain owner; engine produces no legally certified calculations.
- [x] OpenAPI validated with a schema parser.
  - [x] `specs/openapi/tax-calculation-v1.yaml` validated 2026-11-24 via `swagger-cli validate` (Node 22 / @apidevtools/swagger-cli 4.0.4): "is valid". All $ref targets resolved. Satisfies IT-RC-002.
- [x] Architecture boundaries and Maven graph reviewed.
  - [x] 5 pom.xml files created 2026-11-24: root + tax-domain, tax-usecases, tax-interface-adapters, tax-frameworks. `mvn validate` returns BUILD SUCCESS (0.690 s, 5/5 modules). Dependency direction verified inward-only per ADR-008: tax-domain←usecases←adapters←frameworks.
- [x] Concurrency, idempotency and outbox semantics specified.
  - [x] SPEC-TAX-005 refined 2026-11-23: gateway interface, 5-state lifecycle, two-phase transaction protocol, 12 BDD scenarios (concurrent races, timeout-after-commit, tenant isolation), 7 integration test requirements. OQ-IMP-001…OQ-IMP-005 pending sign-off.
  - [x] SPEC-TAX-007 refined 2026-11-23: persistence gateway, outbox gateway, evidence hash algorithm (§5.3), canonical snapshot formats, background publisher protocol (at-least-once), 13 BDD scenarios, 7 integration test requirements. OQ-OUT-001…OQ-OUT-005 pending sign-off.
- [x] Threat model and tenant boundaries reviewed.
  - [x] SPEC-TAX-009 §8 maps all 12 threat categories from security.md to specific controls (cross-tenant access, rule tampering, token leaks, algorithm confusion, scope escalation, idempotency abuse, replay mismatch, audit modification, SQL injection, malformed input DoS, JWT replay, tenant enumeration). OQ-SEC-001…OQ-SEC-007 pending sign-off.
- [x] Test cases trace to requirements.
  - [x] SPEC-TAX-001 §10 maps S-001…S-010 to requirements, names `TaxDomainInvariantTest`.
  - [x] SPEC-TAX-003 §13 maps S-DE-001…S-DE-013 and P-001…P-006 to `TaxCalculationServiceTest`.
  - [x] SPEC-TAX-005 §14 maps S-IMP-001…S-IMP-012 and IT-IMP-001…IT-IMP-007 to `IdempotencyIntegrationTest` / `IdempotencyDatabaseTest`.
  - [x] SPEC-TAX-007 §13 maps S-OUT-001…S-OUT-013 and IT-OUT-001…IT-OUT-007 to `AuditOutboxIntegrationTest` / `CalculationPersistenceTest`.
  - [x] SPEC-TAX-002 §12 maps S-RR-001…S-RR-014 and IT-RR-001…IT-RR-007 to `RuleResolutionServiceTest` / `TaxRuleGatewayIntegrationTest`.
  - [x] SPEC-TAX-004 §15 maps S-RC-001…S-RC-016 and IT-RC-001…IT-RC-007 to `CalculationControllerTest` / `CalculationApiIntegrationTest`.
  - [x] SPEC-TAX-006 §14 maps S-DB-001…S-DB-012 and IT-DB-001…IT-DB-007 to `SchemaConstraintTest` / `FlywayMigrationTest` / `DatabaseIntegrationTest`.
  - [x] SPEC-TAX-009 §15 maps S-SEC-001…S-SEC-016 and IT-SEC-001…IT-SEC-007 to `JwtSecurityIntegrationTest`.
  - [x] SPEC-TAX-008 §13 maps S-HR-001…S-HR-015 and IT-HR-001…IT-HR-007 to `ReplayInteractorTest` / `ReplayIntegrationTest`.
- [x] G2 technical review evidence recorded.
  - [x] G2-gate-report.md updated 2026-11-24 with full evidence summary.

User authorized eventual implementation; this checklist is not automatically satisfied.
