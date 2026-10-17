-- Enable Supabase Realtime for relay_private.envelopes.
-- Apply AFTER supabase/encrypted_relay.sql. This file is idempotent and safe to re-run.
--
-- The relay_private.envelopes table must be in the supabase_realtime publication so that
-- postgres_changes WebSocket subscriptions can deliver INSERT events to clients.
-- replica identity FULL is required for RLS filters (employee_device_id=eq(...))
-- to evaluate correctly on each incoming row.

BEGIN;

-- Add the relay_private.envelopes table to the supabase_realtime publication
-- if not already present.
-- Note: The relay table is in the relay_private schema, not public.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and tablename = 'envelopes'
          and schemaname = 'relay_private'
    ) then
        alter publication supabase_realtime add table relay_private.envelopes;
    end if;
end $$;

-- Set replica identity to FULL so RLS filters evaluate on the complete row,
-- not just the primary key. This is required for correct filtering behavior.
alter table relay_private.envelopes replica identity full;

COMMIT;
