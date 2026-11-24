# Test strategy (planned, not executed)

- Unit: BigDecimal arithmetic, money/period/rate invariants, rule resolution, canonicalization.
- Property: same input/rule/engine -> same output; valid rate and rounding bounds.
- Contract: OpenAPI schema, error formats, HTTP codes and compatibility.
- Integration: PostgreSQL + Flyway via Testcontainers, idempotent races, outbox atomicity.
- Security: JWT scopes, tenant isolation, no token/PII logging.
- Architecture: ArchUnit rules forbidding outward imports from domain/usecases; Maven dependency convergence.
- Performance: k6 at 100 RPS, P95 <=150ms target; report hardware, P99, errors, DB pool and CPU.
- Quality gate: no P0 failure; evidence links to each SPEC-TAX ID. Do not claim pass without executing.
