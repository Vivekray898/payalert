-- PayAlert device pairing. Apply AFTER supabase/schema.sql, as the schema owner
-- (Supabase SQL editor / postgres migration role). Safe to reapply; no existing
-- users, devices, payment rows, or legacy pairing pointers are deleted/imported.
-- Reapply this migration if schema.sql is subsequently reapplied.
--
-- Public RPC contract (authenticated only; all use auth.uid()):
--   team_register_device(uuid, text) -> jsonb
--     {device:{id,name}, employees:[{pairing_id,device_id,device_name}],
--      owner:null | {pairing_id,device_id,device_name}}
--     Name is trimmed, 1..120 characters. Auth email (or '' for phone-only auth)
--     and display_name/full_name/name metadata populate public.users. Registers
--     or refreshes ONLY the caller's device; updates last_seen_at and reconciles
--     its legacy paired_with_device_id from the authoritative membership table.
--   team_create_invite(uuid) -> {code:"6 decimal digits",expires_at:timestamptz}
--     Rotates the owner's previous code. One outstanding code per owner DEVICE,
--     valid for 10 minutes, single use, 32 bits of CSPRNG entropy mod 10^6,
--     SHA-256 stored only.
--   team_cancel_invite(uuid) -> void (idempotent)
--   team_join(uuid, text) -> jsonb: SQL NULL / JSON null on success (like void).
--     IMPORTANT: inspect a non-null result for {error:...}; returning errors
--     rather than raising preserves failed-attempt counters in the RPC commit.
--     Errors: invalid_invite (also expired/consumed/cancelled), self_pairing,
--     already_paired, device_is_owner (existing employees OR active own invite),
--     owner_is_employee, device_not_owned (only a concurrent privileged change),
--     rate_limited (also includes retry_after_seconds).
--     Codes accept surrounding spaces and either case. At most 10 attempts
--     per authenticated USER per fixed 10-minute window, across all devices;
--     successes, malformed codes and role failures count. Don't wrap a failed
--     join in a transaction you roll back. A privileged SQL caller can always
--     roll back its transaction; ordinary PostgREST RPC calls commit these errors.
--   team_remove_connection(uuid, uuid) -> void
--     Either endpoint can remove its connection; an unrelated device cannot,
--     even when the same account owns one of the other endpoints.
--
-- All RPCs raise SQLSTATE 42501 for missing auth / unowned caller device.
-- register also raises 22023 for invalid id/name; create raises 22023 with
-- device_is_employee; remove raises 42501 with connection_not_found_or_not_owned.
-- Missing auth.users record during registration: 42501 authenticated_user_not_found.
-- No RPC returns user IDs, emails, FCM tokens, or payment data. Membership never
-- participates in users/events/cashbook RLS, sync, or payment authorization.
-- Device IDs are immutable client-generated UUIDs, never proof of ownership.
-- Trusted service/database roles remain privileged; client roles have SELECT
-- on their own devices only and no direct membership/invite/counter access.
-- As with other Postgres writes, a 40001 serialization failure / 40P01 deadlock
-- is a transaction-level retry (not a returned join validation error).

BEGIN;

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE SCHEMA IF NOT EXISTS team_private;
REVOKE ALL ON SCHEMA team_private FROM PUBLIC, anon, authenticated;

CREATE TABLE IF NOT EXISTS public.owner_employees (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    owner_device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    employee_device_id uuid NOT NULL UNIQUE REFERENCES public.devices(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT owner_employees_not_self CHECK (owner_device_id <> employee_device_id)
);
CREATE INDEX IF NOT EXISTS owner_employees_owner_idx
    ON public.owner_employees (owner_device_id, id);
CREATE INDEX IF NOT EXISTS team_devices_user_idx ON public.devices (user_id);

CREATE TABLE IF NOT EXISTS team_private.invites (
    owner_device_id uuid PRIMARY KEY REFERENCES public.devices(id) ON DELETE CASCADE,
    code_hash bytea NOT NULL UNIQUE CHECK (pg_catalog.octet_length(code_hash) = 32),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at)
);
CREATE TABLE IF NOT EXISTS team_private.join_attempts (
    user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 11)
);

ALTER TABLE public.owner_employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_private.invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_private.join_attempts ENABLE ROW LEVEL SECURITY;
-- Deny even if a later blanket table grant is applied. Definers run as the
-- trusted table owner and intentionally bypass RLS on these private tables.
DROP POLICY IF EXISTS team_no_client_access ON public.owner_employees;
CREATE POLICY team_no_client_access ON public.owner_employees AS RESTRICTIVE
    FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
