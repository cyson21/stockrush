create table if not exists coupons (
  id bigserial primary key,
  coupon_code varchar(80) unique not null,
  name varchar(200) not null,
  discount_type varchar(30) not null,
  discount_value numeric(19, 2) not null,
  min_order_amount numeric(19, 2) not null,
  max_discount_amount numeric(19, 2),
  status varchar(30) not null,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create index if not exists idx_coupons_status on coupons (status, created_at);
