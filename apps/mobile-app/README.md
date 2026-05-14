# StockRush Mobile App

Expo React Native customer app scaffold for Android/iOS.

This app uses Gateway as the single API entry point for product browsing, stock lookup, coupon quote, order creation, order status, and Read Model order history.

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
- Screen shell that shows the planned flow and Gateway routes

## Next Implementation Slices

1. Product list and stock selection screen.
2. Coupon quote and checkout screen.
3. Order status polling screen.
4. Read Model order history screen.
