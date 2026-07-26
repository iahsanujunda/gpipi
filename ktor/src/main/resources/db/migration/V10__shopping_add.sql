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

create table shopping_add_draft_item (
    id          uuid primary key,
    draft_id    uuid not null references shopping_add_draft(id),
    position    integer not null,
    item        text not null,
    quantity    text,
    note        text,
    unique (draft_id, position),
    constraint shopping_add_draft_item_position_nonnegative
        check (position >= 0),
    constraint shopping_add_draft_item_item_not_blank
        check (btrim(item) <> '')
);

create table shopping_mutation (
    id                    uuid        primary key,
    kind                  text        not null,
    actor_id              text        not null,
    reverses_mutation_id  uuid        unique references shopping_mutation(id),
    created_at            timestamptz not null default now(),
    constraint shopping_mutation_kind_valid
        check (kind in ('ADD', 'MARK_BOUGHT', 'UNDO_ADD', 'UNDO_BOUGHT')),
    constraint shopping_mutation_reversal_shape
        check (
            (kind in ('ADD', 'MARK_BOUGHT') and reverses_mutation_id is null)
                or (kind in ('UNDO_ADD', 'UNDO_BOUGHT') and reverses_mutation_id is not null)
            ),
    constraint shopping_mutation_not_self_reversing
        check (
            reverses_mutation_id is null
                or reverses_mutation_id <> id
            ),
    constraint shopping_mutation_actor_not_blank
        check (btrim(actor_id) <> '')
);

create table shopping_item (
    id                  uuid        primary key,
    inbound_message_id  uuid        not null references inbound_message(id),
    item                text        not null,
    quantity            text,
    note                text,
    status              text        not null default 'PENDING',
    added_by            text        not null,
    added_at            timestamptz not null default now(),
    bought_by           text,
    bought_at           timestamptz,
    removed_by          text,
    removed_at          timestamptz,
    current_mutation_id uuid        not null references shopping_mutation(id),
    constraint shopping_item_status_valid
        check (status in ('PENDING', 'BOUGHT', 'REMOVED')),
    constraint shopping_item_pending_metadata_valid
        check (
            status <> 'PENDING'
                or (
                bought_by is null
                    and bought_at is null
                    and removed_by is null
                    and removed_at is null
                )
            ),
    constraint shopping_item_bought_metadata_valid
        check (
            status <> 'BOUGHT'
                or (
                bought_by is not null
                    and bought_at is not null
                    and removed_by is null
                    and removed_at is null
                )
            ),
    constraint shopping_item_removed_metadata_valid
        check (
            status <> 'REMOVED'
                or (
                bought_by is null
                    and bought_at is null
                    and removed_by is not null
                    and removed_at is not null
                )
            ),
    constraint shopping_item_item_not_blank
        check (btrim(item) <> ''),
    constraint shopping_item_added_by_not_blank
        check (btrim(added_by) <> ''),
    constraint shopping_item_bought_by_not_blank
        check (
            bought_by is null
                or btrim(bought_by) <> ''
            ),
    constraint shopping_item_removed_by_not_blank
        check (
            removed_by is null
                or btrim(removed_by) <> ''
            )
);

create table shopping_mutation_item (
    mutation_id uuid not null references shopping_mutation(id),
    item_id     uuid not null references shopping_item(id),
    primary key (mutation_id, item_id)
);

create unique index shopping_item_pending_identity
    on shopping_item (
        lower(btrim(item)),
        lower(btrim(coalesce(quantity, ''))),
        lower(btrim(coalesce(note, '')))
    )
    where status = 'PENDING';