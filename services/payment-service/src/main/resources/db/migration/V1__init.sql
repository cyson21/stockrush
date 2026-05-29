-- 초기 스키마 생성 DDL입니다.

create table if not exists payments (
  id bigserial primary key,
  payment_id uuid unique not null,
  order_id varchar(100) not null,
  amount numeric(19, 2) not null,
  method varchar(30) not null,
  status varchar(30) not null,
  failure_reason varchar(200) null,
  idempotency_key varchar(150) unique not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
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

create index if not exists idx_payments_order_id on payments (order_id);
create index if not exists idx_outbox_events_relay on outbox_events (status, next_retry_at, created_at);
create index if not exists idx_outbox_events_aggregate on outbox_events (aggregate_id, created_at);
