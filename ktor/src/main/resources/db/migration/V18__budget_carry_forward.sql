create table budget_carry_forward (
    id                          uuid        primary key default gen_random_uuid(),
    category_id                 uuid        not null references category(id),
    cadence                     text        not null,
    source_window_start         date        not null,
    source_window_end_exclusive date        not null,
    target_window_start         date        not null,
    target_window_end_exclusive date        not null,
    amount                      bigint      not null,
    source_base_cap             bigint      not null,
    source_incoming_carry       bigint      not null,
    source_spent                bigint      not null,
    created_by_user_id          text        not null,
    created_at                  timestamptz not null default now(),
    constraint budget_carry_forward_cadence_valid
        check (cadence in ('WEEKLY', 'MONTHLY')),
    constraint budget_carry_forward_amount_non_zero
        check (amount <> 0),
    constraint budget_carry_forward_source_base_cap_non_negative
        check (source_base_cap >= 0),
    constraint budget_carry_forward_source_spent_non_negative
        check (source_spent >= 0),
    constraint budget_carry_forward_creator_not_blank
        check (btrim(created_by_user_id) <> ''),
    constraint budget_carry_forward_consecutive_windows
        check (
            source_window_start < source_window_end_exclusive
            and source_window_end_exclusive = target_window_start
            and target_window_start < target_window_end_exclusive
        ),
    constraint budget_carry_forward_amount_matches_snapshot
        check (amount = source_base_cap + source_incoming_carry - source_spent),
    constraint budget_carry_forward_target_unique
        unique (category_id, cadence, target_window_start)
);

create index budget_carry_forward_source_idx
    on budget_carry_forward (category_id, cadence, source_window_start);
