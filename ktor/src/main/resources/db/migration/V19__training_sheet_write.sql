create table sheet_write (
    id                           uuid primary key default gen_random_uuid(),
    program_id                   uuid not null references program(id) on delete restrict,
    session_id                   uuid not null references training_session(id) on delete restrict,
    source_week_number           integer not null,
    source_workout_name          text not null,
    spreadsheet_id               text not null,
    spreadsheet_title            text not null,
    available_week_numbers       integer[] not null default '{}',
    discovery_snapshot           jsonb not null,
    target_week_number           integer,
    target_google_sheet_id       bigint,
    target_tab_title             text,
    target_week_start_row        integer,
    target_week_end_row          integer,
    target_week_header_address   text,
    target_week_header_value     text,
    execution_boundary_col       integer,
    execution_header_address     text,
    execution_header_value       text,
    matching_contract_version    text,
    matching_model               text,
    matching_source_snapshot     jsonb,
    matching_source_hash         text,
    execution_projection_hash    text,
    payload_hash                 text,
    written_by_user_id           text not null,
    idempotency_key              uuid not null unique,
    status                       text not null default 'SCANNING',
    api_called                   boolean not null default false,
    created_at                   timestamptz not null default now(),
    status_updated_at            timestamptz not null default now(),
    finished_at                  timestamptz,
    detail                       text,
    constraint sheet_write_source_week_positive check (source_week_number >= 1),
    constraint sheet_write_target_week_positive check (
        target_week_number is null or target_week_number >= 1
    ),
    constraint sheet_write_source_name_not_blank check (btrim(source_workout_name) <> ''),
    constraint sheet_write_spreadsheet_not_blank check (
        btrim(spreadsheet_id) <> '' and btrim(spreadsheet_title) <> ''
    ),
    constraint sheet_write_status_valid check (status in (
        'SCANNING', 'NEEDS_WEEK', 'MATCHING', 'REVIEW', 'PREPARED',
        'VALIDATING', 'SENDING', 'SUCCEEDED', 'DRIFT_ABORTED',
        'VERIFY_CONFLICT', 'UNKNOWN', 'FAILED', 'CANCELLED'
    ))
);

create table sheet_write_movement (
    id                       uuid primary key default gen_random_uuid(),
    sheet_write_id           uuid not null references sheet_write(id) on delete cascade,
    performed_exercise_id    uuid not null references performed_exercise(id) on delete restrict,
    position                 integer not null,
    sheet_movement_address   text not null,
    sheet_movement_text      text not null,
    match_source             text not null,
    confirmed                boolean not null default false,
    unique (sheet_write_id, performed_exercise_id),
    unique (sheet_write_id, sheet_movement_address),
    constraint sheet_write_movement_position_positive check (position >= 1),
    constraint sheet_write_movement_source_valid check (match_source in ('MODEL', 'MANUAL'))
);

create table sheet_write_cell (
    id                          uuid primary key default gen_random_uuid(),
    sheet_write_movement_id     uuid not null references sheet_write_movement(id) on delete cascade,
    performed_set_id            uuid references performed_set(id) on delete restrict,
    set_number                  integer not null,
    field                       text not null,
    row_index                   integer not null,
    column_index                integer not null,
    cell_address                text not null,
    observed_user_entered_value jsonb,
    observed_formatted_value    text,
    prewrite_user_entered_value jsonb,
    prewrite_formatted_value    text,
    action                      text not null,
    proposed_user_entered_value jsonb,
    verified_user_entered_value jsonb,
    verified_formatted_value    text,
    verified_at                 timestamptz,
    unique (sheet_write_movement_id, set_number, field),
    unique (sheet_write_movement_id, row_index, column_index),
    constraint sheet_write_cell_set_positive check (set_number >= 1),
    constraint sheet_write_cell_field_valid check (field in ('REPS', 'LOAD', 'RIR')),
    constraint sheet_write_cell_coordinate_valid check (row_index >= 0 and column_index >= 0),
    constraint sheet_write_cell_action_valid check (action in ('WRITE', 'CLEAR')),
    constraint sheet_write_cell_proposal_valid check (
        (action = 'WRITE' and performed_set_id is not null and proposed_user_entered_value is not null)
        or (action = 'CLEAR' and proposed_user_entered_value is null)
    )
);

create index sheet_write_session_created_idx on sheet_write (session_id, created_at desc);
create index sheet_write_owner_created_idx on sheet_write (written_by_user_id, created_at desc);
create index sheet_write_cell_performed_set_idx
    on sheet_write_cell (performed_set_id) where performed_set_id is not null;
