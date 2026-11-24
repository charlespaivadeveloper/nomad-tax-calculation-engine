# SPEC-TAX-012 — Performance

Status: draft / requires G2 technical review.

## Purpose
k6 at 100 RPS, P95 target <=150ms, report environment and P99.

## Inputs and outputs
Document typed boundary DTOs, validation constraints and result/error representation before implementation. Domain interfaces must remain framework-agnostic.

## Invariants
- No silent fallback for missing or conflicting rules.
- Deterministic results for equal canonical inputs and equal versions.
- No unverified fiscal rule presented as legally compliant.
- Preserve tenant isolation and relevant evidence.

## Failure modes
Invalid input, missing/ambiguous rule, concurrency conflicts, unavailable dependency, historical version missing as applicable.

## Acceptance tests
- Positive synthetic fixture with exact expected output.
- Negative invalid input and missing/ambiguous rule cases.
- Repeatability and boundary tests.
- Integration/contract/security tests where applicable.

## Traceability
See `../traceability-matrix.md` and `../../bmad/backlog.md`.

## Open questions
Refine per-story exact input/output schema, policy details and approved fiscal source references during `/nomad:spec` and `/nomad:review`.