DROP POLICY IF EXISTS team_no_client_access ON team_private.invites;
CREATE POLICY team_no_client_access ON team_private.invites AS RESTRICTIVE
    FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
DROP POLICY IF EXISTS team_no_client_access ON team_private.join_attempts;
CREATE POLICY team_no_client_access ON team_private.join_attempts AS RESTRICTIVE
    FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
REVOKE ALL ON public.owner_employees, team_private.invites,
    team_private.join_attempts FROM PUBLIC, anon, authenticated;

-- Replace the original ALL-command device policies. Table AND column grants
-- are revoked: revoking table UPDATE alone does not revoke column UPDATE.
DROP POLICY IF EXISTS "Devices readable by owner" ON public.devices;
DROP POLICY IF EXISTS "Devices insertable by owner" ON public.devices;
DROP POLICY IF EXISTS team_devices_select ON public.devices;
CREATE POLICY team_devices_select ON public.devices FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()));
-- Also constrain any existing permissive custom policies, without dropping them.
DROP POLICY IF EXISTS team_devices_owner_boundary ON public.devices;
CREATE POLICY team_devices_owner_boundary ON public.devices AS RESTRICTIVE
    FOR ALL TO authenticated USING (user_id = (SELECT auth.uid()))
    WITH CHECK (user_id = (SELECT auth.uid()));
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.devices FROM PUBLIC, anon, authenticated;
DO $privileges$
DECLARE
    v_columns text;
BEGIN
    SELECT pg_catalog.string_agg(pg_catalog.quote_ident(a.attname), ', ' ORDER BY a.attnum)
      INTO v_columns
      FROM pg_catalog.pg_attribute AS a
     WHERE a.attrelid = 'public.devices'::pg_catalog.regclass
       AND a.attnum > 0 AND NOT a.attisdropped;
    EXECUTE pg_catalog.format(
        'REVOKE SELECT (%1$s), INSERT (%1$s), UPDATE (%1$s), REFERENCES (%1$s) '
        'ON public.devices FROM PUBLIC, anon, authenticated', v_columns);
END
$privileges$;
GRANT SELECT ON public.devices TO authenticated;

-- Bind the helper to pgcrypto's ACTUAL schema: a stock Postgres installation
-- might already have pgcrypto in public. Do not relocate an existing extension.
-- SHA-256 below is the built-in Postgres 13+ bytea function; no search_path or
-- extension-schema assumptions are needed for hashing.
DO $crypto$
DECLARE
    v_schema text;
