# SPEC-TAX-009 — OAuth2, JWT Scopes, and Tenant Isolation

**Status:** refined / awaiting G2 technical sign-off  
**Story:** TAX-010 (Implement OAuth2/tenant isolation)  
**Last updated:** 2026-11-23  
**Requirements:** RNF-004, RNF-005, RNF-006; closes OQ-RC-001 from SPEC-TAX-004  
**Related specs:** SPEC-TAX-001 §2.3 (TaxOperationInput.tenantId), SPEC-TAX-002 INV-RR-002 (gateway tenant filter), SPEC-TAX-004 §3 (auth/tenant propagation), SPEC-TAX-005 INV-IMP-005 (idempotency per-tenant), SPEC-TAX-006 §12 (tenant-scoped indexes), SPEC-TAX-007 INV-OUT-007 (payload exclusions)  
**All fixtures are SYNTHETIC. No fiscal compliance is implied.**

---

## 1. Purpose

Specify the complete OAuth2 / JWT security model for the tax calculation engine: token validation rules, scope-to-endpoint authorisation, tenant identity extraction and propagation, secret-safe logging constraints, database least-privilege policy, transport security requirements, and the mapped threat model. This spec resolves OQ-RC-001 from SPEC-TAX-004 and closes the security-review gap in the G2 checklist.

Security enforcement is layered:

| Layer | Mechanism |
|---|---|
| Transport | TLS 1.2+ for all non-local traffic |
| Authentication | OIDC JWT signature and claims validation |
| Authorisation | Scope-based; per endpoint |
| Tenant isolation | `tenant_id` from JWT only; predicate on every DB query |
| Input safety | Pattern constraints + domain validation; parameterised queries |
| Audit / logging | Secret-safe structured logs; append-only persistence |

---

## 2. OAuth2 token validation

### 2.1 Token format and grant type

The engine operates as an **OAuth2 Resource Server** only — it validates Bearer tokens issued by an external Identity Provider (IdP). It does not issue tokens, perform login flows, or manage client credentials.

Accepted token format: **JWT** (JSON Web Token, RFC 7519) transported as `Authorization: Bearer <token>`.

The grant type used by callers (client credentials, authorisation code, etc.) is an IdP and client concern; the engine validates the resulting access token regardless of how it was obtained.

### 2.2 JWT signature validation

| Property | Requirement |
|---|---|
| Signing algorithm | RS256 (RSA + SHA-256) or ES256 (ECDSA + P-256 + SHA-256) only |
| Rejected algorithms | `none`, `HS256`, `HS384`, `HS512` (symmetric — shared secret cannot be validated without possessing the secret) |
| Algorithm header `alg` | Must match the key type in the JWKS; algorithm confusion attacks must be rejected |
| Public keys | Fetched from the IdP's JWKS URI derived from `<issuer>/.well-known/openid-configuration` |
| Key rotation | JWKS cache refreshed when a JWT presents a `kid` not in the current cache; hard failure if key still not found after refresh |

### 2.3 Standard claim validation

| Claim | Requirement | Failure response |
|---|---|---|
| `iss` (Issuer) | Must exactly match the configured `issuer-uri` value | 401 `UNAUTHENTICATED` |
| `exp` (Expiration) | Must be in the future (UTC); no clock skew tolerance beyond 30 seconds (OQ-SEC-002) | 401 `UNAUTHENTICATED` |
| `iat` (Issued At) | Must be present; must not be in the future beyond 30 seconds | 401 `UNAUTHENTICATED` |
| `aud` (Audience) | If present in token, must include the configured resource server identifier (OQ-SEC-003) | 401 `UNAUTHENTICATED` |
| `sub` (Subject) | Must be present; not used directly by this engine beyond presence check | 401 `UNAUTHENTICATED` |

Any claim validation failure returns **401** with `code = UNAUTHENTICATED`. The error `detail` must not reveal which specific claim failed — only "token validation failed".

### 2.4 Custom claim requirements

| Claim | JSON type | Requirement | Failure response |
|---|---|---|---|
| `tenant_id` | string | Required; must be parseable as a UUID (RFC 4122, any variant) | 403 `TENANT_NOT_FOUND` |
| `scope` or `scp` | string (space-separated) | Required for authorised endpoints; see §3 | 403 `FORBIDDEN` |

