# REA Check

| Check | Tool | Evidence |
|---|---|---|
| Architecture | ArchUnit + Maven Enforcer | test report |
| Domain | JUnit 5 / property tests | deterministic fixtures |
| API | OpenAPI validation + contract tests | contract report |
| Database | Testcontainers + Flyway | migration test |
| Security | JWT/tenant tests + dependency scan | security report |
| Reliability | concurrency, idempotency, outbox | integration report |
| Performance | k6 | P95/P99/throughput/environment |

Nothing is marked passed before actual execution.
