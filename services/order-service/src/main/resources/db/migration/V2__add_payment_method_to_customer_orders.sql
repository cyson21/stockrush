alter table customer_orders
  add column if not exists payment_method varchar(30) not null default 'CARD';
