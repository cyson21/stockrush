# Apps

Frontend apps connect the commerce services to portfolio demo flows.

## Current Apps

- [customer-app](customer-app/README.md): product list, stock selection, order creation, Saga status tracking
- [admin-app](admin-app/README.md): product and stock operations, order operations, Saga detail, service-local outbox monitoring and retry
- [mobile-app](mobile-app/README.md): Expo React Native customer app with product/stock lookup, coupon quote, checkout, order status, and order history

The portable demo runtime serves `customer-app` and `admin-app` from Docker containers. The mobile app remains host-run through Expo because simulator/device networking depends on the target machine.
