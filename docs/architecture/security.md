# Security Architecture

StockRush security expansion targets a production-style baseline for a portfolio project. The goal is not to build a custom identity platform, but to apply standard OIDC/OAuth2 patterns around the existing MSA and demo runtime.

## Standards Baseline

- OIDC/OAuth2 login and Authorization Code with PKCE for browser/mobile clients.
- JWT access tokens validated by Spring Security OAuth2 Resource Server at the Gateway.
- Role and scope based access control for customer/admin routes.
- Object-level authorization for customer-owned orders and order history.
- Authenticated principal based operator identity for admin audit logs.
- Explicit `401` unauthenticated and `403` forbidden tests for protected APIs.

Reference standards:

- NIST SP 800-63-4 Digital Identity Guidelines
- OWASP API Security Top 10 2023
- OAuth 2.0 Security Best Current Practice RFC 9700
- Spring Security OAuth2 Resource Server

## Target Architecture

```text
Customer Web / Mobile / Admin Web
  -> OIDC login with PKCE
  -> access token
  -> Gateway Resource Server
       - JWT issuer and signature validation
       - ROLE_CUSTOMER / ROLE_ADMIN mapping
       - path authorization
       - principal headers for internal services
  -> backend services
       - domain state and event processing
       - customer object ownership checks where needed
       - admin audit uses authenticated operator id
```

The demo IdP is Keycloak. It is an infrastructure dependency like PostgreSQL and Kafka, not a custom StockRush service. This keeps the project aligned with common production setups while avoiding a fragile custom password/login implementation.

## Route Policy

| Route | Access |
|---|---|
| `/actuator/health` | public |
| `/internal/ping` | public in local/demo only |
| `GET /api/products`, `GET /api/stocks`, `POST /api/coupons/quote` | public initially, may become customer optional-auth |
| `POST /api/orders` | `ROLE_CUSTOMER` |
| `GET /api/orders/{orderId}` | `ROLE_CUSTOMER` and order owner |
| `GET /api/read-model/orders` | `ROLE_CUSTOMER` and token subject as member id |
| `/api/admin/**` | `ROLE_ADMIN` |
| `/api/read-model/admin/**` | `ROLE_ADMIN` |

## Principal Propagation

Gateway owns external token validation. It forwards internal identity headers only after successful authentication:

- `X-StockRush-Subject`: JWT `sub`
- `X-StockRush-Email`: optional email claim
- `X-StockRush-Roles`: normalized application roles
- `X-Operator-Id`: derived from authenticated principal for admin operations

Clients must not be allowed to spoof these headers. Gateway removes incoming `X-StockRush-*` and `X-Operator-Id` before setting trusted values.

## Domain Authorization

Customer APIs must stop trusting caller supplied `memberId` as the authority source.

- Create order: Gateway or Order Service derives member id from `X-StockRush-Subject`.
- Customer order detail: a customer can read only their own order.
- Customer order history: `memberId` query is removed or ignored; subject drives the query.
- Admin search can still filter by member id, but requires `ROLE_ADMIN`.

## Demo Identity Setup

Keycloak demo realm:

- Realm: `stockrush`
- Client: `stockrush-customer-web`, public, Authorization Code + PKCE
- Client: `stockrush-admin-web`, public, Authorization Code + PKCE
- Client: `stockrush-mobile`, public, Authorization Code + PKCE
- Roles: `CUSTOMER`, `ADMIN`
- Users:
  - `customer.demo@stockrush.local` / customer role
  - `admin.demo@stockrush.local` / admin role

Demo-only smoke credentials are committed only for the disposable local realm and use `*.demo@stockrush.local` users. Production credentials must be supplied outside the repository.

## Implementation Phases

### P9-1. Planning and Guardrails

- Add this architecture document.
- Add Phase 9 TODO entries.
- Add security test strategy and portfolio wording.
- Add Architecture Guard checks that Gateway owns external auth enforcement.

### P9-2. Keycloak Demo Runtime

- Add Keycloak service to `infra/demo`.
- Add realm import JSON for demo clients, roles, and users.
- Split token issuer and Docker-internal JWKS lookup for Gateway validation.
- Add health/preflight checks and smoke token acquisition to demo scripts.

### P9-3. Gateway Resource Server

- Add Spring Security OAuth2 Resource Server to Gateway.
- Validate JWT issuer and JWKS.
- Enforce route policy.
- Map Keycloak realm/client roles to `ROLE_CUSTOMER` and `ROLE_ADMIN`.
- Add `401` and `403` gateway integration tests.

### P9-4. Customer Object Authorization

- Replace user-controlled `memberId` with authenticated subject in customer order routes.
- Add BOLA regression tests for another customer's order.
- Update customer web/mobile clients.

### P9-5. Admin Audit Principal

- Derive `X-Operator-Id` from authenticated principal.
- Remove client supplied operator id from Admin App API calls.
- Verify outbox retry/requeue and delayed payment cancel audit identity.

### P9-6. Client Login

- Add OIDC login/logout to Customer App and Admin App.
- Add Expo AuthSession PKCE login to Mobile App.
- Add unauthenticated, forbidden, token expiry, and logout UI tests.

### P9-7. CI Security Gates

- Add dependency and container vulnerability scan.
- Add secret scanning.
- Add Architecture Guard rules for protected admin/customer routes.

## Current Non-Goals

- Building a custom password database.
- Social login federation.
- Production mTLS between services.
- WAF/rate limiting beyond documented follow-up.
- Passkey-only authentication.
