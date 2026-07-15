-- V1__init: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

create table if not exists products (
  id bigserial primary key,
  product_code varchar(80) unique not null,
  name varchar(200) not null,
  sales_status varchar(30) not null,
  list_price numeric(19, 2) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create index if not exists idx_products_sales_status on products (sales_status);

