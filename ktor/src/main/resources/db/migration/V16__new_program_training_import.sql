alter table training_import
    add column owner_user_id text,
    add column target_type text not null default 'EXISTING_PROGRAM',
    add column new_program_name text,
    add column new_program_note text,
    add column new_program_starts_on date,
    add column new_program_confirmed_at timestamptz;

update training_import ti
set owner_user_id = p.owner_user_id
from program p
where p.id = ti.program_id;

alter table training_import
    alter column owner_user_id set not null,
    alter column program_id drop not null,
    add constraint training_import_owner_not_blank check (btrim(owner_user_id) <> ''),
    add constraint training_import_target_type_valid
        check (target_type in ('EXISTING_PROGRAM', 'NEW_PROGRAM')),
    add constraint training_import_existing_target_valid
        check (target_type <> 'EXISTING_PROGRAM' or program_id is not null),
    add constraint training_import_new_program_name_not_blank
        check (new_program_name is null or btrim(new_program_name) <> '');

create index training_import_owner_created_idx
    on training_import (owner_user_id, created_at desc);
