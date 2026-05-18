#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const baseTime = "date_trunc('minute', now())";

const coupons = [
  {
    couponCode: 'PF10',
    name: 'PF 10% 할인',
    discountType: 'RATE',
    discountValue: '10.00',
    minOrderAmount: '20000.00',
    maxDiscountAmount: '12000.00',
    status: 'ACTIVE',
  },
  {
    couponCode: 'RUSH5K',
    name: 'StockRush 5K 할인',
    discountType: 'AMOUNT',
    discountValue: '5000.00',
    minOrderAmount: '30000.00',
    maxDiscountAmount: null,
    status: 'ACTIVE',
  },
  {
    couponCode: 'DL10',
    name: '지연 복구 쿠폰',
    discountType: 'RATE',
    discountValue: '10.00',
    minOrderAmount: '10000.00',
    maxDiscountAmount: '8000.00',
    status: 'ACTIVE',
  },
];

const orders = [
  {
    orderId: 'pf-ord-1001',
    memberId: 'pf-member-a',
    status: 'CONFIRMED',
    sagaStatus: 'COMPLETED',
    couponCode: 'PF10',
    totalAmount: '129000.00',
    discountAmount: '12000.00',
    payableAmount: '117000.00',
    itemCount: 3,
    cancellationReason: null,
    confirmedOffset: '8 minutes',
    cancelledOffset: null,
    createdOffset: '18 minutes',
  },
  {
    orderId: 'pf-ord-1002',
    memberId: 'pf-member-b',
    status: 'CONFIRMED',
    sagaStatus: 'COMPLETED',
    couponCode: 'RUSH5K',
    totalAmount: '64500.00',
    discountAmount: '5000.00',
    payableAmount: '59500.00',
    itemCount: 2,
    cancellationReason: null,
    confirmedOffset: '11 minutes',
    cancelledOffset: null,
    createdOffset: '24 minutes',
  },
  {
    orderId: 'pf-ord-1003',
    memberId: 'pf-member-c',
    status: 'CANCELLED',
    sagaStatus: 'FAILED',
    couponCode: 'DL10',
    totalAmount: '88000.00',
    discountAmount: '8000.00',
    payableAmount: '80000.00',
    itemCount: 1,
    cancellationReason: 'payment timeout after inventory reservation',
    confirmedOffset: null,
    cancelledOffset: '16 minutes',
    createdOffset: '33 minutes',
  },
  {
    orderId: 'pf-ord-1004',
    memberId: 'pf-member-d',
    status: 'CREATED',
    sagaStatus: 'PAYMENT_DELAYED',
    couponCode: 'PF10',
    totalAmount: '45200.00',
    discountAmount: '4520.00',
    payableAmount: '40680.00',
    itemCount: 4,
    cancellationReason: null,
    confirmedOffset: null,
    cancelledOffset: null,
    createdOffset: '41 minutes',
  },
  {
    orderId: 'pf-ord-1005',
    memberId: 'pf-member-a',
    status: 'CONFIRMED',
    sagaStatus: 'COMPLETED',
    couponCode: null,
    totalAmount: '21900.00',
    discountAmount: '0.00',
    payableAmount: '21900.00',
    itemCount: 1,
    cancellationReason: null,
    confirmedOffset: '43 minutes',
    cancelledOffset: null,
    createdOffset: '58 minutes',
  },
  {
    orderId: 'pf-ord-1006',
    memberId: 'pf-member-e',
    status: 'CANCELLED',
    sagaStatus: 'FAILED',
    couponCode: 'RUSH5K',
    totalAmount: '73000.00',
    discountAmount: '5000.00',
    payableAmount: '68000.00',
    itemCount: 2,
    cancellationReason: 'inventory reservation expired during payment retry',
    confirmedOffset: null,
    cancelledOffset: '51 minutes',
    createdOffset: '72 minutes',
  },
  {
    orderId: 'pf-ord-1007',
    memberId: 'pf-member-f',
    status: 'CREATED',
    sagaStatus: 'STARTED',
    couponCode: null,
    totalAmount: '156000.00',
    discountAmount: '0.00',
    payableAmount: '156000.00',
    itemCount: 5,
    cancellationReason: null,
    confirmedOffset: null,
    cancelledOffset: null,
    createdOffset: '83 minutes',
  },
  {
    orderId: 'pf-ord-1008',
    memberId: 'pf-member-g',
    status: 'CONFIRMED',
    sagaStatus: 'COMPLETED',
    couponCode: 'DL10',
    totalAmount: '54000.00',
    discountAmount: '5400.00',
    payableAmount: '48600.00',
    itemCount: 2,
    cancellationReason: null,
    confirmedOffset: '85 minutes',
    cancelledOffset: null,
    createdOffset: '96 minutes',
  },
  {
    orderId: 'pf-ord-1009',
    memberId: 'pf-member-h',
    status: 'CANCELLED',
    sagaStatus: 'FAILED',
    couponCode: null,
    totalAmount: '39000.00',
    discountAmount: '0.00',
    payableAmount: '39000.00',
    itemCount: 1,
    cancellationReason: 'payment provider returned insufficient funds',
    confirmedOffset: null,
    cancelledOffset: '97 minutes',
    createdOffset: '118 minutes',
  },
  {
    orderId: 'pf-ord-1010',
    memberId: 'pf-member-i',
    status: 'CONFIRMED',
    sagaStatus: 'COMPLETED',
    couponCode: 'PF10',
    totalAmount: '231000.00',
    discountAmount: '12000.00',
    payableAmount: '219000.00',
    itemCount: 6,
    cancellationReason: null,
    confirmedOffset: '121 minutes',
    cancelledOffset: null,
    createdOffset: '138 minutes',
  },
];