BEGIN
    SELECT n.nspname INTO STRICT v_schema
      FROM pg_catalog.pg_extension AS e
      JOIN pg_catalog.pg_namespace AS n ON n.oid = e.extnamespace
     WHERE e.extname = 'pgcrypto';
    EXECUTE pg_catalog.format(
        'CREATE OR REPLACE FUNCTION team_private.new_invite_code() RETURNS text '
        'LANGUAGE plpgsql VOLATILE SET search_path = '''' AS %L',
        pg_catalog.format(
            $body$
DECLARE
    v_bytes bytea;
    v_int bigint;
BEGIN
    v_bytes := %I.gen_random_bytes(4);
    v_int := ('x' || pg_catalog.encode(v_bytes, 'hex'))::bit(32)::bigint;
    RETURN pg_catalog.lpad((v_int %% 1000000)::text, 6, '0');
END
$body$,
            v_schema));
END
$crypto$;
REVOKE ALL ON FUNCTION team_private.new_invite_code() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.team_register_device(p_device_id uuid, p_device_name text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_name text := pg_catalog.btrim(p_device_name);
    v_id uuid;
    v_email text;
    v_display_name text;
    v_result jsonb;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_device_id IS NULL OR v_name IS NULL OR pg_catalog.char_length(v_name) NOT BETWEEN 1 AND 120 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_device_id_or_name';
    END IF;
    SELECT COALESCE(u.email, ''), COALESCE(
               NULLIF(pg_catalog.btrim(u.raw_user_meta_data ->> 'display_name'), ''),
               NULLIF(pg_catalog.btrim(u.raw_user_meta_data ->> 'full_name'), ''),
               NULLIF(pg_catalog.btrim(u.raw_user_meta_data ->> 'name'), ''))
      INTO v_email, v_display_name FROM auth.users AS u WHERE u.id = v_uid;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authenticated_user_not_found';
    END IF;
    INSERT INTO public.users AS u (id, email, display_name)
    VALUES (v_uid, v_email, v_display_name)
    ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email,
        display_name = COALESCE(EXCLUDED.display_name, u.display_name),
        updated_at = pg_catalog.clock_timestamp();

    -- The ownership predicate is on the conflict UPDATE, not just a preceding
    -- SELECT: concurrent first registration cannot overwrite a stolen UUID.
    INSERT INTO public.devices AS d (id, user_id, device_name, last_seen_at)
    VALUES (p_device_id, v_uid, v_name, pg_catalog.clock_timestamp())
    ON CONFLICT (id) DO UPDATE SET device_name = EXCLUDED.device_name,
        last_seen_at = EXCLUDED.last_seen_at
        WHERE d.user_id = v_uid
    RETURNING id INTO v_id;
    IF v_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;
    -- Registration holds the caller's device row lock. Every role mutation also
    -- locks that row, so readback and legacy pointer reconciliation are stable.
    UPDATE public.devices AS d SET paired_with_device_id = (
        SELECT p.owner_device_id FROM public.owner_employees AS p
         WHERE p.employee_device_id = p_device_id)
     WHERE d.id = p_device_id;
    SELECT pg_catalog.jsonb_build_object(
        'device', pg_catalog.jsonb_build_object('id', d.id, 'name', d.device_name),
        'employees', COALESCE((
            SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                'pairing_id', p.id, 'device_id', e.id, 'device_name', e.device_name) ORDER BY p.id)
              FROM public.owner_employees AS p
              JOIN public.devices AS e ON e.id = p.employee_device_id
             WHERE p.owner_device_id = d.id), '[]'::jsonb),
        'owner', (
            SELECT pg_catalog.jsonb_build_object(
                'pairing_id', p.id, 'device_id', o.id, 'device_name', o.device_name)
              FROM public.owner_employees AS p
              JOIN public.devices AS o ON o.id = p.owner_device_id
             WHERE p.employee_device_id = d.id))
      INTO v_result FROM public.devices AS d WHERE d.id = p_device_id;
    RETURN v_result;
END
$function$;

CREATE OR REPLACE FUNCTION public.team_create_invite(p_device_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_code text;
    v_now timestamptz;
    v_expires timestamptz;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    -- An actual tuple update also invalidates stale REPEATABLE READ snapshots;
    -- a row lock alone would not make a concurrent new invite visible there.
    UPDATE public.devices AS d SET last_seen_at = pg_catalog.clock_timestamp()
     WHERE d.id = p_device_id AND d.user_id = v_uid;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;
    IF EXISTS (SELECT 1 FROM public.owner_employees WHERE employee_device_id = p_device_id) THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'device_is_employee';
    END IF;
    v_now := pg_catalog.clock_timestamp();
    v_expires := v_now + interval '10 minutes';
    v_code := team_private.new_invite_code();
    INSERT INTO team_private.invites (owner_device_id, code_hash, created_at, expires_at)
    VALUES (p_device_id, pg_catalog.sha256(pg_catalog.convert_to(v_code, 'UTF8')), v_now, v_expires)
    ON CONFLICT (owner_device_id) DO UPDATE SET code_hash = EXCLUDED.code_hash,
        created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at;
    RETURN pg_catalog.jsonb_build_object('code', v_code, 'expires_at', v_expires);
END
$function$;

CREATE OR REPLACE FUNCTION public.team_cancel_invite(p_device_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    UPDATE public.devices AS d SET last_seen_at = pg_catalog.clock_timestamp()
     WHERE d.id = p_device_id AND d.user_id = v_uid;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;
    DELETE FROM team_private.invites WHERE owner_device_id = p_device_id;
END
$function$;

CREATE OR REPLACE FUNCTION public.team_join(p_device_id uuid, p_code text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_now timestamptz;
    v_window timestamptz;
    v_attempts integer;
    v_code text;
    v_hash bytea;
    v_owner uuid;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE id = p_device_id AND user_id = v_uid) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;

    -- Lock the per-user budget FIRST, then device rows in UUID order, then the
    -- invite. All subsequent expected failures RETURN, never RAISE/rollback.
    v_now := pg_catalog.clock_timestamp();
    INSERT INTO team_private.join_attempts AS a (user_id, window_started_at, attempts)
    VALUES (v_uid, v_now, 1)
    ON CONFLICT (user_id) DO UPDATE SET
        window_started_at = CASE WHEN a.window_started_at <= v_now - interval '10 minutes'
            THEN v_now ELSE a.window_started_at END,
        attempts = CASE WHEN a.window_started_at <= v_now - interval '10 minutes'
            THEN 1 ELSE LEAST(a.attempts + 1, 11) END
    RETURNING attempts, window_started_at INTO v_attempts, v_window;
    IF v_attempts > 10 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'rate_limited', 'retry_after_seconds',
            GREATEST(1, pg_catalog.ceil(EXTRACT(epoch FROM
                (v_window + interval '10 minutes' - pg_catalog.clock_timestamp())))::integer));
    END IF;
    -- Bound input before normalization or hashing. Malformed input costs budget.
    IF p_code IS NULL OR pg_catalog.octet_length(p_code) > 64 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    v_code := pg_catalog.btrim(p_code);
    IF v_code !~ '^[0-9]{6}$' THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    v_hash := pg_catalog.sha256(pg_catalog.convert_to(v_code, 'UTF8'));
    SELECT i.owner_device_id INTO v_owner FROM team_private.invites AS i
     WHERE i.code_hash = v_hash AND i.expires_at > pg_catalog.clock_timestamp();
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    IF v_owner = p_device_id THEN
        RETURN pg_catalog.jsonb_build_object('error', 'self_pairing');
    END IF;

    -- This common lock order serializes joins, role changes, invite rotation,
    -- removal, and registration for either endpoint without a global lock.
    PERFORM d.id FROM public.devices AS d WHERE d.id IN (v_owner, p_device_id)
     ORDER BY d.id FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE id = p_device_id AND user_id = v_uid) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'device_not_owned');
    END IF;
    -- Recheck after acquiring endpoint locks. A concurrent consume/cancel/rotate
    -- cannot reuse the pre-lock lookup. Wall clock is used even in long txns.
    PERFORM i.owner_device_id FROM team_private.invites AS i
     WHERE i.owner_device_id = v_owner AND i.code_hash = v_hash
       AND i.expires_at > pg_catalog.clock_timestamp() FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    IF EXISTS (SELECT 1 FROM public.owner_employees WHERE employee_device_id = v_owner) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'owner_is_employee');
    END IF;
    IF EXISTS (SELECT 1 FROM public.owner_employees WHERE employee_device_id = p_device_id) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'already_paired');
    END IF;
    IF EXISTS (SELECT 1 FROM public.owner_employees WHERE owner_device_id = p_device_id)
       OR EXISTS (SELECT 1 FROM team_private.invites WHERE owner_device_id = p_device_id
                   AND expires_at > pg_catalog.clock_timestamp()) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'device_is_owner');
    END IF;
    INSERT INTO public.owner_employees (owner_device_id, employee_device_id)
    VALUES (v_owner, p_device_id);
    UPDATE public.devices SET paired_with_device_id = v_owner WHERE id = p_device_id;
    DELETE FROM team_private.invites WHERE owner_device_id = v_owner AND code_hash = v_hash;
    RETURN NULL;
END
$function$;

CREATE OR REPLACE FUNCTION public.team_remove_connection(p_device_id uuid, p_pairing_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_owner uuid;
    v_employee uuid;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE id = p_device_id AND user_id = v_uid) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;
    SELECT p.owner_device_id, p.employee_device_id INTO v_owner, v_employee
      FROM public.owner_employees AS p WHERE p.id = p_pairing_id
       AND p_device_id IN (p.owner_device_id, p.employee_device_id);
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'connection_not_found_or_not_owned';
    END IF;
    PERFORM d.id FROM public.devices AS d WHERE d.id IN (v_owner, v_employee)
     ORDER BY d.id FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE id = p_device_id AND user_id = v_uid) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;
    DELETE FROM public.owner_employees
     WHERE id = p_pairing_id AND owner_device_id = v_owner AND employee_device_id = v_employee;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'connection_not_found_or_not_owned';
    END IF;
    UPDATE public.devices SET paired_with_device_id = NULL WHERE id = v_employee;
END
$function$;

REVOKE ALL ON FUNCTION public.team_register_device(uuid, text),
    public.team_create_invite(uuid), public.team_cancel_invite(uuid),
    public.team_join(uuid, text), public.team_remove_connection(uuid, uuid)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.team_register_device(uuid, text),
    public.team_create_invite(uuid), public.team_cancel_invite(uuid),
    public.team_join(uuid, text), public.team_remove_connection(uuid, uuid)
    TO authenticated;

COMMIT;
