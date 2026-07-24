create table auth_nonce (
    id          uuid        primary key default gen_random_uuid(),
    nonce_hash  text        not null unique,
    user_id     text        not null,
    expires_at  timestamptz not null,
    consumed_at timestamptz,
    created_at  timestamptz not null default now()
);