const couponUsages = [
  {
    orderId: 'pf-ord-1001',
    memberId: 'pf-member-a',
    couponCode: 'PF10',
    status: 'CONSUMED',
    orderAmount: '129000.00',
    discountAmount: '12000.00',
    payableAmount: '117000.00',
    reservedOffset: '18 minutes',
    consumedOffset: '8 minutes',
    releasedOffset: null,
    releaseReason: null,
  },
  {
    orderId: 'pf-ord-1002',
    memberId: 'pf-member-b',
    couponCode: 'RUSH5K',
    status: 'CONSUMED',
    orderAmount: '64500.00',
    discountAmount: '5000.00',
    payableAmount: '59500.00',
    reservedOffset: '24 minutes',
    consumedOffset: '11 minutes',
    releasedOffset: null,
    releaseReason: null,
  },
  {
    orderId: 'pf-ord-1003',
    memberId: 'pf-member-c',
    couponCode: 'DL10',
    status: 'RELEASED',
    orderAmount: '88000.00',
    discountAmount: '8000.00',
    payableAmount: '80000.00',
    reservedOffset: '33 minutes',
    consumedOffset: null,
    releasedOffset: '16 minutes',
    releaseReason: 'payment timeout rollback',
  },
  {
    orderId: 'pf-ord-1004',
    memberId: 'pf-member-d',
    couponCode: 'PF10',
    status: 'RESERVED',
    orderAmount: '45200.00',
    discountAmount: '4520.00',
    payableAmount: '40680.00',
    reservedOffset: '41 minutes',
    consumedOffset: null,
    releasedOffset: null,
    releaseReason: null,
  },
  {
    orderId: 'pf-ord-1006',
    memberId: 'pf-member-e',
    couponCode: 'RUSH5K',
    status: 'RELEASED',
    orderAmount: '73000.00',
    discountAmount: '5000.00',
    payableAmount: '68000.00',
    reservedOffset: '72 minutes',
    consumedOffset: null,
    releasedOffset: '51 minutes',
    releaseReason: 'reservation expired',
  },
  {
    orderId: 'pf-ord-1008',
    memberId: 'pf-member-g',
    couponCode: 'DL10',
    status: 'CONSUMED',
    orderAmount: '54000.00',
    discountAmount: '5400.00',
    payableAmount: '48600.00',
    reservedOffset: '96 minutes',
    consumedOffset: '85 minutes',
    releasedOffset: null,
    releaseReason: null,
  },
];

