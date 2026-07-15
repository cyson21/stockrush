-- 쿠폰 멱등성 키 저장 테이블을 초기화하는 마이그레이션입니다.

create table if not exists admin_coupon_command_idempotency (
  id bigserial primary key,
  idempotency_key varchar(120) unique not null,
  request_hash varchar(64) not null,
  coupon_code varchar(80) not null references coupons (coupon_code),
  created_at timestamptz not null default now()
);

create index if not exists idx_admin_coupon_command_idempotency_coupon_code
  on admin_coupon_command_idempotency (coupon_code);
