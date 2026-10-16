-- Executable psql regression suite (Postgres 13+ with pgcrypto installed).
-- From the project root, against a DISPOSABLE empty standard Postgres database:
--   psql "$TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 -v shim=true \
--     -f supabase/tests/team_pairing.sql
-- Optional: add -v crypto_public=true to test an already-installed pgcrypto in
-- public rather than extensions. No Supabase service or network is needed.
-- The shim creates auth.users/auth.uid(), anon/authenticated, applies schema.sql,
-- simulates Supabase's broad default grants (including column-level grants),
-- then applies team_pairing.sql twice, encrypted_relay.sql, team_pairing.sql
-- again, and encrypted_relay.sql again. It persists only setup, not fixtures.
-- NEVER use shim=true on a real Supabase database: it replaces auth.uid().
--
-- On a disposable Supabase/test database already migrated, omit shim=true:
--   psql "$TEST_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
--     -f supabase/tests/team_pairing.sql
-- Requires a trusted migration/test role able to SET ROLE and write auth.users.
-- Tests use reserved 1000... account / 2000... device UUIDs and ROLLBACK fixtures.
-- Errors stop execution; a disconnected failed test transaction rolls back.
-- Two-session concurrency requires separate connections; the lock/consume design
-- is described in team_pairing.sql and can be exercised independently of this
-- single-session, transaction-based regression suite.

\set ON_ERROR_STOP on
\if :{?shim}
\else
    \set shim false
\endif
\if :{?crypto_public}
\else
    \set crypto_public false
\endif
\if :shim
    DO $shim$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'authenticated') THEN
            CREATE ROLE authenticated NOLOGIN;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'anon') THEN
            CREATE ROLE anon NOLOGIN;
        END IF;
    END
    $shim$;
    CREATE SCHEMA auth;
    CREATE TABLE auth.users (
        id uuid PRIMARY KEY,
        email text,
        raw_user_meta_data jsonb NOT NULL DEFAULT '{}'::jsonb
    );
    CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE SET search_path = '' AS $$
        SELECT NULLIF(pg_catalog.current_setting('request.jwt.claim.sub', true), '')::uuid
    $$;
    GRANT USAGE ON SCHEMA auth TO anon, authenticated;
    GRANT EXECUTE ON FUNCTION auth.uid() TO anon, authenticated;
    \if :crypto_public
        CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
    \endif
    \ir ../schema.sql
    GRANT USAGE ON SCHEMA public TO anon, authenticated;
    GRANT ALL ON public.users, public.devices, public.payment_events,
        public.cashbook_entries TO anon, authenticated;
    GRANT UPDATE (paired_with_device_id, user_id), INSERT (id, user_id, device_name)
        ON public.devices TO authenticated;
    \ir ../team_pairing.sql
    \ir ../team_pairing.sql
    \ir ../encrypted_relay.sql
    \ir ../team_pairing.sql
    \ir ../encrypted_relay.sql
\endif

BEGIN;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '5s';

CREATE FUNCTION pg_temp.assert_true(p_condition boolean, p_label text)
RETURNS void LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    IF p_condition IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'FAIL: %', p_label;
    END IF;
END
$$;
CREATE FUNCTION pg_temp.expect_error(p_sql text, p_sqlstate text)
RETURNS void LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = p_sqlstate THEN RETURN; END IF;
        RAISE EXCEPTION 'Expected SQLSTATE %, got % (%): %', p_sqlstate, SQLSTATE, SQLERRM, p_sql;
    END;
    RAISE EXCEPTION 'Expected SQLSTATE %, statement succeeded: %', p_sqlstate, p_sql;
END
$$;
CREATE TEMP TABLE team_pairing_test_state (key text PRIMARY KEY, value jsonb);
GRANT SELECT, INSERT, UPDATE ON team_pairing_test_state TO authenticated, anon;

\set account_a '10000000-0000-0000-0000-000000000001'
\set account_b '10000000-0000-0000-0000-000000000002'
\set account_c '10000000-0000-0000-0000-000000000003'
\set owner '20000000-0000-0000-0000-000000000001'
\set employee '20000000-0000-0000-0000-000000000002'
\set other_a '20000000-0000-0000-0000-000000000003'
\set employee2 '20000000-0000-0000-0000-000000000004'
\set outsider '20000000-0000-0000-0000-000000000005'
\set spare '20000000-0000-0000-0000-000000000006'
\set owner_key '30000000-0000-0000-0000-000000000001'
\set employee_key '30000000-0000-0000-0000-000000000002'
\set employee_key_rotated '30000000-0000-0000-0000-000000000003'
\set relay_message '40000000-0000-0000-0000-000000000001'
\set relay_expiring '40000000-0000-0000-0000-000000000002'
\set relay_cascade '40000000-0000-0000-0000-000000000003'

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
    (:'account_a', 'team-a@example.test', '{"full_name":"Private account A"}'),
    (:'account_b', 'team-b@example.test', '{"name":"Private account B"}'),
    (:'account_c', 'team-c@example.test', '{"display_name":"Private account C"}');

