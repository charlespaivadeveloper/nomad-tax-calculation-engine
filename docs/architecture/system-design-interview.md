# System Design interview questions

1. How do you reproduce a 2026 calculation in 2030? Discuss input/rule/engine/rounding version retention.
2. Why modular monolith rather than microservices? Discuss boundaries and operational overhead.
3. What if the HTTP response is lost after database commit? Idempotency and transaction atomicity.
4. How do you prevent overlapping rule validity? Conflict rejection and database constraints.
5. How would you scale from 100 to 10,000 RPS? Benchmark, cache, pools, write capacity, tenant hotspots.
6. How do you ensure decimal correctness? BigDecimal, canonical input, rounding and property tests.
7. How do you ensure audit delivery? Outbox, at-least-once semantics and idempotent consumers.
8. How do you migrate Java 8 ERP tax logic? Strangler pattern, shadow comparison, rollback.
9. How would you detect rules published with incorrect applicability? Validation workflow and review evidence.
10. How do you avoid domain coupling to Spring and persistence? Dependency Rule and ArchUnit.
