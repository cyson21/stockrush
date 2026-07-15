import test from 'node:test';
import assert from 'node:assert/strict';

// 데모 샘플 데이터 스키마와 상태 분포가 기대 범위를 벗어나지 않는지 확인합니다.

import { buildPortfolioSeedSql, portfolioFixtures } from './seed-portfolio-data.mjs';

test('portfolio fixtures cover visual admin states', () => {
  assert.equal(portfolioFixtures.orders.length, 10);
  assert.equal(portfolioFixtures.couponUsages.length, 6);
  assert.equal(portfolioFixtures.fulfillmentRequests.length, 5);
  assert.equal(portfolioFixtures.outboxEvents.length, 4);

  assert.deepEqual(
    new Set(portfolioFixtures.orders.map((order) => order.status)),
    new Set(['CREATED', 'CONFIRMED', 'CANCELLED']),
  );
  assert.deepEqual(
    new Set(portfolioFixtures.orders.map((order) => order.sagaStatus)),
    new Set(['STARTED', 'COMPLETED', 'FAILED', 'PAYMENT_DELAYED']),
  );
  assert.deepEqual(
    new Set(portfolioFixtures.couponUsages.map((usage) => usage.status)),
    new Set(['RESERVED', 'CONSUMED', 'RELEASED']),
  );
});

test('seed SQL is idempotent across portfolio tables', () => {
  const sql = buildPortfolioSeedSql();

  assert.match(sql, /insert into promotion\.coupons/);
  assert.match(sql, /on conflict \(coupon_code\) do update/);
  assert.match(sql, /insert into read_model\.order_summaries/);
  assert.match(sql, /on conflict \(order_id\) do update/);
  assert.match(sql, /insert into promotion\.coupon_usages/);
  assert.match(sql, /insert into fulfillment\.fulfillment_requests/);
  assert.match(sql, /insert into orders\.outbox_events/);
  assert.match(sql, /insert into inventory\.outbox_events/);
  assert.match(sql, /insert into payment\.outbox_events/);
  assert.match(sql, /where event_id::text like '00000000-0000-4000-8000-00000000%'/);
  assert.doesNotMatch(sql, new RegExp('\\uacc4\\uc57d'));
});
