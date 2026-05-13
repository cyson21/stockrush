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
