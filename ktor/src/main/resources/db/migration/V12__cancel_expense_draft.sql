alter table expense_draft
    add constraint expense_draft_status_valid
        check (status in ('PENDING', 'CONFIRMED', 'CANCELLED'));
