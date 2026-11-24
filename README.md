# Nomad Compliance Platform — Tax Calculation Engine

**Handoff:** Claude Code | **Method:** BMAD + REA (Study/Specify/Build/Check/Ready) + SDD | **Architecture:** Robert C. Martin, [The Clean Architecture (2012)](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html).

This is a **specification-first starter**, not an implemented or certified tax engine. Tax rates in fixtures are **synthetic**. Do not use for real fiscal decisions. Official rules require legal-source mapping and fiscal specialist sign-off.

## Quick start

```bash
unzip nomad-tax-calculation-engine.zip
cd nomad-tax-calculation-engine
claude
```

Within Claude Code:

```text
/nomad:status
/nomad:study TAX-001
/nomad:spec TAX-001
/nomad:review TAX-001
/nomad:gate G2
/nomad:plan TAX-001
```

After the G2 evidence check is satisfied and the recorded implementation authorization is confirmed, run `/nomad:build TAX-001`, `/nomad:test TAX-001`, `/nomad:check TAX-001`, `/nomad:ready TAX-001`, `/nomad:next`.

**Gate semantics:** user approved progression and implementation in conversation; technical verification remains pending. Commands must not claim tests pass without executing them. `G2` evidence validation is required before G3 changes.

## Repository map
- `docs/bmad`: product scope, stories and backlog
- `docs/rea`: research and quality workflow
- `docs/sdd`: specifications, acceptance scenarios, traceability
- `docs/architecture`: C4, sequence diagrams, ADRs
- `specs/openapi`: draft API contract
- `specs/fixtures`: **synthetic** examples
- `.claude/commands/nomad`: Claude Code project slash commands
- `.claude/agents`: specialized reviewer prompts
- `scripts/check_gates.py`: executable gate checks
- `backend/*`: **empty placeholders** for Maven modules to be generated in G3

## Technical targets (not measured)
Java 21, Spring Boot 3, PostgreSQL, Flyway, OpenAPI 3, JUnit 5, Testcontainers, ArchUnit, Micrometer, OAuth2/OIDC. Target P95 <= 150ms at 100 RPS under a documented local test profile.
