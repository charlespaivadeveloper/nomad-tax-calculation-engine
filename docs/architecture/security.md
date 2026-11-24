# Security model

OIDC JWT issuer/JWKS validation, scoped roles tax:calculate / tax:read / tax:replay, tenant derived from authenticated identity, least-privilege DB, TLS, request validation, audit data minimization and retention review under LGPD. Threats: cross-tenant access, rule tampering, token leaks, idempotency abuse, replay mismatch, audit modification and malicious inputs. Test every authorization boundary and do not log tokens or raw secrets.
