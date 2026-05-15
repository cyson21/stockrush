create table if not exists admin_actions (
    id bigserial primary key,
    action varchar(80) not null,
    target_id varchar(120) not null,
    operator_id varchar(100) not null,
    correlation_id varchar(100) not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_order_admin_actions_action_created_at
    on admin_actions (action, created_at desc);

create index if not exists idx_order_admin_actions_target_id
    on admin_actions (target_id);