const fulfillmentRequests = [
  {
    requestId: '00000000-0000-4000-8000-000000000101',
    orderId: 'pf-ord-1001',
    requestedOffset: '7 minutes',
    sourceEventId: '00000000-0000-4000-8000-000000000201',
    correlationId: 'pf-corr-1001',
    idempotencyKey: 'pf-fulfillment-1001',
  },
  {
    requestId: '00000000-0000-4000-8000-000000000102',
    orderId: 'pf-ord-1002',
    requestedOffset: '10 minutes',
    sourceEventId: '00000000-0000-4000-8000-000000000202',
    correlationId: 'pf-corr-1002',
    idempotencyKey: 'pf-fulfillment-1002',
  },
  {
    requestId: '00000000-0000-4000-8000-000000000103',
    orderId: 'pf-ord-1005',
    requestedOffset: '42 minutes',
    sourceEventId: '00000000-0000-4000-8000-000000000203',
    correlationId: 'pf-corr-1005',
    idempotencyKey: 'pf-fulfillment-1005',
  },
  {
    requestId: '00000000-0000-4000-8000-000000000104',
    orderId: 'pf-ord-1008',
    requestedOffset: '84 minutes',
    sourceEventId: '00000000-0000-4000-8000-000000000204',
    correlationId: 'pf-corr-1008',
    idempotencyKey: 'pf-fulfillment-1008',
  },
  {
    requestId: '00000000-0000-4000-8000-000000000105',
    orderId: 'pf-ord-1010',
    requestedOffset: '120 minutes',
    sourceEventId: '00000000-0000-4000-8000-000000000205',
    correlationId: 'pf-corr-1010',
    idempotencyKey: 'pf-fulfillment-1010',
  },
];

const outboxEvents = [
  {
    schema: 'orders',
    eventId: '00000000-0000-4000-8000-000000001001',
    aggregateType: 'Order',
    aggregateId: 'pf-ord-1003',
    eventType: 'OrderPublishFailed',
    topic: 'stockrush.order.events',
    partitionKey: 'pf-ord-1003',
    correlationId: 'pf-corr-1003',
    idempotencyKey: 'pf-outbox-order-1003',
    status: 'FAILED',
    retryCount: 5,
    maxRetryCount: 5,
    nextRetryAt: null,
    errorMessage: 'max retries reached',
    createdOffset: '14 minutes',
    publishedAt: null,
  },
  {
    schema: 'orders',
    eventId: '00000000-0000-4000-8000-000000001002',
    aggregateType: 'Order',
    aggregateId: 'pf-ord-1004',
    eventType: 'OrderBacklogFailed',
    topic: 'stockrush.order.events',
    partitionKey: 'pf-ord-1004',
    correlationId: 'pf-corr-1004',
    idempotencyKey: 'pf-outbox-order-1004',
    status: 'FAILED',
    retryCount: 5,
    maxRetryCount: 5,
    nextRetryAt: null,
    errorMessage: 'retry limit reached',
    createdOffset: '22 minutes',
    publishedAt: null,
  },
  {
    schema: 'inventory',
    eventId: '00000000-0000-4000-8000-000000002001',
    aggregateType: 'Inventory',
    aggregateId: 'pf-sku-1003',
    eventType: 'InventoryRollbackFailed',
    topic: 'stockrush.inventory.events',
    partitionKey: 'pf-sku-1003',
    correlationId: 'pf-corr-1003',
    idempotencyKey: 'pf-outbox-inventory-1003',
    status: 'FAILED',
    retryCount: 4,
    maxRetryCount: 5,
    nextRetryAt: null,
    errorMessage: 'inventory rollback publish failed',
    createdOffset: '15 minutes',
    publishedAt: null,
  },
  {
    schema: 'payment',
    eventId: '00000000-0000-4000-8000-000000003001',
    aggregateType: 'Payment',
    aggregateId: 'pf-pay-1006',
    eventType: 'PaymentPublishFailed',
    topic: 'stockrush.payment.events',
    partitionKey: 'pf-ord-1006',
    correlationId: 'pf-corr-1006',
    idempotencyKey: 'pf-outbox-payment-1006',
    status: 'FAILED',
    retryCount: 5,
    maxRetryCount: 5,
    nextRetryAt: null,
    errorMessage: 'payment failure event publish failed',
    createdOffset: '49 minutes',
    publishedAt: null,
  },
];

export const portfolioFixtures = {
  coupons,
  orders,
  couponUsages,
  fulfillmentRequests,
  outboxEvents,
};

function quote(value) {
  if (value === null || value === undefined) {
    return 'null';
  }
  return `'${String(value).replaceAll("'", "''")}'`;
}

function timestamp(offset) {
  if (!offset) {
    return 'null';
  }
  return `${baseTime} - interval ${quote(offset)}`;
}

function nullableTimestamp(offset) {
  return offset ? timestamp(offset) : 'null';
}

function jsonb(value) {
  return `${quote(JSON.stringify(value))}::jsonb`;
}