If `tenant_id` is absent, the engine returns 403 `TENANT_NOT_FOUND`. If `tenant_id` is present but not a valid UUID, the engine returns 403 `TENANT_NOT_FOUND`. Neither case reveals why the JWT was accepted but the request was rejected — the error `detail` says only "tenant identity not resolvable from token".

The `tenant_id` claim name is configurable (OQ-SEC-004) — the IdP may use a different claim name in some deployments.

---

## 3. Scope-based authorisation

### 3.1 Scope definitions

| Scope | Permits |
|---|---|
| `tax:calculate` | POST `/v1/tax-calculations` (create a new calculation) |
| `tax:read` | GET `/v1/tax-calculations/{id}` (retrieve a stored calculation) |
| `tax:replay` | POST `/v1/tax-calculations/{id}/replays` (replay and compare) |

Scopes are independent: a token with `tax:calculate` cannot GET or replay. A token with all three scopes can perform all operations. No wildcard scope (`tax:*`, `*`) is recognised in MVP.

### 3.2 Endpoint-to-scope mapping

| Method | Path | Required scope | Insufficient scope response |
|---|---|---|---|
| POST | `/v1/tax-calculations` | `tax:calculate` | 403 `FORBIDDEN` |
| GET | `/v1/tax-calculations/{id}` | `tax:read` | 403 `FORBIDDEN` |
| POST | `/v1/tax-calculations/{id}/replays` | `tax:replay` | 403 `FORBIDDEN` |

Scope check is performed **after** JWT signature and standard claim validation. A token that is otherwise valid but lacks the required scope returns 403, not 401.

### 3.3 Scope claim format

The engine accepts the scope list from either:
- `scope` claim: `"tax:calculate tax:read"` (space-separated string, RFC 8693)
- `scp` claim: `["tax:calculate", "tax:read"]` (JSON array, common in some IdPs)

If both are present, `scope` takes precedence. If neither is present, the token is treated as having no scopes.

---

## 4. Tenant identity extraction and propagation

### 4.1 Extraction chain

```
HTTP Request
  └─► Spring Security JWT filter (tax-frameworks)
        └─► JwtAuthenticationConverter extracts:
              tenant_id  ← token attribute "tenant_id"
              scopes     ← token attribute "scope" / "scp"
        └─► Stores JwtAuthenticationToken in SecurityContext

Controller method (tax-interface-adapters)
  └─► Reads tenantId = UUID.fromString(authentication.getTokenAttributes().get("tenant_id"))
  └─► Builds TaxOperationInput(tenantId = tenantId, ...)
  └─► Passes to interactor (tax-usecases)
```

**Invariant**: the `tenantId` field in `TaxOperationInput` is always set from the validated JWT claim. It is never read from the HTTP request body, path parameters, or query parameters.

### 4.2 Application-layer isolation rules

The following rules apply to every component that handles data:

| Component | Rule |
|---|---|
| `TaxOperationInput` | `tenantId` field populated from JWT only (SPEC-TAX-001 §2.3 INV-008) |
| `RuleResolutionRequest` | `tenantId` field propagated from `TaxOperationInput` (SPEC-TAX-002 §2.2) |
| `CalculationPersistenceGateway.loadById()` | Takes explicit `tenantId` parameter; returns `Optional.empty()` for cross-tenant access (SPEC-TAX-007) |
| `IdempotencyGateway` methods | All take explicit `tenantId`; `reserve()` scopes uniqueness to `(tenantId, key)` (SPEC-TAX-005) |
| `CalculationResponse` | `tenantId` never serialised in response body (SPEC-TAX-004 INV-RC-003) |
| Error responses | Error `detail` must not contain the requesting tenant's UUID or any other tenant's UUID |

### 4.3 Database-layer isolation

Every SELECT against `tax_calculation`, `idempotency_record`, and `audit_outbox` **must** include a `WHERE tenant_id = ?` predicate bound to the authenticated tenant's UUID. The `tax_rule` table also includes `tenant_id` and must be queried with it.

There is no row-level security (RLS) in PostgreSQL in MVP — the application layer is solely responsible for the predicate. This creates a risk: a programming defect that omits the predicate could expose cross-tenant data. The mitigation is integration test IT-SEC-004 and code review (OQ-SEC-005 for future RLS consideration).

The DB application user must not have access to any other tenant's data at the DB permission level, but since all tenants share the same tables, this is not enforced at the DB permission layer in MVP.

### 4.4 Non-disclosing 404 for cross-tenant resources

