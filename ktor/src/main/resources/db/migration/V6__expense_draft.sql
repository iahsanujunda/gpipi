create table expense_draft (
    id                    uuid primary key default gen_random_uuid(),
    inbound_message_id    uuid        not null references inbound_message(id),
    user_id               text        not null,
    channel_id            text        not null,   -- where to post/replace the card
    amount                bigint      not null,
    currency              text        not null default 'JPY',
    merchant              text,
    note                  text,
    predicted_category_id uuid        not null references category(id),
    confidence            numeric,
    model                 text,
    status                text        not null default 'PENDING',  -- PENDING | CONFIRMED
    created_at            timestamptz not null default now()
);
