-- Capture EVERY @ai message here, including failures. This is the debugging
-- corpus, the replay set, and the dedup key — all in one table.
create table inbound_message (
    id          uuid        primary key default gen_random_uuid(),
    event_id    text        not null unique,   -- Slack event_id; also the dedup key
    user_id     text        not null,
    channel_id  text        not null,
    text        text,                           -- raw @ai message; nullable so it can be TTL'd (appendix)
    slack_ts    text        not null,           -- Slack's message timestamp
    status      text        not null default 'RECEIVED',  -- see Inbound Message Status Reference
    fail_reason text,
    received_at timestamptz not null default now()
);

create table expense (
    id                  uuid        primary key default gen_random_uuid(),
    inbound_message_id  uuid        not null references inbound_message(id),  -- source of raw text
    user_id             text        not null,          -- Slack user id
    amount              bigint      not null,          -- integer JPY (no minor unit); multi-currency later
    currency            text        not null default 'JPY',
    category            text        not null,          -- free text for now; FK in iter 3
    merchant            text,
    note                text,
    spent_at            timestamptz not null default now(),
    source              text        not null default 'SLACK',
    created_at          timestamptz not null default now()
);