A request for a `calculationId` that exists but belongs to a different tenant must return **exactly the same 404 response body** as a request for a genuinely absent `calculationId`. The response must not reveal:
- That the resource exists
- Which tenant owns it
- Any field values from the resource

This is enforced by `loadById(tenantId, calculationId)` returning `Optional.empty()` for both cases (SPEC-TAX-007) and the controller returning 404 `CALCULATION_NOT_FOUND` unconditionally on empty.

---

## 5. Secret-safe logging

### 5.1 Fields that must NEVER appear in logs

The following values must not appear in application log output at **any log level** (not just INFO):

| Value | Why |
|---|---|
| Raw JWT token (`Authorization: Bearer <token>` value) | Bearer token is equivalent to a credential; logging it enables token replay |
| `Idempotency-Key` header value | Logging enables idempotency abuse by observers (SPEC-TAX-005 INV-IMP-008) |
| `request_hash` hex values | Enables timing analysis of idempotency store |
| `snapshot_hash` hex values at INFO | Sensitive audit trail; DEBUG only |
| Any decoded JWT claim beyond UUID values | May contain PII from the IdP |

### 5.2 Fields that must appear in structured logs

Every inbound request must produce a structured log entry containing:

| Field | Log level | Notes |
|---|---|---|
| `correlationId` | INFO | From `X-Correlation-ID` header or server-generated |
| HTTP method | INFO | `POST`, `GET` |
| HTTP path | INFO | `/v1/tax-calculations` (no query string logged) |
| HTTP status | INFO | Integer (201, 400, 503, etc.) |
| Duration ms | INFO | Wall-clock time from request receipt to response send |
| `calculationId` | INFO | Only when the operation produces one (POST 201) |
| `tenantId` | DEBUG only | UUID string; never at INFO |

Log format must be structured JSON (not concatenated strings) so that log aggregators can filter without regex parsing.

### 5.3 Header scrubbing

The HTTP access log / request log must scrub the `Authorization` header value before writing. The `Idempotency-Key` header must similarly be scrubbed. The header names may appear in logs but their values must not.

A safe log entry for `Authorization` is: `"Authorization": "[REDACTED]"`. An unsafe entry is any entry that includes the token value.

---

## 6. Transport security

| Property | Requirement |
|---|---|
| Protocol | TLS 1.2 minimum; TLS 1.3 preferred |
| Cipher suites | Follow OWASP TLS Cheat Sheet; no RC4, 3DES, export ciphers |
| Local development | HTTP permitted on `localhost` only; never in staging or production |
| Certificate validation | Standard PKI; self-signed certificates in Testcontainers integration tests only |
| HSTS | `Strict-Transport-Security: max-age=31536000; includeSubDomains` on production responses |

TLS termination may happen at a load balancer / API gateway; the Spring Boot application may receive plain HTTP if behind such a proxy. The TLS configuration requirement applies to the edge, not necessarily to the application container itself. This must be confirmed per deployment topology (OQ-SEC-006).

---

## 7. Database least-privilege policy

The PostgreSQL role used by the application at runtime must have exactly the following privileges — no more:

| Table | Permitted operations |
|---|---|
| `tax_rule` | SELECT |
| `tax_calculation` | INSERT, SELECT |
| `tax_calculation_item` | INSERT, SELECT |
| `idempotency_record` | INSERT, SELECT, UPDATE |
| `audit_outbox` | INSERT, SELECT, UPDATE (`published_at` only) |
| All tables (DDL) | NONE — DDL is performed by a separate Flyway role |

The runtime role must not have DELETE, DROP, TRUNCATE, ALTER, or CREATE privileges on any table.

A separate **migration role** (used only by Flyway at startup) has CREATE TABLE, CREATE INDEX, ALTER TABLE, and DROP TABLE privileges. It must not be used for runtime DB connections.

This is verified by integration test IT-SEC-004 (attempting a DELETE with the runtime role must fail).

---

## 8. Threat model — threats mapped to controls

