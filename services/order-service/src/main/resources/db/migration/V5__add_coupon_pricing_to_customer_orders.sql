-- V5__add_coupon_pricing_to_customer_orders: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

alter table customer_orders
  add column if not exists coupon_code varchar(100);

alter table customer_orders
  add column if not exists discount_amount numeric(19, 2) not null default 0;

alter table customer_orders
  add column if not exists payable_amount numeric(19, 2);

update customer_orders
set payable_amount = total_amount
where payable_amount is null;

alter table customer_orders
  alter column payable_amount set not null;

alter table customer_orders
  alter column payable_amount set default 0;
