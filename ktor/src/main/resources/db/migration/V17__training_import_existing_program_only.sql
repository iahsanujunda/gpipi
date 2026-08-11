-- Import may only ever add workouts to an existing, member-created program.
-- The new-program import path is removed: program creation stays manual-only, so an
-- import can never create, activate, or deactivate a program.

-- Abandoned new-program imports never produced a program row; drop them so program_id
-- can become mandatory. Applied new-program imports already created their program during
-- Apply, so fold them into the single supported target type.
delete from training_import where program_id is null;

update training_import
set target_type = 'EXISTING_PROGRAM'
where target_type <> 'EXISTING_PROGRAM';

alter table training_import
    alter column program_id set not null;

alter table training_import
    drop constraint if exists training_import_target_type_valid;

alter table training_import
    add constraint training_import_target_type_valid check (target_type = 'EXISTING_PROGRAM');

-- The new_program_* columns are now always null. They are left in place to avoid a
-- destructive column drop and a pgen regeneration; nothing reads or writes them.
