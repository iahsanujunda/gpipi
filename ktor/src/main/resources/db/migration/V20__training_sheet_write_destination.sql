alter table sheet_write
    drop constraint sheet_write_status_valid;

alter table sheet_write
    add constraint sheet_write_status_valid check (status in (
        'SCANNING', 'RESOLVED', 'NEEDS_TAB', 'NEEDS_WEEK', 'MATCHING',
        'REVIEW', 'PREPARED', 'VALIDATING', 'SENDING', 'SUCCEEDED',
        'DRIFT_ABORTED', 'VERIFY_CONFLICT', 'UNKNOWN', 'FAILED', 'CANCELLED'
    ));

alter table sheet_write_movement
    drop constraint sheet_write_movement_source_valid;

alter table sheet_write_movement
    add constraint sheet_write_movement_source_valid
        check (match_source in ('IMPORT', 'MODEL', 'MANUAL'));
