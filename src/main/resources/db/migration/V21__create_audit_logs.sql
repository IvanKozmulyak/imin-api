create table audit_logs (
    id          uuid primary key,
    org_id      uuid not null,
    actor_id    uuid,
    actor_email varchar(320),
    action      varchar(64) not null,
    target_type varchar(32),
    target_id   uuid,
    summary     varchar(512) not null,
    occurred_at timestamp with time zone not null default now()
);
create index audit_logs_org_occurred_idx on audit_logs (org_id, occurred_at desc);
