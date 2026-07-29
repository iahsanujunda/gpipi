create table account (
    id          uuid        primary key default gen_random_uuid(),
    name        text        not null unique,
    description text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint account_name_not_blank check (btrim(name) <> '')
);

alter table category
    add column account_id uuid references account(id);

insert into account (name, description)
values ('Default wallet', 'Created automatically for existing budget lines');

update category
set account_id = (select id from account where name = 'Default wallet')
where account_id is null;

alter table category
    alter column account_id set not null;

alter table expense
    add column account_id uuid references account(id);

update expense e
set account_id = c.account_id
from category c
where e.category_id = c.id
  and e.account_id is null;

alter table expense
    alter column account_id set not null;

create table money_movement (
    id                 uuid        primary key default gen_random_uuid(),
    idempotency_key    uuid        not null unique,
    from_account_id    uuid references account(id),
    to_account_id      uuid references account(id),
    amount             bigint      not null,
    occurred_at        timestamptz not null,
    note               text,
    created_by_user_id text        not null,
    created_at         timestamptz not null default now(),
    constraint money_movement_amount_positive check (amount > 0),
    constraint money_movement_has_tracked_endpoint
        check (from_account_id is not null or to_account_id is not null),
    constraint money_movement_endpoints_differ
        check (
            from_account_id is null
                or to_account_id is null
                or from_account_id <> to_account_id
        ),
    constraint money_movement_creator_not_blank
        check (btrim(created_by_user_id) <> '')
);

create index category_account_id_idx
    on category (account_id);

create index expense_account_spent_at_idx
    on expense (account_id, spent_at desc, id desc);

create index money_movement_from_occurred_at_idx
    on money_movement (from_account_id, occurred_at desc, id desc);

create index money_movement_to_occurred_at_idx
    on money_movement (to_account_id, occurred_at desc, id desc);