-- Only the five intended authenticated entry points, all fixed-path definers.
SELECT pg_temp.assert_true((SELECT count(*) = 5 AND bool_and(p.prosecdef)
    AND bool_and(p.proconfig @> ARRAY['search_path=""'])
    FROM pg_catalog.pg_proc AS p JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname IN ('team_register_device', 'team_create_invite',
        'team_cancel_invite', 'team_join', 'team_remove_connection')), 'fixed-path SECURITY DEFINER RPCs');
SELECT pg_temp.assert_true((SELECT count(*) = 8 AND bool_and(p.prosecdef)
    AND bool_and(p.proconfig @> ARRAY['search_path=""'])
    FROM pg_catalog.pg_proc AS p JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname IN ('relay_register_key', 'relay_rotate_key',
        'relay_bind_peer_key', 'relay_peer_key', 'relay_enqueue', 'relay_fetch', 'relay_ack',
        'relay_cleanup_expired')), 'encrypted relay fixed-path SECURITY DEFINER RPCs');
SELECT pg_temp.assert_true(NOT has_schema_privilege('authenticated', 'team_private', 'USAGE'),
    'private schema is not client-accessible');
SELECT pg_temp.assert_true(NOT has_schema_privilege('authenticated', 'relay_private', 'USAGE'),
    'relay private schema is not client-accessible');
SELECT pg_temp.assert_true(NOT has_table_privilege('authenticated', 'public.devices', 'UPDATE')
    AND NOT has_column_privilege('authenticated', 'public.devices', 'paired_with_device_id', 'UPDATE')
    AND NOT has_column_privilege('authenticated', 'public.devices', 'id', 'INSERT'),
    'both table and column device write grants revoked');
SELECT pg_temp.assert_true(NOT has_table_privilege('authenticated', 'relay_private.envelopes', 'SELECT')
    AND NOT has_table_privilege('authenticated', 'relay_private.envelopes', 'INSERT')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'relay_private' AND table_name = 'envelopes'
          AND column_name IN ('payload', 'body', 'plaintext', 'raw_body')),
    'relay stores opaque envelopes with no plaintext payload column');

SET LOCAL ROLE anon;
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'owner', 'No'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_create_invite(%L)', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_cancel_invite(%L)', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_join(%L, %L)', :'owner', '000000000000'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_remove_connection(%L, %L)', :'owner', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.relay_peer_key(%L)', :'employee'), '42501');
SELECT pg_temp.expect_error('SELECT * FROM public.devices', '42501');
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '', true);
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'owner', 'No'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_create_invite(%L)', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_cancel_invite(%L)', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_join(%L, %L)', :'owner', '000000000000'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_remove_connection(%L, %L)', :'owner', :'owner'), '42501');
SELECT set_config('request.jwt.claim.sub', '10000000-0000-0000-0000-000000000099', true);
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'owner', 'No auth row'), '42501');

-- Registration, users upsert, ownership, validation and last_seen refresh.
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('initial', public.team_register_device(:'owner', '  Owner tablet  '));
SELECT pg_temp.assert_true((SELECT value = jsonb_build_object(
    'device', jsonb_build_object('id', :'owner', 'name', 'Owner tablet'),
    'employees', '[]'::jsonb, 'owner', NULL) FROM team_pairing_test_state WHERE key = 'initial'),
    'registration exact standalone contract');
SELECT pg_temp.assert_true((SELECT email = 'team-a@example.test' AND display_name = 'Private account A'
    FROM public.users WHERE id = :'account_a'), 'auth metadata populates users');
SELECT public.team_register_device(:'other_a', 'Other A device');
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'owner', '  '), '22023');
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'owner', repeat('x', 121)), '22023');
SELECT pg_temp.expect_error('SELECT public.team_register_device(NULL, ''Name'')', '22023');
RESET ROLE;
UPDATE auth.users SET email = 'team-a-new@example.test', raw_user_meta_data = '{"display_name":"Updated A"}'
    WHERE id = :'account_a';
UPDATE public.devices SET last_seen_at = '2000-01-01' WHERE id = :'owner';
SET LOCAL ROLE authenticated;
SELECT public.team_register_device(:'owner', 'Owner tablet');
SELECT pg_temp.assert_true((SELECT email = 'team-a-new@example.test' AND display_name = 'Updated A'
    FROM public.users WHERE id = :'account_a'), 'users upsert refreshes metadata');
SELECT pg_temp.assert_true((SELECT last_seen_at > now() - interval '1 minute'
    FROM public.devices WHERE id = :'owner'), 'last_seen refreshed');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT public.team_register_device(:'employee', 'Employee terminal');
