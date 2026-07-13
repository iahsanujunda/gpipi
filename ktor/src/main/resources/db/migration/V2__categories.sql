create table budget_envelope (
    id          uuid primary key default gen_random_uuid(),
    name        text        not null,
    period      text        not null,
    amount      bigint      not null,
    created_at  timestamptz not null default now()
);

create table category (
    id          uuid primary key default gen_random_uuid(),
    envelope_id uuid        not null references budget_envelope(id),
    name        text        not null unique,
    description text        not null,
    active      boolean     not null default true,
    created_at  timestamptz not null default now()
);

-- expense.category becomes a FK
alter table expense add column category_id uuid references category(id);
-- backfill from the old text column, then drop it once verified