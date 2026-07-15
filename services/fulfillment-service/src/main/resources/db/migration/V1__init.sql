-- 초기 스키마 생성 DDL입니다.

create table if not exists fulfillment_requests (
  id bigserial primary key,
  request_id uuid unique not null,
  order_id varchar(100) unique not null,
  status varchar(30) not null,
  requested_at timestamptz not null,
  source_event_id uuid not null,
  correlation_id varchar(100) not null,
  idempotency_key varchar(150) not null,
  created_at timestamptz not null,
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

create index if not exists idx_fulfillment_requests_status
  on fulfillment_requests (status, created_at);

create index if not exists idx_fulfillment_requests_order_status
  on fulfillment_requests (order_id, status);
