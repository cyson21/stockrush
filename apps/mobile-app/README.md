# StockRush Mobile App

Expo React Native customer app scaffold for Android/iOS.

This app currently implements Gateway-based product browsing, stock lookup, coupon quote, order creation, order status tracking, and Read Model order history.

## Runtime

| Target | Default API base URL |
|---|---|
| iOS simulator | `http://localhost:18080` |
| Android emulator | `http://10.0.2.2:18080` |
| Physical device | set `EXPO_PUBLIC_API_BASE_URL=http://{host-lan-ip}:18080` |

When the portable Docker demo stack is running, use the demo Gateway port instead:

| Target | Demo stack API base URL |
|---|---|
| iOS simulator | `http://localhost:28080` |
| Android emulator | `http://10.0.2.2:28080` |
| Physical device | set `EXPO_PUBLIC_API_BASE_URL=http://{host-lan-ip}:28080` |

## Commands

```bash
cd apps/mobile-app
npm install
npm run start
```

Verification:

```bash
npm test
npm run typecheck
npm run test:scaffold
npm run smoke:preflight -- --api-base-url http://localhost:28080
npm run smoke:evidence -- --target all --api-base-url http://localhost:28080
```

Android:

```bash
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:28080 npm run android
```

iOS on macOS:

```bash
EXPO_PUBLIC_API_BASE_URL=http://localhost:28080 npm run ios
```

Scaffold validation without network install:

```bash
npm run test:scaffold
```

Smoke preflight without network install:

```bash
npm run smoke:preflight -- --target all --api-base-url http://localhost:28080
```

The preflight checks whether mobile dependencies are installed, whether the Gateway health endpoint is reachable, and whether iOS `simctl` or Android emulator tooling is available. It reports blockers without installing packages.

Smoke evidence:

```bash
```

The evidence command runs Jest, TypeScript typecheck, scaffold validation, and preflight in one pass. It exits non-zero only when the hard checks fail; simulator or emulator absence is recorded as a preflight blocker so the remaining environment gap is explicit.

For Android emulator runs, the app should use `10.0.2.2`, but the preflight health check usually runs from the host shell. In that case pass both URLs:

```bash
npm run smoke:preflight -- --target android --api-base-url http://10.0.2.2:28080 --host-api-base-url http://localhost:28080
```

## Manual Smoke Flow

1. Start the backend through either development mode or the portable demo stack.
2. Run `npm install` in `apps/mobile-app` if `node_modules` is absent.
3. Run the preflight command for the target environment.
4. Start Expo with the target API base URL.
5. In the app, select a product and SKU, enter a coupon, request quote, create a `CARD` order, wait for terminal status, then refresh order history.
6. Capture the product screen, quote/order screen, final status, and order history screen.

## Windows 11 Android

Use Android first on Windows 11. Run Docker Desktop with WSL2 integration for the backend and use Android Emulator or a physical Android device for the mobile client.

Recommended API base URLs:

| Target | API base URL |
|---|---|
| Android emulator with demo stack | `http://10.0.2.2:28080` |
| Android emulator with development backend | `http://10.0.2.2:18080` |
| Physical Android device | `http://{host-lan-ip}:28080` or `http://{host-lan-ip}:18080` |

## Environment Variables

| Variable | Default |
|---|---|
| `EXPO_PUBLIC_API_BASE_URL` | runtime-specific Gateway URL |
| `EXPO_PUBLIC_MEMBER_ID` | `member-mobile-demo` |

## Current Scope

- Expo SDK 54 blank app baseline
- Gateway-first API client structure
- Runtime API base URL strategy for iOS simulator, Android emulator, and physical devices
- Product list screen connected to `GET /api/products?status=ON_SALE`
- SKU stock lookup for the selected product through `GET /api/stocks?productCode={productCode}`
- Coupon quote through `POST /api/coupons/quote`
- Order creation through `POST /api/orders`
- Order status tracking through `GET /api/orders/{orderId}`
- Read Model order history through `GET /api/read-model/orders?memberId={memberId}`
- React Native Testing Library coverage for product loading, stock loading, product error retry, stock error retry, out-of-order stock responses, coupon quote, coupon block, order payload/header, order status polling, and order history refresh

## Next Implementation Slices

1. Android or iOS smoke evidence.