SELECT public.team_register_device(:'employee2', 'Second terminal');
SELECT public.team_register_device(:'spare', 'Spare terminal');
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'owner', 'Stolen'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_create_invite(%L)', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_cancel_invite(%L)', :'owner'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_join(%L, %L)', :'owner', '000000000000'), '42501');
SELECT pg_temp.expect_error(format('SELECT public.team_remove_connection(%L, %L)', :'owner', :'owner'), '42501');
SELECT set_config('request.jwt.claim.sub', :'account_c', true);
SELECT public.team_register_device(:'outsider', 'Outside tablet');
INSERT INTO team_pairing_test_state VALUES ('outside_invite', public.team_create_invite(:'outsider'));
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.expect_error(format('SELECT public.team_register_device(%L, %L)', :'employee', 'Stolen'), '42501');
SELECT pg_temp.assert_true((SELECT device_name = 'Owner tablet' FROM public.devices WHERE id = :'owner'),
    'stolen registration did not overwrite device');

-- Self code fails without consuming; client never sees the stored hash.
INSERT INTO team_pairing_test_state VALUES ('invite', public.team_create_invite(:'owner'));
SELECT pg_temp.assert_true((SELECT (value ->> 'code') ~ '^[0-9A-F]{12}$'
    AND (value ->> 'expires_at')::timestamptz BETWEEN clock_timestamp() + interval '9 minutes'
        AND clock_timestamp() + interval '10 minutes'
    FROM team_pairing_test_state WHERE key = 'invite'), 'secure code shape and ten-minute TTL');
SELECT pg_temp.assert_true(public.team_join(:'owner',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'invite'))
    = '{"error":"self_pairing"}'::jsonb, 'self pairing rejected');
RESET ROLE;
SELECT pg_temp.assert_true((SELECT i.code_hash = sha256(convert_to(s.value ->> 'code', 'UTF8'))
    AND octet_length(i.code_hash) = 32 FROM team_private.invites i
    CROSS JOIN team_pairing_test_state s WHERE i.owner_device_id = :'owner' AND s.key = 'invite'),
    'only the SHA-256 code hash is stored');
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'team_private' AND table_name = 'invites'
    AND column_name IN ('code', 'invite_code')), 'no plaintext code column');
UPDATE public.devices SET device_token = 'PRIVATE_FCM_TOKEN' WHERE id IN (:'owner', :'employee');
INSERT INTO public.payment_events (device_id, kind, amount, dedup_key, raw_body) VALUES
    (:'owner', 'credit', 11, 'pairing-regression-owner', 'PRIVATE_OWNER_PAYMENT'),
    (:'employee', 'credit', 22, 'pairing-regression-employee', 'PRIVATE_EMPLOYEE_PAYMENT');
INSERT INTO public.cashbook_entries (device_id, label, kind, amount, note) VALUES
    (:'owner', 'Owner fixture', 'credit', 11, 'PRIVATE_OWNER_CASHBOOK'),
    (:'employee', 'Employee fixture', 'credit', 22, 'PRIVATE_EMPLOYEE_CASHBOOK');

-- An outstanding invite reserves the owner role until cancelled or expired.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT public.team_create_invite(:'spare');
SELECT pg_temp.assert_true(public.team_join(:'spare',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'invite'))
    = '{"error":"device_is_owner"}'::jsonb, 'active invite prevents employee role');
SELECT public.team_cancel_invite(:'spare');
SELECT public.team_cancel_invite(:'spare');
SELECT pg_temp.assert_true(public.team_join(:'employee', '  ' || lower(
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'invite')) || '  ') IS NULL,
    'two different accounts pair with normalized code');
SELECT pg_temp.assert_true(public.team_join(:'employee2',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'invite'))
    = '{"error":"invalid_invite"}'::jsonb, 'code is single use');
INSERT INTO team_pairing_test_state VALUES ('employee_state', public.team_register_device(:'employee', 'Employee terminal'));
SELECT pg_temp.assert_true((SELECT value -> 'device' = jsonb_build_object('id', :'employee', 'name', 'Employee terminal')
    AND value -> 'employees' = '[]'::jsonb
    AND value -> 'owner' = jsonb_build_object('pairing_id', value #>> '{owner,pairing_id}',
        'device_id', :'owner', 'device_name', 'Owner tablet')
    FROM team_pairing_test_state WHERE key = 'employee_state'), 'employee exact readback; no private user/token fields');
SELECT pg_temp.expect_error(format('SELECT public.team_create_invite(%L)', :'employee'), '22023');
SELECT pg_temp.assert_true(public.team_join(:'employee',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'outside_invite'))
    = '{"error":"already_paired"}'::jsonb, 'employee cannot have a second owner');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('owner_state', public.team_register_device(:'owner', 'Owner tablet'));
