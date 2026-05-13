create table if not exists outbox_admin_actions (
    id bigserial primary key,
    action varchar(50) not null,
    requested_batch_size integer not null,
    affected_count integer not null,
    operator_id varchar(100) not null,
    correlation_id varchar(100) not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_outbox_admin_actions_created_at
    on outbox_admin_actions (created_at desc);
