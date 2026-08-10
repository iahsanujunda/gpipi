create table exercise (
    id            uuid        primary key default gen_random_uuid(),
    owner_user_id text        not null,
    name          text        not null,
    demo_url      text,
    created_at    timestamptz not null default now(),
    unique (id, owner_user_id),
    constraint exercise_name_not_blank check (btrim(name) <> ''),
    constraint exercise_owner_not_blank check (btrim(owner_user_id) <> '')
);

create unique index exercise_owner_name_ci
    on exercise (owner_user_id, lower(btrim(name)));

create table exercise_alias (
    id            uuid        primary key default gen_random_uuid(),
    exercise_id   uuid        not null,
    owner_user_id text        not null,
    alias         text        not null,
    created_at    timestamptz not null default now(),
    constraint exercise_alias_exercise_owner_fk
        foreign key (exercise_id, owner_user_id)
        references exercise(id, owner_user_id) on delete cascade,
    constraint exercise_alias_not_blank check (btrim(alias) <> ''),
    constraint exercise_alias_owner_not_blank check (btrim(owner_user_id) <> '')
);

create unique index exercise_alias_owner_alias_ci
    on exercise_alias (owner_user_id, lower(btrim(alias)));

create table program (
    id            uuid        primary key default gen_random_uuid(),
    owner_user_id text        not null,
    name          text        not null,
    note          text,
    starts_on     date,
    active        boolean     not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint program_name_not_blank check (btrim(name) <> ''),
    constraint program_owner_not_blank check (btrim(owner_user_id) <> '')
);

create unique index program_one_active_per_owner
    on program (owner_user_id) where active;
create index program_owner_idx on program (owner_user_id);

create table workout (
    id         uuid    primary key default gen_random_uuid(),
    program_id uuid    not null references program(id) on delete cascade,
    name       text    not null,
    note       text,
    position   integer not null,
    unique (program_id, position),
    constraint workout_position_positive check (position >= 1),
    constraint workout_name_not_blank check (btrim(name) <> '')
);

create table workout_week (
    id          uuid    primary key default gen_random_uuid(),
    workout_id  uuid    not null references workout(id) on delete cascade,
    week_number integer not null,
    skipped_at  timestamptz,
    unique (workout_id, week_number),
    constraint workout_week_number_positive check (week_number >= 1)
);

create table workout_group (
    id       uuid    primary key default gen_random_uuid(),
    week_id  uuid    not null references workout_week(id) on delete cascade,
    label    text    not null,
    kind     text    not null,
    position integer not null,
    unique (week_id, position),
    constraint workout_group_kind_valid check (kind in ('STRAIGHT_SET', 'SUPERSET')),
    constraint workout_group_position_positive check (position >= 1),
    constraint workout_group_label_not_blank check (btrim(label) <> '')
);

create table prescription (
    id             uuid    primary key default gen_random_uuid(),
    group_id       uuid    not null references workout_group(id) on delete cascade,
    exercise_id    uuid    not null references exercise(id) on delete restrict,
    position       integer not null,
    execution_type text    not null,
    sets           text,
    rest           text,
    reps           text,
    load           text,
    rir            text,
    tempo          text,
    note           text,
    archived_at    timestamptz,
    unique (group_id, position),
    constraint prescription_position_positive check (position >= 1),
    constraint prescription_execution_type_valid
        check (execution_type in ('REPS', 'REPS_PER_SIDE', 'DURATION'))
);

create table training_session (
    id                   uuid        primary key default gen_random_uuid(),
    week_id              uuid        not null references workout_week(id) on delete restrict,
    performed_on         date        not null,
    status               text        not null default 'IN_PROGRESS',
    note                 text,
    started_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    execution_updated_at timestamptz,
    completed_at         timestamptz,
    unique (week_id),
    constraint training_session_status_valid check (status in ('IN_PROGRESS', 'COMPLETED')),
    constraint training_session_completion_consistent check (
        (status = 'IN_PROGRESS' and completed_at is null)
        or (status = 'COMPLETED' and completed_at is not null)
    )
);

create table performed_exercise (
    id                    uuid    primary key default gen_random_uuid(),
    session_id            uuid    not null references training_session(id) on delete cascade,
    exercise_id           uuid    not null references exercise(id) on delete restrict,
    prescription_id       uuid    not null references prescription(id) on delete restrict,
    position              integer not null,
    note                  text,
    target_group_label    text    not null,
    target_group_kind     text    not null,
    target_exercise_name  text    not null,
    target_demo_url       text,
    target_execution_type text    not null,
    target_sets           text,
    target_rest           text,
    target_reps           text,
    target_load           text,
    target_rir            text,
    target_tempo          text,
    target_note           text,
    unique (session_id, prescription_id),
    unique (session_id, position),
    constraint performed_exercise_position_positive check (position >= 1),
    constraint performed_exercise_group_kind_valid
        check (target_group_kind in ('STRAIGHT_SET', 'SUPERSET')),
    constraint performed_exercise_execution_type_valid
        check (target_execution_type in ('REPS', 'REPS_PER_SIDE', 'DURATION'))
);

create table performed_set (
    id                    uuid          primary key default gen_random_uuid(),
    performed_exercise_id uuid          not null references performed_exercise(id) on delete cascade,
    set_number            integer       not null,
    reps                  integer,
    duration_s            integer,
    load                  numeric(8, 2),
    rir                   integer,
    note                  text,
    target_reps           text,
    target_load           text,
    target_rir            text,
    target_tempo          text,
    logged_at             timestamptz   not null default now(),
    updated_at            timestamptz   not null default now(),
    deleted_at            timestamptz,
    unique (performed_exercise_id, set_number),
    constraint performed_set_number_positive check (set_number >= 1),
    constraint performed_set_reps_nonnegative check (reps is null or reps >= 0),
    constraint performed_set_duration_nonnegative check (duration_s is null or duration_s >= 0),
    constraint performed_set_load_nonnegative check (load is null or load >= 0),
    constraint performed_set_one_primary_measure check (num_nonnulls(reps, duration_s) = 1)
);

create index workout_program_idx on workout (program_id);
create index exercise_alias_exercise_idx on exercise_alias (exercise_id);
create index workout_week_workout_idx on workout_week (workout_id);
create index workout_group_week_idx on workout_group (week_id);
create index prescription_group_idx on prescription (group_id);
create index prescription_exercise_idx on prescription (exercise_id);
create index training_session_week_idx on training_session (week_id);
create index performed_exercise_session_idx on performed_exercise (session_id);
create index performed_set_exercise_active_idx
    on performed_set (performed_exercise_id, set_number) where deleted_at is null;