SELECT pg_temp.assert_true((SELECT value = jsonb_build_object(
    'device', jsonb_build_object('id', :'owner', 'name', 'Owner tablet'), 'owner', NULL,
    'employees', jsonb_build_array(jsonb_build_object(
        'pairing_id', value #>> '{employees,0,pairing_id}',
        'device_id', :'employee', 'device_name', 'Employee terminal')))
    FROM team_pairing_test_state WHERE key = 'owner_state'), 'owner sees only employee DEVICE name');
SELECT pg_temp.assert_true(public.team_join(:'owner',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'outside_invite'))
    = '{"error":"device_is_owner"}'::jsonb, 'owner cannot become an employee / form a chain');
RESET ROLE;
SELECT pg_temp.assert_true((SELECT paired_with_device_id = :'owner' FROM public.devices WHERE id = :'employee')
    AND (SELECT paired_with_device_id IS NULL FROM public.devices WHERE id = :'owner'), 'legacy pointer only on employee');

-- Rotate, cancel and expire codes; none can subsequently pair another device.
SET LOCAL ROLE authenticated;
INSERT INTO team_pairing_test_state VALUES ('old_invite', public.team_create_invite(:'owner'));
INSERT INTO team_pairing_test_state VALUES ('rotated_invite', public.team_create_invite(:'owner'));
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.team_join(:'employee2',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'old_invite'))
    = '{"error":"invalid_invite"}'::jsonb, 'rotated code invalid');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT public.team_cancel_invite(:'owner');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.team_join(:'employee2',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'rotated_invite'))
    = '{"error":"invalid_invite"}'::jsonb, 'cancelled code invalid');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('expired_invite', public.team_create_invite(:'owner'));
RESET ROLE;
SELECT pg_temp.assert_true((SELECT count(*) = 1 FROM team_private.invites WHERE owner_device_id = :'owner'),
    'at most one outstanding invite per owner device');
UPDATE team_private.invites SET created_at = clock_timestamp() - interval '11 minutes',
    expires_at = clock_timestamp() - interval '1 minute' WHERE owner_device_id = :'owner';
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.team_join(:'employee2',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'expired_invite'))
    = '{"error":"invalid_invite"}'::jsonb, 'expired code invalid using wall clock');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('second_invite', public.team_create_invite(:'owner'));
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.team_join(:'employee2',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'second_invite')) IS NULL, 'owner supports many employees');
INSERT INTO team_pairing_test_state VALUES ('employee2_state', public.team_register_device(:'employee2', 'Second terminal'));
SELECT public.team_create_invite(:'spare');
RESET ROLE;
UPDATE team_private.invites SET created_at = clock_timestamp() - interval '11 minutes',
    expires_at = clock_timestamp() - interval '1 minute' WHERE owner_device_id = :'spare';
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('third_invite', public.team_create_invite(:'owner'));
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.team_join(:'spare',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key = 'third_invite')) IS NULL,
    'expired own invite no longer reserves owner role');
INSERT INTO team_pairing_test_state VALUES ('spare_state', public.team_register_device(:'spare', 'Spare terminal'));

-- Encrypted relay: keys are public metadata, envelopes are opaque bytea, and
-- only the owner endpoint may enqueue to its employee endpoint.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.assert_true((public.relay_register_key(:'owner', :'owner_key',
    pg_catalog.decode(repeat('a1', 32), 'hex')) ->> 'rotated')::boolean,
    'owner key registration succeeds');
SELECT pg_temp.assert_true((public.relay_register_key(:'owner', :'owner_key',
    pg_catalog.decode(repeat('a1', 32), 'hex')) ->> 'rotated')::boolean IS FALSE,
    'same key registration is idempotent');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true((public.relay_register_key(:'employee', :'employee_key',
    pg_catalog.decode(repeat('b2', 32), 'hex')) ->> 'rotated')::boolean,
    'employee key registration succeeds');
SELECT pg_temp.expect_error(format('SELECT public.relay_register_key(%L,%L,%L)',
    :'owner', :'employee_key', '\\x01'::bytea), '42501');
SELECT pg_temp.expect_error(format('SELECT public.relay_bind_peer_key(%L,%L,%L)',
    :'owner', :'employee', :'employee_key'), '42501');

SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('relay_binding',
    public.relay_bind_peer_key(:'owner', :'employee', :'employee_key'));
SELECT pg_temp.assert_true((SELECT value ->> 'employee_key_id' = :'employee_key'
    AND value ->> 'peer_public_key_hex' IS NOT NULL
    FROM team_pairing_test_state WHERE key = 'relay_binding'),
    'owner binds current employee peer key');
-- The owner cannot read the employee's relay peer lookup, and a device with no
-- binding gets no key material; only the bound employee sees the owner key.
SELECT pg_temp.assert_true(public.relay_peer_key(:'employee') IS NULL,
    'owner endpoint sees no owner key for itself');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
