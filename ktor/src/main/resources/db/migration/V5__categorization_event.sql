create table categorization_event (
    id                    uuid primary key default gen_random_uuid(),
    inbound_message_id    uuid        not null references inbound_message(id),  -- raw text lives here
    expense_id            uuid        references expense(id),
    predicted_category_id uuid        references category(id),
    final_category_id     uuid        references category(id),
    was_corrected         boolean     not null,
    confidence            numeric,
    model                 text,
    created_at            timestamptz not null default now()
);