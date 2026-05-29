-- V1__init: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

create table if not exists customer_orders (
  id bigserial primary key,
  order_id varchar(100) unique not null,
  member_id varchar(100) not null,
  status varchar(30) not null,
  saga_status varchar(30) not null,
  total_amount numeric(19, 2) not null,
  idempotency_key varchar(150) unique not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table if not exists order_items (
  id bigserial primary key,
  order_id varchar(100) not null,
  product_code varchar(80) not null,
  sku_id varchar(80) not null,
  quantity integer not null,
  unit_price numeric(19, 2) not null,
  created_at timestamptz not null
);

create table if not exists outbox_events (
  id bigserial primary key,
  event_id uuid unique not null,
  aggregate_type varchar(50) not null,
  aggregate_id varchar(100) not null,
  event_type varchar(100) not null,
  event_version integer not null,
  topic varchar(150) not null,
  partition_key varchar(100) not null,
  correlation_id varchar(100) not null,
  idempotency_key varchar(150) not null,
  payload jsonb not null,
  headers jsonb not null default '{}'::jsonb,
  status varchar(30) not null,
  retry_count integer not null default 0,
  max_retry_count integer not null default 5,
  next_retry_at timestamptz null,
  error_message text null,
  created_at timestamptz not null,
  published_at timestamptz null,
  updated_at timestamptz not null
);

create table if not exists processed_events (
  event_id uuid not null,
  consumer_group varchar(120) not null,
  event_type varchar(100) not null,
  aggregate_id varchar(100) not null,
  idempotency_key varchar(150) not null,
  processed_at timestamptz not null,
  primary key (event_id, consumer_group)
);

create index if not exists idx_order_items_order_id on order_items (order_id);
create index if not exists idx_customer_orders_status on customer_orders (status);
create index if not exists idx_outbox_events_relay on outbox_events (status, next_retry_at, created_at);
create index if not exists idx_outbox_events_aggregate on outbox_events (aggregate_id, created_at);

