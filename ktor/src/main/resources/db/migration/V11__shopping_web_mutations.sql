alter table shopping_mutation
    drop constraint shopping_mutation_kind_valid;

alter table shopping_mutation
    add constraint shopping_mutation_kind_valid
        check (
            kind in (
                'ADD',
                'MARK_BOUGHT',
                'UNDO_ADD',
                'UNDO_BOUGHT',
                'EDIT',
                'REMOVE',
                'RESTORE'
            )
        );

alter table shopping_mutation
    drop constraint shopping_mutation_reversal_shape;

alter table shopping_mutation
    add constraint shopping_mutation_reversal_shape
        check (
            (
                kind in ('ADD', 'MARK_BOUGHT', 'EDIT', 'REMOVE', 'RESTORE')
                    and reverses_mutation_id is null
            )
                or (
                kind in ('UNDO_ADD', 'UNDO_BOUGHT')
                    and reverses_mutation_id is not null
            )
        );