function couponRow(coupon) {
  return `(${[
    quote(coupon.couponCode),
    quote(coupon.name),
    quote(coupon.discountType),
    coupon.discountValue,
    coupon.minOrderAmount,
    coupon.maxDiscountAmount ?? 'null',
    quote(coupon.status),
    "now() - interval '1 day'",
    "now() + interval '30 days'",
    'now()',
    'now()',
  ].join(', ')})`;
}

function orderRow(order) {
  return `(${[
    quote(order.orderId),
    quote(order.memberId),
    quote(order.status),
    quote(order.sagaStatus),
    quote(order.couponCode),
    order.totalAmount,
    order.discountAmount,
    order.payableAmount,
    order.itemCount,
    quote(order.cancellationReason),
    nullableTimestamp(order.confirmedOffset),
    nullableTimestamp(order.cancelledOffset),
    timestamp(order.createdOffset),
    'now()',
  ].join(', ')})`;
}

function couponUsageRow(usage) {
  return `(${[
    quote(usage.orderId),
    quote(usage.memberId),
    quote(usage.couponCode),
    quote(usage.status),
    usage.orderAmount,
    usage.discountAmount,
    usage.payableAmount,
    timestamp(usage.reservedOffset),
    nullableTimestamp(usage.consumedOffset),
    nullableTimestamp(usage.releasedOffset),
    quote(usage.releaseReason),
    timestamp(usage.reservedOffset),
    'now()',
  ].join(', ')})`;
}

function fulfillmentRow(request) {
  return `(${[
    quote(request.requestId),
    quote(request.orderId),
    quote('PREPARING'),
    timestamp(request.requestedOffset),
    quote(request.sourceEventId),
    quote(request.correlationId),
    quote(request.idempotencyKey),
    timestamp(request.requestedOffset),
    'now()',
  ].join(', ')})`;
}

function outboxRow(event) {
  const payload = {
    portfolioSample: true,
    aggregateId: event.aggregateId,
    status: event.status,
    reason: event.errorMessage,
  };
  const headers = {
    source: 'portfolio-demo-data',
    correlationId: event.correlationId,
  };
  return `(${[
    quote(event.eventId),
    quote(event.aggregateType),
    quote(event.aggregateId),
    quote(event.eventType),
    1,
    quote(event.topic),
    quote(event.partitionKey),
    quote(event.correlationId),
    quote(event.idempotencyKey),
    jsonb(payload),
    jsonb(headers),
    quote(event.status),
    event.retryCount,
    event.maxRetryCount,
    event.nextRetryAt ?? 'null',
    quote(event.errorMessage),
    timestamp(event.createdOffset),
    event.publishedAt ?? 'null',
    'now()',
  ].join(', ')})`;
}

function insertOutboxSql(schema, rows) {
  if (rows.length === 0) {
    return '';
  }
  return `
insert into ${schema}.outbox_events (
  event_id, aggregate_type, aggregate_id, event_type, event_version, topic, partition_key,
  correlation_id, idempotency_key, payload, headers, status, retry_count, max_retry_count,
  next_retry_at, error_message, created_at, published_at, updated_at
) values
${rows.map(outboxRow).join(',\n')}
on conflict (event_id) do update set
  aggregate_type = excluded.aggregate_type,
  aggregate_id = excluded.aggregate_id,
  event_type = excluded.event_type,
  event_version = excluded.event_version,
  topic = excluded.topic,
  partition_key = excluded.partition_key,
  correlation_id = excluded.correlation_id,
  idempotency_key = excluded.idempotency_key,
  payload = excluded.payload,
  headers = excluded.headers,
  status = excluded.status,
  retry_count = excluded.retry_count,
  max_retry_count = excluded.max_retry_count,
  next_retry_at = excluded.next_retry_at,
  error_message = excluded.error_message,
  created_at = excluded.created_at,
  published_at = excluded.published_at,
  updated_at = excluded.updated_at;
`;
}

