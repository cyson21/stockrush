create table if not exists coupon_usages (
  id bigserial primary key,
  order_id varchar(100) unique not null,
  member_id varchar(100) not null,
  coupon_code varchar(80) not null references coupons (coupon_code),
  status varchar(30) not null,
  order_amount numeric(19, 2) not null,
  discount_amount numeric(19, 2) not null,
  payable_amount numeric(19, 2) not null,
  reserved_at timestamptz not null,
  consumed_at timestamptz null,
  released_at timestamptz null,
  release_reason varchar(200) null,
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

create index if not exists idx_coupon_usages_coupon_status
  on coupon_usages (coupon_code, status, created_at);

create index if not exists idx_coupon_usages_order_status
  on coupon_usages (order_id, status);
