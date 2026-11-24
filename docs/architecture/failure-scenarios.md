# Failure scenarios and expected behavior

1. No applicable rule -> 422 RULE_NOT_FOUND, never infer 0%.
2. Multiple eligible rules -> 409 RULE_AMBIGUOUS, never pick arbitrarily.
3. Same tenant/key + same request -> original logical result; different payload -> 409 IDEMPOTENCY_CONFLICT.
4. DB unavailable -> 503, no fabricated confirmation.
5. Network timeout after commit -> retry with same idempotency key returns committed result.
6. Audit broker unavailable -> committed outbox remains pending for retry; duplicate delivery safe.
7. Replay engine version unavailable -> explicit REPLAY_UNAVAILABLE, no false equivalence.
8. Cross-tenant lookup -> non-disclosing 404/403 policy, verified by tests.
9. Cache stale -> versioned keys and invalidation; historical replay reads immutable snapshot.
10. Monetary overflow/invalid scale -> reject with validation error.