export function buildPortfolioSeedSql() {
  const orderOutboxEvents = outboxEvents.filter((event) => event.schema === 'orders');
  const inventoryOutboxEvents = outboxEvents.filter((event) => event.schema === 'inventory');
  const paymentOutboxEvents = outboxEvents.filter((event) => event.schema === 'payment');

  return `begin;

insert into promotion.coupons (
  coupon_code, name, discount_type, discount_value, min_order_amount, max_discount_amount,
  status, starts_at, ends_at, created_at, updated_at
) values
${coupons.map(couponRow).join(',\n')}
on conflict (coupon_code) do update set
  name = excluded.name,
  discount_type = excluded.discount_type,
  discount_value = excluded.discount_value,
  min_order_amount = excluded.min_order_amount,
  max_discount_amount = excluded.max_discount_amount,
  status = excluded.status,
  starts_at = excluded.starts_at,
  ends_at = excluded.ends_at,
  updated_at = excluded.updated_at;

insert into read_model.order_summaries (
  order_id, member_id, status, saga_status, coupon_code, total_amount, discount_amount,
  payable_amount, item_count, cancellation_reason, confirmed_at, cancelled_at, created_at, updated_at
) values
${orders.map(orderRow).join(',\n')}
on conflict (order_id) do update set
  member_id = excluded.member_id,
  status = excluded.status,
  saga_status = excluded.saga_status,
  coupon_code = excluded.coupon_code,
  total_amount = excluded.total_amount,
  discount_amount = excluded.discount_amount,
  payable_amount = excluded.payable_amount,
  item_count = excluded.item_count,
  cancellation_reason = excluded.cancellation_reason,
  confirmed_at = excluded.confirmed_at,
  cancelled_at = excluded.cancelled_at,
  created_at = excluded.created_at,
  updated_at = excluded.updated_at;

insert into promotion.coupon_usages (
  order_id, member_id, coupon_code, status, order_amount, discount_amount, payable_amount,
  reserved_at, consumed_at, released_at, release_reason, created_at, updated_at
) values
${couponUsages.map(couponUsageRow).join(',\n')}
on conflict (order_id) do update set
  member_id = excluded.member_id,
  coupon_code = excluded.coupon_code,
  status = excluded.status,
  order_amount = excluded.order_amount,
  discount_amount = excluded.discount_amount,
  payable_amount = excluded.payable_amount,
  reserved_at = excluded.reserved_at,
  consumed_at = excluded.consumed_at,
  released_at = excluded.released_at,
  release_reason = excluded.release_reason,
  created_at = excluded.created_at,
  updated_at = excluded.updated_at;

insert into fulfillment.fulfillment_requests (
  request_id, order_id, status, requested_at, source_event_id, correlation_id,
  idempotency_key, created_at, updated_at
) values
${fulfillmentRequests.map(fulfillmentRow).join(',\n')}
on conflict (order_id) do update set
  request_id = excluded.request_id,
  status = excluded.status,
  requested_at = excluded.requested_at,
  source_event_id = excluded.source_event_id,
  correlation_id = excluded.correlation_id,
  idempotency_key = excluded.idempotency_key,
  created_at = excluded.created_at,
  updated_at = excluded.updated_at;

delete from orders.outbox_events
 where event_id::text like '00000000-0000-4000-8000-00000000%';
delete from inventory.outbox_events
 where event_id::text like '00000000-0000-4000-8000-00000000%';
delete from payment.outbox_events
 where event_id::text like '00000000-0000-4000-8000-00000000%';
${insertOutboxSql('orders', orderOutboxEvents)}
${insertOutboxSql('inventory', inventoryOutboxEvents)}
${insertOutboxSql('payment', paymentOutboxEvents)}
commit;
`;
}

function runDockerPsql(sql) {
  const composeFiles = [
    '--env-file',
    'infra/demo/.env',
    '-f',
    'infra/demo/docker-compose.yml',
    '-f',
    'infra/demo/docker-compose.images.yml',
  ];
  const args = [
    'compose',
    ...composeFiles,
    'exec',
    '-T',
    'postgres',
    'psql',
    '-U',
    process.env.PORTFOLIO_DEMO_DB_USER ?? 'stockrush',
    '-d',
    process.env.PORTFOLIO_DEMO_DB_NAME ?? 'stockrush',
    '-v',
    'ON_ERROR_STOP=1',
  ];

  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, {
      cwd: new URL('../..', import.meta.url),
      stdio: ['pipe', 'inherit', 'inherit'],
    });
    child.stdin.end(sql);
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`docker compose psql exited with ${code}`));
      }
    });
  });
}

async function main() {
  const sql = buildPortfolioSeedSql();
  if (process.argv.includes('--print-sql')) {
    process.stdout.write(sql);
    return;
  }
  await runDockerPsql(sql);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
