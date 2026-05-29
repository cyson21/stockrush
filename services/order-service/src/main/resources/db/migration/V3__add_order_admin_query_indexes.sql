-- V3__add_order_admin_query_indexes: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

create index if not exists idx_customer_orders_created_at on customer_orders (created_at desc, id desc);
create index if not exists idx_customer_orders_saga_created_at on customer_orders (saga_status, created_at desc, id desc);
