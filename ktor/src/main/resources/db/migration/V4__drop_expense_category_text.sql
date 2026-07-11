-- Finish the category_id cutover: backfill from the old free-text column, then drop it.
-- After this migration category_id is the sole category authority on expense.

-- 1. Backfill by matching the old text against category.name. The iter-2 prompt constrained
--    the model to exactly the names later seeded in V3, so live rows match 1:1.
update expense e
set category_id = c.id
from category c
where e.category_id is null
  and e.category = c.name;

-- 2. Safety net: any legacy row whose text matched no known category falls back to 'Other'
--    (seeded in V3, so guaranteed to exist here), keeping the NOT NULL below infallible.
update expense e
set category_id = c.id
from category c
where e.category_id is null
  and c.name = 'Other';

-- 3. category_id becomes mandatory; the free-text column is retired.
alter table expense alter column category_id set not null;
alter table expense drop column category;
