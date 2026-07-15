-- V2__add_payment_method_to_customer_orders: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

alter table customer_orders
  add column if not exists payment_method varchar(30) not null default 'CARD';