| Threat | Attack surface | Primary control | Secondary control |
|---|---|---|---|
| **Cross-tenant data access** | GET/replay with another tenant's `calculationId` | `loadById(tenantId, id)` returns empty; non-disclosing 404 | Tenant-scoped DB indexes; `WHERE tenant_id = ?` on every query |
| **Rule tampering** | Modifying `tax_rule` data after publication | `snapshot_hash` stored with calculation; evidence hash verified on replay (SPEC-TAX-007 §5.3) | Append-only `tax_calculation`; RESTRICT FK prevents deletion |
| **Token leaks via logs** | Log aggregation pipeline | JWT value never logged (§5.1) | Header scrubbing (§5.3) |
| **Algorithm confusion attack** | Providing HS256 or `alg:none` token | RS256/ES256 only; `none` rejected at JWT decoder | Algorithm list hardcoded in `JwtDecoder` bean |
| **Scope escalation** | Using a `tax:read` token to POST | Scope checked per endpoint (§3.2) | Separate scope required for each verb |
| **Idempotency abuse** | Exhausting key space with PROCESSING records | `markFailed()` after rollback prevents permanent PROCESSING (SPEC-TAX-005) | Key TTL cleanup (OQ-IMP-003 from SPEC-TAX-005) |
| **Replay mismatch acceptance** | Submitting a modified calculation as a replay | `evidenceHash` tied to `inputCanonicalHash` + `snapshotHash` (SPEC-TAX-007 §5.3); divergence reported, not silently accepted | `input_snapshot` and `rule_snapshot` stored verbatim; tamper-detectable |
| **Audit record modification** | UPDATE/DELETE of `tax_calculation` or `audit_outbox` | Runtime DB role has no DELETE/ALTER (§7) | Append-only invariants INV-OUT-006 (SPEC-TAX-007) |
| **SQL injection** | Malicious `classificationCode` or `operationId` | Pattern constraint `^[A-Z0-9_]+$` rejects metacharacters (SPEC-TAX-001) | Parameterised queries; JPA/JDBC bind variables |
| **Malformed input DoS** | Oversized fields, invalid Unicode, deeply nested JSON | `additionalProperties: false` in OpenAPI; `maxLength` constraints; domain validation fails fast | Servlet container request size limit (OQ-SEC-007) |
| **JWT replay after expiry** | Reusing an expired token from a compromised log | Short `exp` (OQ-SEC-002); `exp` validated on every request | No token caching beyond JWKS |
| **Tenant enumeration** | Probing GET by guessing UUIDs | 404 indistinguishable from non-existent UUID | UUID v4 randomness makes guessing infeasible |

---

## 9. Invariants

| ID | Invariant |
|---|---|
| INV-SEC-001 | `tenantId` in `TaxOperationInput` is always sourced from a validated JWT claim; it may never come from the request body or any untrusted source |
| INV-SEC-002 | A JWT with algorithm `none` or any symmetric algorithm (`HS*`) is always rejected with 401, regardless of other claim validity |
| INV-SEC-003 | A valid JWT with a `tenant_id` claim that is not a parseable UUID always returns 403, never 500 |
| INV-SEC-004 | A cross-tenant resource access always returns 404 `CALCULATION_NOT_FOUND` — never 403, never 200 |
| INV-SEC-005 | The raw JWT token value never appears in any application log line at any log level |
| INV-SEC-006 | The `Idempotency-Key` header value never appears in any application log line at INFO or above |
| INV-SEC-007 | Every SELECT on `tax_calculation`, `tax_rule`, `idempotency_record`, and `audit_outbox` includes a bound `tenant_id` parameter; unfiltered queries are a defect |
| INV-SEC-008 | The runtime DB role cannot execute DELETE, TRUNCATE, DROP, or ALTER on any table; a failed attempt is a configuration defect |
| INV-SEC-009 | Error response `detail` fields never contain JWT claim values, tenant UUIDs, or stack traces |
| INV-SEC-010 | Scope validation is performed after JWT signature and claim validation; a scope deficiency returns 403, not 401 |

---

## 10. Failure modes

| Trigger | Response | Code | HTTP |
|---|---|---|---|
| `Authorization` header absent | — | `UNAUTHENTICATED` | 401 |
| Token not parseable as JWT | — | `UNAUTHENTICATED` | 401 |
| JWT signature invalid | — | `UNAUTHENTICATED` | 401 |
| JWT `iss` mismatch | — | `UNAUTHENTICATED` | 401 |
| JWT `exp` in past | — | `UNAUTHENTICATED` | 401 |
| JWT algorithm `none` or `HS*` | — | `UNAUTHENTICATED` | 401 |
| JWT `kid` not found after JWKS refresh | — | `UNAUTHENTICATED` | 401 |
| JWT missing `tenant_id` claim | — | `TENANT_NOT_FOUND` | 403 |
| JWT `tenant_id` not a valid UUID | — | `TENANT_NOT_FOUND` | 403 |
| JWT missing required scope for endpoint | — | `FORBIDDEN` | 403 |
| Resource exists but belongs to different tenant | — | `CALCULATION_NOT_FOUND` | 404 |
| `tenantId` field present in POST request body | `DomainError(VALIDATION_ERROR)` | `VALIDATION_ERROR` | 400 |
| `classificationCode` with SQL metacharacters | `DomainError(VALIDATION_ERROR)` | `VALIDATION_ERROR` | 400 |
| JWKS endpoint unreachable at startup | Application fails to start | — | — |
| JWKS endpoint unreachable during request | 401 (key not found, cached state) | `UNAUTHENTICATED` | 401 |