INSERT INTO team_pairing_test_state VALUES ('relay_peer', public.relay_peer_key(:'employee'));
SELECT pg_temp.assert_true((SELECT value ->> 'owner_device_id' = :'owner'
    AND value ->> 'owner_key_id' = :'owner_key'
    AND value ->> 'employee_key_id' = :'employee_key'
    AND pg_catalog.decode(value ->> 'owner_public_key_hex', 'hex')
        = pg_catalog.decode(repeat('a1', 32), 'hex')
    AND value ? 'owner_public_key_hex'
    AND NOT value ? 'private_key'
    FROM team_pairing_test_state WHERE key = 'relay_peer'),
    'employee reads the bound owner public key and no private material');
SELECT pg_temp.assert_true(public.relay_peer_key(:'outsider') IS NULL,
    'another account gets no key material for an unowned employee device');
SELECT pg_temp.expect_error(format('SELECT public.relay_peer_key(%L)', NULL), '22023');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('relay_first', public.relay_enqueue(
    :'owner', :'employee', :'relay_message', pg_catalog.decode(repeat('11', 32), 'hex'), 3600));
INSERT INTO team_pairing_test_state VALUES ('relay_duplicate', public.relay_enqueue(
    :'owner', :'employee', :'relay_message', pg_catalog.decode(repeat('ff', 32), 'hex'), 3600));
SELECT pg_temp.assert_true((SELECT (value ->> 'deduplicated')::boolean
    AND value ->> 'message_id' = (SELECT value ->> 'message_id'
        FROM team_pairing_test_state WHERE key = 'relay_first')
    FROM team_pairing_test_state WHERE key = 'relay_duplicate'),
    'enqueue is idempotent and does not replace the first envelope');
RESET ROLE;
SELECT pg_temp.assert_true((SELECT count(*) = 1
    AND bool_and(encrypted_envelope = pg_catalog.decode(repeat('11', 32), 'hex'))
    FROM relay_private.envelopes WHERE client_message_id = :'relay_message'),
    'relay stores exactly one opaque envelope');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.expect_error(format('SELECT * FROM public.relay_fetch(%L)', :'owner'), '42501');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
INSERT INTO team_pairing_test_state
SELECT 'relay_lease', COALESCE(jsonb_agg(pg_catalog.jsonb_build_object(
    'message_id', f.message_id, 'lease_token', f.lease_token,
    'envelope_hex', pg_catalog.encode(f.encrypted_envelope, 'hex'))), '[]'::jsonb)
FROM public.relay_fetch(:'employee', 10, 300) AS f;
SELECT pg_temp.assert_true((SELECT jsonb_array_length(value) = 1
    AND value #>> '{0,envelope_hex}' = repeat('11', 32)
    FROM team_pairing_test_state WHERE key = 'relay_lease'),
    'employee fetch leases the opaque envelope');
SELECT pg_temp.assert_true((SELECT count(*) = 0 FROM public.relay_fetch(:'employee', 10, 300)),
    'active lease is not fetched twice');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.expect_error(format('SELECT public.relay_ack(%L,%L,%L)', :'employee',
    (SELECT value #>> '{0,message_id}' FROM team_pairing_test_state WHERE key = 'relay_lease'),
    (SELECT value #>> '{0,lease_token}' FROM team_pairing_test_state WHERE key = 'relay_lease')), '42501');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.relay_ack(:'employee',
    (SELECT (value #>> '{0,message_id}')::uuid FROM team_pairing_test_state WHERE key = 'relay_lease'),
    '00000000-0000-0000-0000-000000000099') IS FALSE, 'wrong lease token cannot ack');
SELECT pg_temp.assert_true(public.relay_ack(:'employee',
    (SELECT (value #>> '{0,message_id}')::uuid FROM team_pairing_test_state WHERE key = 'relay_lease'),
    (SELECT (value #>> '{0,lease_token}')::uuid FROM team_pairing_test_state WHERE key = 'relay_lease')),
    'employee acks its live lease');
RESET ROLE;
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM relay_private.envelopes
    WHERE client_message_id = :'relay_message'), 'ack deletes the envelope');

-- Rotation invalidates the old binding; a fresh binding is required before a
-- new owner-to-employee envelope can be queued.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.relay_peer_key(:'employee') IS NOT NULL,
    'existing peer lookup still resolves before rotation');
SELECT pg_temp.assert_true((public.relay_rotate_key(:'employee', :'employee_key_rotated',
    pg_catalog.decode(repeat('c3', 32), 'hex')) ->> 'rotated')::boolean,
    'employee key rotation succeeds');
SELECT pg_temp.assert_true(public.relay_peer_key(:'employee') IS NULL,
    'rotation drops the peer lookup until the owner rebinds');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.expect_error(format('SELECT public.relay_enqueue(%L,%L,%L,%L)',
    :'owner', :'employee', :'relay_expiring', '\\x22'::bytea), '42501');
SELECT public.relay_bind_peer_key(:'owner', :'employee', NULL);
INSERT INTO team_pairing_test_state VALUES ('relay_expiring_state', public.relay_enqueue(
    :'owner', :'employee', :'relay_expiring', pg_catalog.decode(repeat('22', 32), 'hex'), 60));
RESET ROLE;
UPDATE relay_private.envelopes
   SET created_at = pg_catalog.clock_timestamp() - interval '2 minutes',
       expires_at = pg_catalog.clock_timestamp() - interval '1 minute'
 WHERE client_message_id = :'relay_expiring';
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.assert_true(public.relay_cleanup_expired(100) = 1,
    'expired relay envelope cleanup deletes expired rows');
RESET ROLE;
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM relay_private.envelopes
    WHERE client_message_id = :'relay_expiring'), 'expired envelope is gone');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
INSERT INTO team_pairing_test_state VALUES ('relay_cascade_state', public.relay_enqueue(
    :'owner', :'employee', :'relay_cascade', pg_catalog.decode(repeat('33', 32), 'hex'), 3600));
SELECT set_config('request.jwt.claim.sub', :'account_b', true);

-- Pairing does NOT grant data access in either direction, or sync writes.
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.devices WHERE id = :'owner')
    AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = :'account_a')
    AND NOT EXISTS (SELECT 1 FROM public.payment_events WHERE device_id = :'owner')
    AND NOT EXISTS (SELECT 1 FROM public.cashbook_entries WHERE device_id = :'owner'), 'employee cannot read owner private data');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.devices WHERE id = :'employee')
    AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = :'account_b')
    AND NOT EXISTS (SELECT 1 FROM public.payment_events WHERE device_id = :'employee')
    AND NOT EXISTS (SELECT 1 FROM public.cashbook_entries WHERE device_id = :'employee'), 'owner cannot read employee private data');
