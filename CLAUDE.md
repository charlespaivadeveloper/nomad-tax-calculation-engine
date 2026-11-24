# Claude Code — binding project instructions

Read `AGENTS.md`, `docs/governance/gates.yaml`, `docs/bmad/prd.md`, `docs/architecture/adr/ADR-008-clean-architecture.md`, and the relevant SDD spec **before** changing anything.

1. Follow **Uncle Bob Clean Architecture (2012)**: Entities <- Use Cases <- Interface Adapters <- Frameworks & Drivers. Static source dependencies point inward only.
2. Maven modules planned: `tax-domain`, `tax-usecases`, `tax-interface-adapters`, `tax-frameworks`. Domain/use cases have no Spring, JPA, HTTP, JSON or persistence framework types. Interactors use input/output boundaries and gateway interfaces; concrete adapters stay outside.
3. SDD: story -> approved spec -> failing test -> minimal implementation -> refactor -> evidence. Link every change to story/spec/test IDs.
4. Rates and calculations are synthetic until verified against authoritative legislation and approved by a fiscal specialist. Never fabricate tax law, rates, classification, exemptions or compliance certification.
5. Monetary calculations use BigDecimal from strings, explicit rounding and immutable versioned rule snapshots. No float/double in domain calculations.
6. Multi-tenant isolation, OAuth2 scopes, idempotency, audit outbox and deterministic replay are mandatory acceptance areas.
7. No automatic gate approvals or commits. The user approved moving forward, but G2 technical evidence check must pass before implementation. Use `python3 scripts/check_gates.py --require-build` before any production code change; stop if it fails. Do not edit gate state just to bypass a failure.
8. Keep docs and code consistent; report unexecuted checks explicitly.
