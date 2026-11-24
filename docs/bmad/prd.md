# PRD — Tax Calculation Engine (MVP)

## Problem
ERP tax logic is fragmented and difficult to version, audit, replay and test. Provide a deterministic, explainable, multi-tenant **synthetic-first** calculation service.

## MVP scope
- Receive operation/date/classification/base and validated context via REST.
- Resolve one published versioned rule applicable at operation date.
- Calculate synthetic CBS/IBS components with BigDecimal and documented rounding.
- Persist canonical request, rule snapshot/hash, engine version, result and audit outbox atomically.
- Idempotent create; query and replay historical calculations.
- JWT scope checks, tenant isolation, logs/metrics, OpenAPI and automated tests.

## Out of scope
Real tax homologation, NF-e/NFS-e issuance, tax return filing, split payment, complex credits, full special regimes, external Receita/SEFAZ integration, UI, LLM interpretation of law.

## Personas
ERP developer, fiscal analyst, compliance officer, software architect, SRE.

## Functional requirements
RF-001 receive; RF-002 validate; RF-003 resolve rule; RF-004 calculate; RF-005 persist evidence; RF-006 replay; RF-007 idempotency; RF-008 query; RF-009 observability; RF-010 optional nonpersistent simulation (post-MVP).

## NFRs
RNF-001 determinism; RNF-002 decimal precision; RNF-003 P95<=150ms @100 RPS *target only*; RNF-004 OAuth2 tenant isolation; RNF-005 auditability; RNF-006 modularity; RNF-007 /v1 versioning; RNF-008 safe retries.

## Acceptance
All P0 specs reviewed, synthetic scenarios deterministic, documented rounding and versioning, architecture rules enforced, integration/security/load evidence attached. No production fiscal usage without legal validation.
