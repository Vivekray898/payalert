-- PayAlert pairing-code format update.
-- Existing migrations 001-006 are already applied on the linked project, so
-- this forward migration updates the live helper and join RPC without rewriting
-- migration history. Codes remain single-use and are stored as SHA-256 hashes.

BEGIN;

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

REVOKE ALL ON FUNCTION team_private.new_invite_code()
    FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.team_join(p_device_id uuid, p_code text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
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
    -- invite. Expected validation failures return so the attempt counter commits.
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

    IF p_code IS NULL OR pg_catalog.octet_length(p_code) > 64 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    v_code := pg_catalog.btrim(p_code);
    IF v_code !~ '^[0-9]{6}$' THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    v_hash := pg_catalog.sha256(pg_catalog.convert_to(v_code, 'UTF8'));
    SELECT i.owner_device_id INTO v_owner
      FROM team_private.invites AS i
     WHERE i.code_hash = v_hash
       AND i.expires_at > pg_catalog.clock_timestamp();
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_invite');
    END IF;
    IF v_owner = p_device_id THEN
        RETURN pg_catalog.jsonb_build_object('error', 'self_pairing');
    END IF;

    -- This common lock order serializes joins, role changes, invite rotation,
    -- removal, and registration for either endpoint without a global lock.
    PERFORM d.id
      FROM public.devices AS d
     WHERE d.id IN (v_owner, p_device_id)
     ORDER BY d.id
       FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE id = p_device_id AND user_id = v_uid) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'device_not_owned');
    END IF;

    -- Recheck after acquiring endpoint locks so a concurrent consume/cancel/
    -- rotate cannot reuse the pre-lock lookup.
    PERFORM i.owner_device_id
      FROM team_private.invites AS i
     WHERE i.owner_device_id = v_owner
       AND i.code_hash = v_hash
       AND i.expires_at > pg_catalog.clock_timestamp()
       FOR UPDATE;
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
       OR EXISTS (SELECT 1 FROM team_private.invites
                   WHERE owner_device_id = p_device_id
                     AND expires_at > pg_catalog.clock_timestamp()) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'device_is_owner');
    END IF;

    INSERT INTO public.owner_employees (owner_device_id, employee_device_id)
    VALUES (v_owner, p_device_id);
    UPDATE public.devices
       SET paired_with_device_id = v_owner
     WHERE id = p_device_id;
    DELETE FROM team_private.invites
     WHERE owner_device_id = v_owner
       AND code_hash = v_hash;
    RETURN NULL;
END
$function$;

COMMIT;
