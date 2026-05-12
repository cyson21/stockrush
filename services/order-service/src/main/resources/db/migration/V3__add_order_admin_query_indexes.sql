create index if not exists idx_customer_orders_created_at on customer_orders (created_at desc, id desc);
create index if not exists idx_customer_orders_saga_created_at on customer_orders (saga_status, created_at desc, id desc);
