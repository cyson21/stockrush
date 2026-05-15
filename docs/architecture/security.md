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

Implemented baseline:

- Gateway protects `POST /api/orders`, `GET /api/orders/{orderId}`, and `GET /api/read-model/orders` with `ROLE_CUSTOMER`.
- Gateway removes spoofable identity headers, derives `X-StockRush-Subject` from the JWT subject, and forwards only trusted customer identity headers.
- Order Service uses the trusted subject for order creation, blocks detail reads for another member, and rejects same `Idempotency-Key` replay when the authenticated subject differs from the existing order owner.
- Read Model customer history prefers the trusted subject header and Gateway strips caller supplied `memberId` from the customer query path. Admin read-model search still supports `memberId` filtering behind `ROLE_ADMIN`.
- Service-local direct calls still accept legacy `memberId` where needed for existing local compatibility; the external demo path is Gateway-first.

### P9-5. Admin Audit Principal

- Derive `X-Operator-Id` from authenticated principal.
- Remove client supplied operator id from Admin App API calls.
- Verify outbox retry/requeue and delayed payment cancel audit identity.

Current baseline:

- Gateway admin routes derive `X-Operator-Id` from the authenticated admin token and overwrite client supplied values.
- Admin App no longer sends `X-Operator-Id` for outbox retry/requeue; the Gateway owns operator propagation.
- Service-local admin APIs keep optional `X-Operator-Id` fallback for direct local tooling compatibility.
- Order Service records delayed payment cancel admin actions.
- Catalog Service records product create/update admin actions.
- Inventory Service records stock quantity set admin actions.

### P9-6. Client Login

- Customer App and Admin App have OIDC Authorization Code + PKCE login/logout.
- Mobile App has Expo Linking based OIDC PKCE login/logout without adding another runtime dependency.
- Customer protected order APIs and Admin protected APIs attach Bearer tokens.
- Customer App blocks order submit when unauthenticated. Admin App blocks protected screens when unauthenticated and surfaces forbidden API responses. Mobile App blocks order and history actions when unauthenticated.
- Gateway owns protected product/stock/order/admin routing so authenticated principal headers are generated at the boundary.

### P9-7. CI Security Gates

- CI tools job now includes `./scripts/check-no-committed-secrets.sh` and Trivy `fs` scans for `services` and `apps`.
- Release Images workflow now runs Trivy image scan for each published image tag before the release job completes.
- Architecture Guard now enforces `ARCH-011` (Gateway protected route authorization) and `ARCH-012` (trusted identity header rewriting) in CI.

Current baseline:

- Secret leakage and dependency/container risk checks run in `CI` so security gates fail on `HIGH`/`CRITICAL` findings.
- Release image pipeline scans pushed tags and fails the release job on actionable vulnerability findings.
- `ARCH-011` and `ARCH-012` are part of the Architecture Guard baseline and block changes that weaken route protection or header trust boundaries.

## Current Non-Goals

- Building a custom password database.
- Social login federation.
- Production mTLS between services.
- WAF/rate limiting beyond documented follow-up.
- Passkey-only authentication.

## Follow-Up Items

- Live Keycloak browser smoke evidence is recorded; mobile smoke evidence remains pending.
  - 모바일 실기동 로그인 기반 smoke evidence는 Android emulator 또는 iOS simulator 준비 후 진행한다.
- 공개 라우트와 레거시 직접 호출 정책 정리가 보류 중이다.
  - `GET /api/products`, `GET /api/stocks`, `POST /api/coupons/quote`의 공개 정책 범위를 문서에서 정리한다.
  - 로컬 호환을 위한 `memberId` 기반 경로는 단계적으로 폐기할지, direct call 허용 범위를 명확히 제한할지 다음 단계에서 정한다.
