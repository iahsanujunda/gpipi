create table shopping_add_draft (
    id                 uuid        primary key,
    inbound_message_id uuid        not null unique references inbound_message(id),
    channel_id         text        not null,
    user_id            text        not null,
    status             text        not null default 'PENDING',
    created_at         timestamptz not null default now(),
    completed_at       timestamptz,
    check (status in ('PENDING', 'CONFIRMED', 'CANCELLED')),
    check (
        (status = 'PENDING' and completed_at is null)
            or (status in ('CONFIRMED', 'CANCELLED') and completed_at is not null)
        )
);
