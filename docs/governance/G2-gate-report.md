# G2 Gate Report

**User authorization:** YES (conversation, 2026-11-23)  
**Technical verification:** COMPLETE (2026-11-24)  
**Implementation unlock:** PENDING — requires gates.yaml update by user after review  
**Checklist items remaining:** 0

---

## Evidence summary

### Specifications (all P0 stories)

| Spec | Refined | BDD scenarios | Integration tests | Test class named |
|---|---|---|---|---|
| SPEC-TAX-001 | 2026-11-23 | S-001…S-010 (10) | — | `TaxDomainInvariantTest` |
| SPEC-TAX-002 | 2026-11-23 | S-RR-001…S-RR-014 (14) | IT-RR-001…IT-RR-007 | `RuleResolutionServiceTest`, `TaxRuleGatewayIntegrationTest` |
| SPEC-TAX-003 | 2026-11-23 | S-DE-001…S-DE-013 (13) + P-001…P-006 | — | `TaxCalculationServiceTest` |
| SPEC-TAX-004 | 2026-11-23 | S-RC-001…S-RC-016 (16) | IT-RC-001…IT-RC-007 | `CalculationControllerTest`, `CalculationApiIntegrationTest` |
| SPEC-TAX-005 | 2026-11-23 | S-IMP-001…S-IMP-012 (12) | IT-IMP-001…IT-IMP-007 | `IdempotencyIntegrationTest`, `IdempotencyDatabaseTest` |
| SPEC-TAX-006 | 2026-11-23 | S-DB-001…S-DB-012 (12) | IT-DB-001…IT-DB-007 | `SchemaConstraintTest`, `FlywayMigrationTest`, `DatabaseIntegrationTest` |
| SPEC-TAX-007 | 2026-11-23 | S-OUT-001…S-OUT-013 (13) | IT-OUT-001…IT-OUT-007 | `AuditOutboxIntegrationTest`, `CalculationPersistenceTest` |
| SPEC-TAX-008 | 2026-11-24 | S-HR-001…S-HR-015 (15) | IT-HR-001…IT-HR-007 | `ReplayInteractorTest`, `ReplayIntegrationTest` |
| SPEC-TAX-009 | 2026-11-23 | S-SEC-001…S-SEC-016 (16) | IT-SEC-001…IT-SEC-007 | `JwtSecurityIntegrationTest` |

Total BDD scenarios: 121. Total integration test requirements: 56.

### OpenAPI contract

- Artifact: `specs/openapi/tax-calculation-v1.yaml`
- Validated: 2026-11-24 via `swagger-cli validate` (Node 22 / @apidevtools/swagger-cli 4.0.4)
- Result: **"is valid"** — all `$ref` targets resolved, 3 endpoints, 0 errors
- Satisfies: IT-RC-002

### Maven module structure

- Command: `mvn validate --no-transfer-progress`
- Result: **BUILD SUCCESS** — 5/5 modules (0.690 s)
- Modules: `nomad-tax-calculation-engine` (root) → `tax-domain` ← `tax-usecases` ← `tax-interface-adapters` ← `tax-frameworks`
- Dependency direction: inward-only per ADR-008
- Java: 21 / Spring Boot: 3.3.6 / Maven: 3.6.3

### Decisions confirmed by domain owner (2026-11-24)

| Decision | ADR | Status |
|---|---|---|
| HALF_EVEN rounding for synthetic context | SPEC-TAX-001 §3, OQ-001 | Confirmed |
| Synthetic-only boundary in MVP | ADR-007 | Accepted — engine produces no legally certified calculations |

### Open questions carried to G3

The following OQs remain open but do not block implementation. Each is tracked in the relevant spec.

| OQ | Spec | Topic |
|---|---|---|
| OQ-002 | SPEC-TAX-001 | taxableBase max scale 4 — sufficiency |
| OQ-003 | SPEC-TAX-001 | CBS + IBS only in MVP |
| OQ-004 | SPEC-TAX-001 | Past-date operationDate for replay |
| OQ-RR-001…OQ-RR-005 | SPEC-TAX-002 | Rule resolution edge cases |
| OQ-RC-002…OQ-RC-005 | SPEC-TAX-004 | API contract edge cases |
| OQ-IMP-001…OQ-IMP-005 | SPEC-TAX-005 | Idempotency edge cases |
| OQ-DB-001…OQ-DB-005 | SPEC-TAX-006 | Schema edge cases |
| OQ-OUT-001…OQ-OUT-005 | SPEC-TAX-007 | Outbox edge cases |
| OQ-HR-001…OQ-HR-005 | SPEC-TAX-008 | Replay edge cases |
| OQ-SEC-001…OQ-SEC-007 | SPEC-TAX-009 | Security edge cases |

---

## Implementation status

No production code has been generated. All stories remain `not_started`.  
`implementation_unlocked` must be set to `true` in `gates.yaml` by the user after reviewing this report.
