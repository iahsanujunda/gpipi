create table google_credential (
    user_id                 text        primary key,
    encrypted_refresh_token text       not null,
    scope                   text        not null,
    connected_at            timestamptz not null default now(),
    revoked_at              timestamptz,
    constraint google_credential_user_not_blank check (btrim(user_id) <> ''),
    constraint google_credential_token_not_blank check (btrim(encrypted_refresh_token) <> '')
);

create table google_oauth_state (
    state_hash  text        primary key,
    user_id     text        not null,
    return_path text        not null,
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now(),
    constraint google_oauth_state_return_path check (return_path like '/%')
);

create index google_oauth_state_expiry_idx on google_oauth_state (expires_at);

create table training_import (
    id                   uuid        primary key default gen_random_uuid(),
    program_id           uuid        not null references program(id) on delete cascade,
    spreadsheet_id       text        not null,
    spreadsheet_title    text        not null,
    selected_week_number integer,
    state                text        not null,
    error_detail         text,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    applied_at           timestamptz,
    constraint training_import_spreadsheet_not_blank check (btrim(spreadsheet_id) <> ''),
    constraint training_import_title_not_blank check (btrim(spreadsheet_title) <> ''),
    constraint training_import_selected_week_positive
        check (selected_week_number is null or selected_week_number >= 1),
    constraint training_import_state_valid check (state in (
        'READING', 'NEEDS_MAPPING', 'EXTRACTING', 'REVIEW',
        'APPLIED', 'FAILED', 'CANCELLED'
    ))
);

create index training_import_program_created_idx
    on training_import (program_id, created_at desc);

create table training_import_tab (
    id                uuid    primary key default gen_random_uuid(),
    import_id         uuid    not null references training_import(id) on delete cascade,
    google_sheet_id   bigint  not null,
    tab_title         text    not null,
    decision          text,
    target_workout_id uuid references workout(id) on delete restrict,
    new_workout_name  text,
    position          integer not null,
    unique (import_id, google_sheet_id),
    constraint training_import_tab_title_not_blank check (btrim(tab_title) <> ''),
    constraint training_import_tab_decision_valid
        check (decision is null or decision in ('WORKOUT', 'EXCLUDE')),
    constraint training_import_tab_position_positive check (position >= 1),
    constraint training_import_tab_target_valid check (
        (decision is null and target_workout_id is null and new_workout_name is null)
        or (decision = 'EXCLUDE' and target_workout_id is null and new_workout_name is null)
        or (
            decision = 'WORKOUT'
            and ((target_workout_id is not null) <> (new_workout_name is not null))
        )
    ),
    constraint training_import_tab_new_name_not_blank
        check (new_workout_name is null or btrim(new_workout_name) <> '')
);

create table training_import_week (
    id                          uuid    primary key default gen_random_uuid(),
    import_tab_id               uuid    not null references training_import_tab(id) on delete cascade,
    target_week_id              uuid references workout_week(id) on delete restrict,
    week_number                 integer not null,
    start_row                   integer not null,
    end_row                     integer not null,
    execution_boundary_col      integer not null,
    execution_header_address    text    not null,
    execution_header_value      text    not null,
    decision                    text,
    extracted_draft             jsonb,
    extraction_contract_version text,
    extraction_model            text,
    base_source_snapshot         jsonb,
    source_snapshot              jsonb,
    source_hash                  text,
    unique (import_tab_id, week_number),
    constraint training_import_week_number_positive check (week_number >= 1),
    constraint training_import_week_rows_valid check (start_row >= 1 and end_row >= start_row),
    constraint training_import_week_boundary_positive check (execution_boundary_col >= 1),
    constraint training_import_week_header_not_blank check (
        btrim(execution_header_address) <> '' and btrim(execution_header_value) <> ''
    ),
    constraint training_import_week_decision_valid
        check (decision is null or decision in ('KEEP', 'EXCLUDE'))
);

create table training_import_exercise_match (
    id                  uuid    primary key default gen_random_uuid(),
    import_week_id      uuid    not null references training_import_week(id) on delete cascade,
    source_movement_key text    not null,
    source_text         text    not null,
    decision            text,
    exercise_id         uuid references exercise(id) on delete restrict,
    new_exercise_name   text,
    execution_type      text,
    remember_as_alias   boolean not null default true,
    unique (import_week_id, source_movement_key),
    constraint training_import_match_source_not_blank check (
        btrim(source_movement_key) <> '' and btrim(source_text) <> ''
    ),
    constraint training_import_match_decision_valid
        check (decision is null or decision in ('MATCH', 'CREATE', 'EXCLUDE')),
    constraint training_import_match_target_valid check (
        (decision is null and new_exercise_name is null)
        or (decision = 'EXCLUDE' and exercise_id is null and new_exercise_name is null)
        or (decision = 'MATCH' and exercise_id is not null and new_exercise_name is null)
        or (
            decision = 'CREATE' and exercise_id is null
            and new_exercise_name is not null and btrim(new_exercise_name) <> ''
        )
    ),
    constraint training_import_match_execution_type_valid
        check (execution_type is null or execution_type in ('REPS', 'REPS_PER_SIDE', 'DURATION'))
);

create table sheet_link (
    id                   uuid        primary key default gen_random_uuid(),
    program_id           uuid        not null references program(id) on delete cascade,
    spreadsheet_id       text        not null,
    spreadsheet_title    text        not null,
    connected_by_user_id text        not null,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    replaced_at          timestamptz
);

create unique index sheet_link_one_current_per_program
    on sheet_link (program_id) where replaced_at is null;

create table sheet_week_link (
    week_id                  uuid    primary key references workout_week(id) on delete cascade,
    sheet_link_id            uuid    not null references sheet_link(id) on delete cascade,
    google_sheet_id          bigint  not null,
    tab_title                text    not null,
    week_start_row           integer not null,
    week_end_row             integer not null,
    execution_boundary_col   integer not null,
    execution_header_address text    not null,
    execution_header_value   text    not null,
    source_snapshot          jsonb   not null,
    source_hash              text    not null,
    unique (sheet_link_id, google_sheet_id, week_start_row, week_end_row)
);

create table sheet_prescription_link (
    prescription_id  uuid  primary key references prescription(id) on delete cascade,
    sheet_week_id    uuid  not null references sheet_week_link(week_id) on delete cascade,
    movement_address text  not null,
    movement_text    text  not null,
    source_cells     jsonb not null,
    execution_cells jsonb not null,
    unique (sheet_week_id, movement_address)
);

create index training_import_tab_import_idx on training_import_tab (import_id, position);
create index training_import_week_tab_idx on training_import_week (import_tab_id);
create index training_import_match_week_idx on training_import_exercise_match (import_week_id);
create index sheet_week_link_sheet_idx on sheet_week_link (sheet_link_id);
