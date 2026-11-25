# ADR-007 — Synthetic Boundary

**Status:** accepted at G2, 2026-11-24  
**Domain owner sign-off:** confirmed verbally in G2 closure conversation, 2026-11-24

## Decision

The engine operates exclusively in a **synthetic simulation context**. Every `CalculationResult` and every persisted record carries `simulationContext = SYNTHETIC`. No output of this engine may ever be presented as a legally certified tax calculation.

## Rationale

The engine is a portfolio showcase and educational tool. No authoritative fiscal legislation has been reviewed, verified, or incorporated. All tax rates, classification codes, and rounding policies in specifications are illustrative fixtures. Using the engine's output as the basis for real tax compliance would be incorrect and potentially illegal.

## Consequences

- `SimulationContext.SYNTHETIC` is the only permitted enum value in MVP (SPEC-TAX-001 §2.1).
- Every API response includes `"simulationContext": "SYNTHETIC"` (INV-RC-002, SPEC-TAX-004 §9).
- No ADR, spec, or implementation may introduce a `REAL`, `CERTIFIED`, or equivalent context without a full fiscal specialist review and a new ADR superseding this one.
- All BDD fixtures and integration test data are explicitly labelled SYNTHETIC.
- The `EvidenceHashUtil` and audit outbox records carry the SYNTHETIC label as a tamper-evident control.

## Alternatives rejected

| Alternative | Reason rejected |
|---|---|
| Mixed synthetic/real context | Would require verified fiscal legislation and fiscal specialist sign-off — out of scope for MVP |
| No context label at all | Removes the tamper-evident safeguard; API consumers could mistake results for certified values |
| Context toggled via feature flag | Deferred — requires separate ADR, audit controls, and legal review before any real-data path is opened |