SELECT pg_temp.assert_true((SELECT count(*) = 1 FROM public.payment_events WHERE device_id = :'owner'),
    'own payment data remains readable');
SELECT pg_temp.expect_error(format('INSERT INTO public.payment_events (device_id,kind,amount,dedup_key) VALUES (%L,''credit'',1,''pairing-attack'')',
    :'employee'), '42501');
WITH changed AS (UPDATE public.payment_events SET amount = 999 WHERE device_id = :'employee' RETURNING id)
SELECT pg_temp.assert_true((SELECT count(*) = 0 FROM changed), 'no cross-account payment updates');
WITH changed AS (DELETE FROM public.cashbook_entries WHERE device_id = :'employee' RETURNING id)
SELECT pg_temp.assert_true((SELECT count(*) = 0 FROM changed), 'no cross-account cashbook deletes');

-- Direct writes cannot forge device ownership, pairing, or invitation state.
SELECT pg_temp.expect_error(format('UPDATE public.devices SET paired_with_device_id=%L WHERE id=%L', :'outsider', :'owner'), '42501');
SELECT pg_temp.expect_error(format('UPDATE public.devices SET user_id=%L WHERE id=%L', :'account_b', :'owner'), '42501');
SELECT pg_temp.expect_error(format('UPDATE public.devices SET device_name=''Spoof'' WHERE id=%L', :'owner'), '42501');
SELECT pg_temp.expect_error(format('INSERT INTO public.devices(id,user_id,device_name) VALUES (%L,%L,''Spoof'')',
    '20000000-0000-0000-0000-000000000099', :'account_a'), '42501');
SELECT pg_temp.expect_error(format('DELETE FROM public.devices WHERE id=%L', :'owner'), '42501');
SELECT pg_temp.expect_error('TRUNCATE public.devices CASCADE', '42501');
SELECT pg_temp.expect_error('SELECT * FROM public.owner_employees', '42501');
SELECT pg_temp.expect_error(format('INSERT INTO public.owner_employees(owner_device_id,employee_device_id) VALUES (%L,%L)', :'other_a', :'owner'), '42501');
SELECT pg_temp.expect_error('UPDATE public.owner_employees SET owner_device_id=employee_device_id', '42501');
SELECT pg_temp.expect_error('DELETE FROM public.owner_employees', '42501');
SELECT pg_temp.expect_error('SELECT * FROM team_private.invites', '42501');
SELECT pg_temp.expect_error('DELETE FROM team_private.invites', '42501');
SELECT pg_temp.expect_error('SELECT * FROM team_private.join_attempts', '42501');
SELECT pg_temp.expect_error('UPDATE team_private.join_attempts SET attempts=1', '42501');
SELECT pg_temp.expect_error('SELECT team_private.new_invite_code()', '42501');
SELECT pg_temp.expect_error('SELECT * FROM relay_private.device_keys', '42501');
SELECT pg_temp.expect_error('SELECT * FROM relay_private.peer_key_bindings', '42501');
SELECT pg_temp.expect_error('SELECT * FROM relay_private.envelopes', '42501');
SELECT pg_temp.expect_error(format('INSERT INTO relay_private.envelopes(
        pairing_id,owner_device_id,employee_device_id,client_message_id,employee_key_id,
        encrypted_envelope,created_at,expires_at) VALUES (%L,%L,%L,%L,%L,%L,now(),now()+interval ''1 hour'')',
    (SELECT (value ->> 'pairing_id')::uuid FROM team_pairing_test_state WHERE key = 'relay_binding'),
    :'owner', :'employee', :'relay_message', :'employee_key_rotated', '\\x44'::bytea), '42501');

