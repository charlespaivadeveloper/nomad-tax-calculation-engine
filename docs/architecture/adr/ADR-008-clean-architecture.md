# ADR-008 — Uncle Bob Clean Architecture

Status: **Approved by user**, 2026-11-23.

Reference: https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html

Decision: four concentric responsibilities — Entities, Use Cases, Interface Adapters, Frameworks & Drivers — with static dependencies pointing inward. Enforce via Maven dependency graph and ArchUnit. Use cases own input/output boundary interfaces and gateway contracts. External adapters implement internal interfaces. Composition root and framework annotations stay outermost.

Proposed modules: `tax-domain` (pure), `tax-usecases` (domain only), `tax-interface-adapters` (usecases/domain), `tax-frameworks` (outer wiring/frameworks). Avoid cyclic dependencies. No Spring, JPA or HTTP types in domain/usecases.

Consequences: more explicit ports and mapping; stronger testability and framework independence.
