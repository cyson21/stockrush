# StockRush Mobile App

Expo React Native customer app scaffold for Android/iOS.

This app currently implements Gateway-based product browsing, stock lookup, coupon quote, and order creation. The same Gateway-first client layer is prepared for order status and Read Model order history slices.

## Runtime

| Target | Default API base URL |
|---|---|
| iOS simulator | `http://localhost:18080` |
| Android emulator | `http://10.0.2.2:18080` |
| Physical device | set `EXPO_PUBLIC_API_BASE_URL=http://{host-lan-ip}:18080` |

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
```

Android:

```bash
npm run android
```

iOS on macOS:

```bash
npm run ios
```

Scaffold validation without network install:

```bash
npm run test:scaffold
```

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
- React Native Testing Library coverage for product loading, stock loading, product error retry, stock error retry, out-of-order stock responses, coupon quote, coupon block, and order payload/header

## Next Implementation Slices

1. Order status polling screen.
2. Read Model order history screen.
3. Android or iOS smoke evidence.
