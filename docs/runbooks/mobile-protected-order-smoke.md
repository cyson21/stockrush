# Mobile Protected Order Smoke

This runbook verifies the Android/iOS customer app path after OIDC login:
login, product selection, SKU selection, coupon quote, protected order creation, and terminal order status.

## Preconditions

- Demo backend stack is running and Gateway is healthy.
- Keycloak demo realm is available at the browser-facing issuer.
- Mobile dependencies are installed in `apps/mobile-app`.
- Android emulator or iOS simulator is available.

For Android Expo Go against the demo stack:

```bash
adb reverse tcp:28088 tcp:28088
cd apps/mobile-app
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:28080 \
EXPO_PUBLIC_AUTH_ISSUER=http://localhost:28088/realms/stockrush \
EXPO_PUBLIC_AUTH_REDIRECT_MODE=expo-go \
EXPO_PUBLIC_EXPO_GO_HOST=10.0.2.2:8081 \
npm run android
```

For local Expo Go order completion smoke, add `EXPO_PUBLIC_MOBILE_SMOKE_AUTORUN=true`. After manual Keycloak login, the app selects the first SKU with enough available quantity and creates a `CARD` order through the normal authenticated API flow.

## Stable Selectors

Use these `testID` values for automation. Avoid coordinate-only taps.

| Step | Selector |
|---|---|
| App screen loaded | `mobile-product-list-screen` |
| Auth status text | `mobile-auth-status-label` |
| Login/logout button | `mobile-auth-action-button` |
| Product button | `mobile-product-card-{productCode}` |
| SKU button | `mobile-stock-row-{skuId}` |
| Quantity input | `mobile-checkout-quantity-input` |
| Coupon input | `mobile-checkout-coupon-input` |
| Coupon apply button | `mobile-checkout-apply-coupon-button` |
| CARD payment button | `mobile-payment-method-CARD` |
| FAIL_CARD payment button | `mobile-payment-method-FAIL_CARD` |
| DELAY_CARD payment button | `mobile-payment-method-DELAY_CARD` |
| Submit order button | `mobile-checkout-submit-order-button` |
| Created order summary | `mobile-created-order-summary` |
| Created order id | `mobile-created-order-id` |
| Created order status | `mobile-created-order-status` |
| Created order saga status | `mobile-created-order-saga-status` |

## Scenario

1. Wait for `mobile-product-list-screen`.
2. Tap `mobile-auth-action-button`.
3. Complete Keycloak login with the demo customer account.
4. Wait until `mobile-auth-status-label` is `로그인됨`.
5. Tap a product through `mobile-product-card-{productCode}`.
6. Tap a SKU through `mobile-stock-row-{skuId}`.
7. Set `mobile-checkout-quantity-input` to `1`.
8. Optionally set `mobile-checkout-coupon-input` and tap `mobile-checkout-apply-coupon-button`.
9. Tap `mobile-payment-method-CARD`.
10. Tap `mobile-checkout-submit-order-button`.
11. Wait for `mobile-created-order-summary`.
12. Wait until `mobile-created-order-saga-status` becomes `COMPLETED`.

## Android UIAutomator Runner

Use the repo runner when an Android emulator is already open and the app is on the product list after Keycloak login. The runner uses Android's built-in UIAutomator XML dump, resolves `testID` values as `resource-id`, taps the center of each resolved node, and records XML/screenshots for every step.

```bash
cd apps/mobile-app
adb reverse tcp:28080 tcp:28080
adb reverse tcp:28088 tcp:28088
npm run smoke:android:e2e -- \
  --quantity 1 \
  --payment-method CARD \
  --expected-saga-status COMPLETED \
```

Optional filters:

```bash
npm run smoke:android:e2e -- \
  --product-code DEMO-E2E-20260516000356-900ada45 \
  --sku-id DEMO-E2E-20260516000356-900ada45-S \
  --coupon-code WELCOME10 \
  --expected-saga-status ANY_TERMINAL
```

Expected evidence files:

| File | Purpose |
|---|---|
| `report.md` | Human-readable step summary |
| `report.json` | Structured step summary |
| `{step}-{attempt}.xml` | UIAutomator tree before each action |
| `{step}-hit.png` | Screenshot when a selector is found |
| `{step}-after.png` | Screenshot after tap/input |
| `failure.txt` | Failure reason when a step times out |

## Evidence To Capture

- Authenticated app screen after returning from Keycloak.
- Selected product and SKU.
- Quote result, when a coupon is used.
- Created order id.
- Final status and saga status.
- Order history refresh after the order reaches terminal status.

## Latest Evidence


| Field | Value |
|---|---|
| Order id | `ord_20260515233439_6a5f6b71` |
| Order status | `CONFIRMED` |
| Saga status | `COMPLETED` |