For 401 and 403 responses, the `detail` field must be generic: "token validation failed" (401) or "insufficient permissions" (403). It must not state which claim failed or which scope is required.

---

## 11. BDD acceptance scenarios

### Scenario S-SEC-001 — Valid RS256 JWT with correct scope → endpoint succeeds

```
Given  a valid RS256 JWT:
         iss = configured issuer
         tenant_id = T1 (valid UUID)
         scope = "tax:calculate"
         exp = 1 hour from now
When   POST /v1/tax-calculations with Idempotency-Key and valid body
Then   HTTP 201 or 200 (not 401 or 403)
  And  calculation proceeds normally
```

### Scenario S-SEC-002 — Absent Authorization header → 401

```
When   POST /v1/tax-calculations with no Authorization header
Then   HTTP 401
  And  body.code = "UNAUTHENTICATED"
  And  body.detail = "token validation failed"  (generic; no claim details)
  And  no idempotency record created; no calculation attempted
```

### Scenario S-SEC-003 — Malformed token (not a JWT) → 401

```
When   Authorization: Bearer not-a-jwt-at-all
Then   HTTP 401
  And  body.code = "UNAUTHENTICATED"
```

### Scenario S-SEC-004 — Expired JWT → 401

```
Given  a valid RS256 JWT with exp = 1 second in the past
When   any endpoint is called with this token
Then   HTTP 401
  And  body.code = "UNAUTHENTICATED"
  And  body.detail does not contain the exp value or timestamp
```

### Scenario S-SEC-005 — Wrong issuer → 401

```
Given  a JWT signed by a different IdP (issuer = "https://evil.example.com")
When   any endpoint is called
Then   HTTP 401
  And  body.code = "UNAUTHENTICATED"
```

### Scenario S-SEC-006 — HS256 symmetric algorithm → 401

```
Given  a JWT with alg = "HS256" (symmetric HMAC)
         (even if the HMAC is valid for some known secret)
When   any endpoint is called
Then   HTTP 401
  And  body.code = "UNAUTHENTICATED"
  And  the token is never accepted regardless of signature validity
```

### Scenario S-SEC-007 — `alg: none` → 401

```
Given  a JWT with alg = "none" (unsigned)
When   any endpoint is called
Then   HTTP 401
  And  body.code = "UNAUTHENTICATED"
```

### Scenario S-SEC-008 — Valid JWT, missing `tenant_id` claim → 403

```
Given  a valid RS256 JWT from the correct issuer
         with scope = "tax:calculate"
         but no "tenant_id" claim
When   POST /v1/tax-calculations with this token
Then   HTTP 403
  And  body.code = "TENANT_NOT_FOUND"
  And  body.detail = "tenant identity not resolvable from token"
```

### Scenario S-SEC-009 — `tenant_id` present but not a UUID → 403

```
Given  a valid RS256 JWT with tenant_id = "not-a-uuid" (non-UUID string)
When   any endpoint is called
Then   HTTP 403
  And  body.code = "TENANT_NOT_FOUND"
```

### Scenario S-SEC-010 — POST without `tax:calculate` scope → 403

```
Given  a valid JWT with tenant_id = T1 and scope = "tax:read"
         (has read scope but NOT calculate scope)
When   POST /v1/tax-calculations
Then   HTTP 403
  And  body.code = "FORBIDDEN"
  And  body.detail = "insufficient permissions"
  And  body.detail does NOT reveal which scope is required
```

### Scenario S-SEC-011 — GET without `tax:read` scope → 403

```
Given  a valid JWT with tenant_id = T1 and scope = "tax:calculate"
When   GET /v1/tax-calculations/<any-id>
Then   HTTP 403
  And  body.code = "FORBIDDEN"
```