-- Defense in depth: restrictive RLS still denies after accidental blanket grants
-- AND permissive policies. Restore the migration's ACLs immediately afterwards.
RESET ROLE;
GRANT USAGE ON SCHEMA team_private TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.owner_employees, team_private.invites,
    team_private.join_attempts TO authenticated;
GRANT USAGE ON SCHEMA relay_private TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON relay_private.device_keys,
    relay_private.peer_key_bindings, relay_private.envelopes TO authenticated;
CREATE POLICY team_test_allow ON public.owner_employees FOR ALL TO authenticated USING (true) WITH CHECK (true);
CREATE POLICY team_test_allow ON team_private.invites FOR ALL TO authenticated USING (true) WITH CHECK (true);
CREATE POLICY team_test_allow ON team_private.join_attempts FOR ALL TO authenticated USING (true) WITH CHECK (true);
CREATE POLICY relay_test_allow ON relay_private.device_keys FOR ALL TO authenticated USING (true) WITH CHECK (true);
CREATE POLICY relay_test_allow ON relay_private.peer_key_bindings FOR ALL TO authenticated USING (true) WITH CHECK (true);
CREATE POLICY relay_test_allow ON relay_private.envelopes FOR ALL TO authenticated USING (true) WITH CHECK (true);
SET LOCAL ROLE authenticated;
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.owner_employees)
    AND NOT EXISTS (SELECT 1 FROM team_private.invites)
    AND NOT EXISTS (SELECT 1 FROM team_private.join_attempts)
    AND NOT EXISTS (SELECT 1 FROM relay_private.device_keys)
    AND NOT EXISTS (SELECT 1 FROM relay_private.peer_key_bindings)
    AND NOT EXISTS (SELECT 1 FROM relay_private.envelopes),
    'RLS remains closed after accidental grants/policies');
SELECT pg_temp.expect_error(format('INSERT INTO public.owner_employees(owner_device_id,employee_device_id) VALUES (%L,%L)', :'other_a', :'owner'), '42501');
RESET ROLE;
DROP POLICY team_test_allow ON public.owner_employees;
DROP POLICY team_test_allow ON team_private.invites;
DROP POLICY team_test_allow ON team_private.join_attempts;
DROP POLICY relay_test_allow ON relay_private.device_keys;
DROP POLICY relay_test_allow ON relay_private.peer_key_bindings;
DROP POLICY relay_test_allow ON relay_private.envelopes;
REVOKE ALL ON public.owner_employees, team_private.invites, team_private.join_attempts FROM authenticated;
REVOKE ALL ON SCHEMA team_private FROM authenticated;
REVOKE ALL ON relay_private.device_keys, relay_private.peer_key_bindings, relay_private.envelopes FROM authenticated;
REVOKE ALL ON SCHEMA relay_private FROM authenticated;

