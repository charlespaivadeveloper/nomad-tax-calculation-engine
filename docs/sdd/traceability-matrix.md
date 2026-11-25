# Traceability

| Story | Spec | Evidence expected |
|---|---|---|
| TAX-001 | SPEC-TAX-001 | Spec refined 2026-11-23 (S-001…S-010); test class `TaxDomainInvariantTest` pending G3 |
| TAX-002 | SPEC-TAX-004 | Spec refined 2026-11-23 (S-RC-001…S-RC-016, IT-RC-001…IT-RC-007); OpenAPI updated; test classes `CalculationControllerTest`, `CalculationApiIntegrationTest` pending G3 |
| TAX-003 | SPEC-TAX-006 | Spec refined 2026-11-23 (S-DB-001…S-DB-012, IT-DB-001…IT-DB-007); normative DDL for all 5 tables + 7 indexes + Flyway plan; test classes `SchemaConstraintTest`, `FlywayMigrationTest`, `DatabaseIntegrationTest` pending G3 |
| TAX-004 | SPEC-TAX-002 | Spec refined 2026-11-23 (S-RR-001…S-RR-014, IT-RR-001…IT-RR-007); §10 updated 2026-11-25 — gateway implementation changed from JDBC to Spring Data JPA (`TaxRuleJpaGateway`, `TaxRuleJpaRepository`, `TaxRuleEntity`); IT-RR-004 now uses Mockito mock; test classes `RuleResolutionServiceTest`, `TaxRuleGatewayIntegrationTest` pending G3 |
| TAX-005 | SPEC-TAX-003 | Spec refined 2026-11-23 (S-DE-001…S-DE-013, P-001…P-006); test class `TaxCalculationServiceTest` pending G3 |
| TAX-006 | SPEC-TAX-004 | Spec refined 2026-11-23 (shared with TAX-002); test classes `CalculationControllerTest`, `CalculationApiIntegrationTest` pending G3 |
| TAX-007 | SPEC-TAX-005 | Spec refined 2026-11-23 (S-IMP-001…S-IMP-012, IT-IMP-001…IT-IMP-007); test classes `IdempotencyIntegrationTest`, `IdempotencyDatabaseTest` pending G3 |
| TAX-008 | SPEC-TAX-007 | Spec refined 2026-11-23 (S-OUT-001…S-OUT-013, IT-OUT-001…IT-OUT-007); test classes `AuditOutboxIntegrationTest`, `CalculationPersistenceTest` pending G3 |
| TAX-009 | SPEC-TAX-008 | Spec refined 2026-11-24 (S-HR-001…S-HR-015, IT-HR-001…IT-HR-007); `ReplayInteractor`, `SnapshotDeserializerPort`, `EvidenceHashUtil`; test classes `ReplayInteractorTest`, `ReplayIntegrationTest` pending G3 |
| TAX-010 | SPEC-TAX-009 | Spec refined 2026-11-23 (S-SEC-001…S-SEC-016, IT-SEC-001…IT-SEC-007); JWT validation, scope-to-endpoint mapping, tenant extraction chain, threat model, secret-safe logging, DB least-privilege; test class `JwtSecurityIntegrationTest` pending G3 |
| TAX-011 | SPEC-TAX-012 | Test report after G3 |
| TAX-012 | SPEC-TAX-011, SPEC-TAX-013 | Test report after G3; quality-gate report (PMD/Checkstyle/SpotBugs) after G3 |
