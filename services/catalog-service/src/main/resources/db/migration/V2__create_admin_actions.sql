-- V2__create_admin_actions: 스키마/제약조건/인덱스 같은 영속 구조를 반영하는 마이그레이션입니다.

create table if not exists admin_actions (
    id bigserial primary key,
    action varchar(80) not null,
    target_id varchar(120) not null,
    operator_id varchar(100) not null,
    correlation_id varchar(100) not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_catalog_admin_actions_action_created_at
    on admin_actions (action, created_at desc);

create index if not exists idx_catalog_admin_actions_target_id
    on admin_actions (target_id);