-- Relationship authorization is endpoint-specific, not just account-specific.
SET LOCAL ROLE authenticated;
SELECT pg_temp.expect_error(format('SELECT public.team_remove_connection(%L,%L)', :'other_a',
    (SELECT value #>> '{owner,pairing_id}' FROM team_pairing_test_state WHERE key='employee_state')), '42501');
SELECT set_config('request.jwt.claim.sub', :'account_c', true);
SELECT pg_temp.expect_error(format('SELECT public.team_remove_connection(%L,%L)', :'outsider',
    (SELECT value #>> '{owner,pairing_id}' FROM team_pairing_test_state WHERE key='employee_state')), '42501');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.expect_error(format('SELECT public.team_remove_connection(%L,%L)', :'owner',
    (SELECT value #>> '{owner,pairing_id}' FROM team_pairing_test_state WHERE key='employee_state')), '42501');
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT public.team_remove_connection(:'owner',
    (SELECT (value #>> '{owner,pairing_id}')::uuid FROM team_pairing_test_state WHERE key='employee_state'));
RESET ROLE;
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM relay_private.envelopes
    WHERE owner_device_id = :'owner' AND employee_device_id = :'employee')
    AND NOT EXISTS (SELECT 1 FROM relay_private.peer_key_bindings
        WHERE owner_device_id = :'owner' AND employee_device_id = :'employee')
    AND EXISTS (SELECT 1 FROM relay_private.device_keys WHERE device_id = :'employee'),
    'pairing removal cascades relay envelopes and peer binding but keeps device keys');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.assert_true(jsonb_array_length(public.team_register_device(:'owner', 'Owner tablet') -> 'employees') = 2,
    'owner revoke preserves other connections');
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
SELECT pg_temp.assert_true(public.team_register_device(:'employee', 'Employee terminal') -> 'owner' = 'null'::jsonb,
    'owner revoke reflected on employee readback');
SELECT public.team_remove_connection(:'employee2',
    (SELECT (value #>> '{owner,pairing_id}')::uuid FROM team_pairing_test_state WHERE key='employee2_state'));
SELECT public.team_create_invite(:'employee2');
SELECT public.team_cancel_invite(:'employee2');
SELECT public.team_remove_connection(:'spare',
    (SELECT (value #>> '{owner,pairing_id}')::uuid FROM team_pairing_test_state WHERE key='spare_state'));
RESET ROLE;
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.devices
    WHERE id IN (:'employee', :'employee2', :'spare') AND paired_with_device_id IS NOT NULL),
    'owner revoke and employee leave clear employee pointers');
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.owner_employees WHERE owner_device_id=:'owner'),
    'last employee can leave');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_a', true);
SELECT pg_temp.assert_true(public.team_join(:'owner',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key='outside_invite')) IS NULL,
    'former owner can become employee after last connection leaves');
SELECT pg_temp.expect_error(format('SELECT public.team_create_invite(%L)', :'owner'), '22023');

-- Shared per-user budget: ten failures persist, valid 11th code is not consumed,
-- and the budget resets after ten minutes without waiting in real time.
RESET ROLE;
UPDATE team_private.join_attempts SET window_started_at = clock_timestamp() - interval '11 minutes'
    WHERE user_id = :'account_b';
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', :'account_c', true);
INSERT INTO team_pairing_test_state VALUES ('rate_invite', public.team_create_invite(:'outsider'));
SELECT set_config('request.jwt.claim.sub', :'account_b', true);
DO $rate$
DECLARE
    v_result jsonb;
BEGIN
    FOR n IN 1..10 LOOP
        v_result := public.team_join(CASE WHEN n % 2 = 0 THEN
            '20000000-0000-0000-0000-000000000002'::uuid ELSE
            '20000000-0000-0000-0000-000000000004'::uuid END,
            CASE WHEN n = 1 THEN NULL WHEN n = 2 THEN repeat('x', 1000) ELSE 'not-a-code' END);
        PERFORM pg_temp.assert_true(v_result = '{"error":"invalid_invite"}'::jsonb,
            'failed join returns error without rolling back budget');
    END LOOP;
END
$rate$;
INSERT INTO team_pairing_test_state VALUES ('rate_error', public.team_join(:'employee',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key='rate_invite')));
SELECT pg_temp.assert_true((SELECT value ->> 'error' = 'rate_limited'
    AND (value ->> 'retry_after_seconds')::integer BETWEEN 1 AND 600
    FROM team_pairing_test_state WHERE key='rate_error'), 'eleventh attempt throttled across devices');
RESET ROLE;
SELECT pg_temp.assert_true((SELECT attempts = 11 FROM team_private.join_attempts WHERE user_id=:'account_b'),
    'failed attempt increments persisted');
SELECT pg_temp.assert_true(EXISTS (SELECT 1 FROM team_private.invites WHERE owner_device_id=:'outsider'),
    'rate-limited join does not consume a valid invite');
UPDATE team_private.join_attempts SET window_started_at = clock_timestamp() - interval '11 minutes'
    WHERE user_id=:'account_b';
SET LOCAL ROLE authenticated;
SELECT pg_temp.assert_true(public.team_join(:'employee',
    (SELECT value ->> 'code' FROM team_pairing_test_state WHERE key='rate_invite')) IS NULL,
    'join allowed when rate window expires');
RESET ROLE;
SELECT pg_temp.assert_true((SELECT attempts = 1 FROM team_private.join_attempts WHERE user_id=:'account_b'),
    'expired rate window starts fresh');
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.owner_employees a
    JOIN public.owner_employees b ON a.employee_device_id = b.owner_device_id), 'no chains or cycles');
SELECT pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM public.devices d
    LEFT JOIN public.owner_employees p ON p.employee_device_id=d.id
    WHERE d.id IN (:'owner', :'employee', :'other_a', :'employee2', :'outsider', :'spare')
    AND d.paired_with_device_id IS DISTINCT FROM p.owner_device_id), 'all fixture legacy pointers match authoritative membership');
SELECT pg_temp.assert_true((SELECT amount = 22 FROM public.payment_events WHERE dedup_key='pairing-regression-employee'),
    'payment rows untouched throughout pairing');

ROLLBACK;
\echo 'PASS: team pairing + encrypted relay regression suite (all fixture changes rolled back)'