### Scenario S-SEC-012 — Replay without `tax:replay` scope → 403

```
Given  a valid JWT with tenant_id = T1 and scope = "tax:calculate tax:read"
         (has calculate and read but NOT replay)
When   POST /v1/tax-calculations/<id>/replays
Then   HTTP 403
  And  body.code = "FORBIDDEN"
```

### Scenario S-SEC-013 — Cross-tenant GET → non-disclosing 404

```
Given  calculation C1 belongs to tenant T1
  And  a valid JWT for tenant T2 with scope = "tax:read"
When   GET /v1/tax-calculations/C1 using T2's token
Then   HTTP 404
  And  body.code = "CALCULATION_NOT_FOUND"
  And  response body is byte-for-byte identical to a genuine not-found 404
  And  body contains no reference to T1 or any field from C1
```

### Scenario S-SEC-014 — `tenantId` in POST request body → 400

```
Given  a valid JWT for tenant T1 with scope = "tax:calculate"
When   POST /v1/tax-calculations with body containing "tenantId": "some-uuid"
Then   HTTP 400
  And  body.code = "VALIDATION_ERROR"
  And  body.detail references unexpected field "tenantId"
         (OpenAPI additionalProperties: false rejects the field)
```

### Scenario S-SEC-015 — JWT value does not appear in logs

```
Given  a valid request is processed successfully (HTTP 201)
  And  the JWT token value is "eyJhbGc..."
When   application logs are inspected (INFO and above)
Then   the string "eyJhbGc..." does not appear in any log line
  And  the string "Authorization" may appear as a header name but not followed by its value
  And  no JWT claim value (tenant UUID, scope string) appears at INFO level
```

### Scenario S-SEC-016 — SQL injection in classificationCode rejected at pattern constraint

```
When   POST /v1/tax-calculations with classificationCode = "'; DROP TABLE tax_rule; --"
Then   HTTP 400
  And  body.code = "VALIDATION_ERROR"
  And  body.detail references "classificationCode"
  And  no SQL statement is executed against the DB
  And  the injected payload does not appear in any log at INFO level
```

---

## 12. Integration and security test requirements

| ID | What to verify |
|---|---|
| IT-SEC-001 | All 3 endpoints return 401 when `Authorization` header is absent; 2xx is never returned without a token |
| IT-SEC-002 | A JWT signed by a different RS256 key (wrong JWKS) returns 401; a JWT signed by the correct key returns the expected 2xx — proving JWKS validation is live, not bypassed |
| IT-SEC-003 | Cross-tenant GET: response body and status code are indistinguishable from a genuine not-found 404 (compare byte-for-byte or field-for-field against a known-absent UUID) |
| IT-SEC-004 | The runtime DB connection role cannot execute `DELETE FROM tax_calculation`; a `DELETE` attempt throws `PSQLException` permission denied — proving least-privilege is configured |
| IT-SEC-005 | A full POST+GET cycle produces no log line containing the JWT value or the `Idempotency-Key` value at INFO level (verify log output captured by test appender) |
| IT-SEC-006 | A token with `alg: none` returns 401; a token with `alg: HS256` returns 401; a valid RS256 token from the same issuer returns 201 — all in the same test to confirm algorithm enforcement is not bypassed |
| IT-SEC-007 | `classificationCode = "'; DROP TABLE tax_rule; --"` returns 400 and `SELECT COUNT(*) FROM tax_rule` after the request returns the same count as before (no rows deleted by injection) |

---

## 13. Architecture constraints

| Constraint | Detail |
|---|---|
| `JwtDecoder` bean | `tax-frameworks` module; configured with `spring.security.oauth2.resourceserver.jwt.issuer-uri`; algorithm allow-list must be set explicitly (no default acceptance of `none` or `HS*`) |
| `JwtAuthenticationConverter` | `tax-frameworks`; extracts `tenant_id` and scopes; must validate UUID format before storing in `Authentication` object |
| `SecurityFilterChain` | `tax-frameworks`; `httpBasic` and `formLogin` disabled; CSRF disabled (stateless REST); session creation `STATELESS` |
| Scope annotations | `@PreAuthorize("hasAuthority('SCOPE_tax:calculate')")` on controller methods in `tax-interface-adapters`; scope name prefixed with `SCOPE_` per Spring Security convention |
| Tenant extraction | In controller method via `@AuthenticationPrincipal Jwt jwt` parameter; call `jwt.getClaimAsString("tenant_id")`; pass UUID to `TaxOperationInput`; never use `SecurityContextHolder` in `tax-usecases` or `tax-domain` |
| `tenantId` scope | `TaxOperationInput.tenantId` crosses the use-case boundary already populated; interactors must not read or write to `SecurityContextHolder` |
| Log scrubbing | Configure `Authorization` and `Idempotency-Key` as redacted headers in the HTTP access log configuration (`tax-frameworks`) |
| Flyway role separation | Two separate `DataSource` beans: one for Flyway (migration role), one for the application (runtime role); never swap them |
| ArchUnit rule | `noClasses().that().resideInAPackage("tax.domain..").or().resideInAPackage("tax.usecases..").should().dependOnClassesThat().resideInAPackage("org.springframework.security..")` |

