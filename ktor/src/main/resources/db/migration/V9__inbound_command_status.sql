-- Legacy deterministic commands predate terminal command statuses. Their Slack
-- delivery result is no longer recoverable, but they must not remain mixed with
-- genuinely in-flight work or future classifier training candidates.
--
-- This expression intentionally mirrors OpenBudgetCommand.matches after removing
-- the leading app mention. Do not broaden it to every RECEIVED row: some are
-- pending expense confirmations or genuine interrupted work.
update inbound_message
set status = 'COMMAND'
where status = 'RECEIVED'
  and regexp_replace(
        coalesce(text, ''),
        '^[[:space:]]*<@[^>]+>[[:space:]]*',
        ''
      ) ~* '^open( .*)?$';
