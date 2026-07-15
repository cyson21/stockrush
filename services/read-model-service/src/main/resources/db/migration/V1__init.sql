-- V1__init: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

create table if not exists order_summaries (
  id bigserial primary key,
  order_id varchar(100) unique not null,
  member_id varchar(100) not null,
  status varchar(30) not null,
  saga_status varchar(30) not null,
  coupon_code varchar(80) null,
  total_amount numeric(19, 2) not null,
  discount_amount numeric(19, 2) not null,
  payable_amount numeric(19, 2) not null,
  item_count integer not null,
  cancellation_reason varchar(200) null,
  confirmed_at timestamptz null,
  cancelled_at timestamptz null,
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

create index if not exists idx_order_summaries_member_created
  on order_summaries (member_id, created_at desc, id desc);

create index if not exists idx_order_summaries_status_created
  on order_summaries (status, created_at desc, id desc);