Test class to be created in `tax-frameworks` test sources:
- `JwtSecurityIntegrationTest` — covers IT-SEC-001 … IT-SEC-007 (MockMvc + Spring Security test slice + Testcontainers PostgreSQL for IT-SEC-004 and IT-SEC-007)

---

## 14. Open questions requiring G2 sign-off

| OQ | Question | Owner |
|---|---|---|
| OQ-SEC-001 | Confirm whether integration tests use a local test IdP (e.g., Keycloak Testcontainers) or mocked `JwtDecoder` bean; mocked decoder is simpler but does not test JWKS fetch path; Keycloak tests JWKS but adds container startup cost | Architect |
| OQ-SEC-002 | Confirm acceptable JWT `exp` maximum (e.g., 1 hour); longer tokens increase the window for replay after compromise; 30-second clock skew tolerance confirmed? | Security reviewer |
| OQ-SEC-003 | Confirm whether `aud` (audience) claim validation is required; some IdPs omit `aud` from service-to-service tokens; if required, confirm the resource server identifier string | Security reviewer |
| OQ-SEC-004 | Confirm the custom claim name for tenant identity is `tenant_id`; some IdPs use `tid`, `x-tenant-id`, or a namespaced URI | Domain owner / IdP operator |
| OQ-SEC-005 | Confirm whether PostgreSQL row-level security (RLS) policies should be added as a defence-in-depth layer against missing `tenant_id` predicates; adds migration complexity | Architect |
| OQ-SEC-006 | Confirm TLS termination topology: TLS at load balancer (application receives plain HTTP) or end-to-end TLS to the container; affects whether HSTS must be set at application level | SRE |
| OQ-SEC-007 | Confirm maximum HTTP request body size (default Spring Boot = 2 MB); should a lower limit (e.g., 4 KB) be set given the small fixed request body structure | SRE / Security reviewer |

---

## 15. Traceability

| Requirement | Covered by |
|---|---|
| RNF-004 (OAuth2 tenant isolation) | §2 token validation, §3 scope authorisation, §4 tenant propagation, §8 threat model (cross-tenant row) |
| RNF-005 (auditability) | §5 secret-safe logging, §7 DB least privilege, §8 threat model (audit modification row) |
| RNF-006 (modularity) | §13 architecture constraints — Spring Security types confined to `tax-frameworks` and `tax-interface-adapters` |
| security.md threats | §8 — all 12 threat rows from security.md mapped to controls; closes the "mitigations not mapped" gap in G2-acceptance.md |
| OQ-RC-001 (SPEC-TAX-004) | §3.1 scope definitions, §3.2 endpoint-to-scope mapping — resolves open question from SPEC-TAX-004 |
| SPEC-TAX-001 §2.3 | INV-SEC-001 (tenantId source), §4.1 extraction chain |
| SPEC-TAX-002 INV-RR-002 | §4.3 DB-layer tenant isolation policy |
| SPEC-TAX-004 §3 | §4.1 extraction chain refines the propagation described there |
| SPEC-TAX-005 INV-IMP-005 | §4.2 application-layer isolation (idempotency per-tenant) |
| SPEC-TAX-006 §12 | §4.3 DB predicate requirement; §7 DB least-privilege policy |
| SPEC-TAX-007 INV-OUT-007 | §5.1 (idempotency_key excluded from logs and payload) |
| TAX-010 | This document |

Test evidence: to be attached after G3. BDD scenarios S-SEC-001 … S-SEC-016 and integration tests IT-SEC-001 … IT-SEC-007 map to `JwtSecurityIntegrationTest` in `tax-frameworks` test sources.
