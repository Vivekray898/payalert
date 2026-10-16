-- PayAlert encrypted owner -> employee relay. Apply AFTER supabase/team_pairing.sql.
--
-- The relay is deliberately blind: clients encrypt an envelope before calling
-- relay_enqueue(), and this migration never stores or accepts plaintext event
-- fields. The database stores routing/lease metadata plus opaque bytea only.
-- Public RPCs use auth.uid(), fixed empty search paths, and the pairing table as
-- the authorization source. This file is safe to reapply.
--
-- Public RPC contract (authenticated callers; all key/envelope bytes are opaque
-- to the database):
--   relay_register_key(uuid device_id, uuid key_id, bytea public_key) -> jsonb
--     Registers the caller's current public key. A new key rotates the device
--     key and invalidates peer bindings. Repeating the same key is idempotent.
--   relay_rotate_key(uuid device_id, uuid key_id, bytea public_key) -> jsonb
--     Explicit rotation alias for relay_register_key(). Private key material is
--     never accepted or stored.
--   relay_bind_peer_key(uuid owner_device_id, uuid employee_device_id,
--                       uuid employee_key_id) -> jsonb
--     Owner-only. Binds the employee's current registered public key to the
--     existing owner_employees relationship and returns that public key as hex
--     for client-side encryption. Pass NULL for employee_key_id to bind
--     whichever key is current. Rotation invalidates this binding.
--   relay_peer_key(uuid employee_device_id) -> jsonb
--     Employee-only. Returns the peer binding for the caller's own employee
--     device: the bound owner key id plus the owner's registered public key as
--     hex. The employee needs the owner's public key to derive the X25519
--     shared secret; it is public metadata and never contains private key
--     material. Fails while no current binding exists.
--   relay_enqueue(uuid owner_device_id, uuid employee_device_id,
--                 uuid client_message_id, bytea encrypted_envelope,
--                 integer ttl_seconds = 86400) -> jsonb
--     Owner-only owner -> employee enqueue. The pair must have current keys
--     and a peer binding. (pairing_id, client_message_id) is idempotent; a
--     duplicate never replaces the first envelope.
--   relay_fetch(uuid employee_device_id, integer limit = 50,
--               integer lease_seconds = 60) -> SETOF relay lease rows
--     Employee-only. Claims unexpired, unleased/expired-lease rows with
--     FOR UPDATE SKIP LOCKED and returns a per-row opaque lease token.
--   relay_ack(uuid employee_device_id, uuid message_id, uuid lease_token) -> boolean
--     Employee-only. Deletes only the row held by the matching live lease.
--   relay_cleanup_expired(integer limit = 1000) -> integer
--     Deletes expired envelopes. It returns only a count and may be called by
--     an authenticated maintenance client; service/database roles remain able
--     to run it as trusted roles.
--
-- Pairing removal cascades through pairing_id foreign keys, deleting pending
-- envelopes and peer-key bindings. Device key rows are retained because a
-- device may be paired again later. No relay table is directly client-readable
-- or client-writable.

BEGIN;

CREATE SCHEMA IF NOT EXISTS relay_private;
REVOKE ALL ON SCHEMA relay_private FROM PUBLIC, anon, authenticated;

-- PostgreSQL foreign keys that carry the immutable endpoint metadata need a
-- unique referenced key. The pairing id is already the primary key; this
-- covering unique index adds no new client-visible state and is reapply-safe.
CREATE UNIQUE INDEX IF NOT EXISTS owner_employees_endpoint_identity_idx
    ON public.owner_employees (id, owner_device_id, employee_device_id);

CREATE TABLE IF NOT EXISTS relay_private.device_keys (
    device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    key_id uuid NOT NULL,
    public_key bytea NOT NULL,
    is_current boolean NOT NULL DEFAULT TRUE,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    retired_at timestamptz,
    PRIMARY KEY (device_id, key_id),
    CONSTRAINT relay_device_keys_material_size
        CHECK (pg_catalog.octet_length(public_key) BETWEEN 1 AND 4096),
    CONSTRAINT relay_device_keys_current_state
        CHECK ((is_current AND retired_at IS NULL)
            OR ((NOT is_current) AND retired_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS relay_device_keys_current_idx
    ON relay_private.device_keys (device_id) WHERE is_current;

CREATE TABLE IF NOT EXISTS relay_private.peer_key_bindings (
    pairing_id uuid PRIMARY KEY REFERENCES public.owner_employees(id) ON DELETE CASCADE,
    owner_device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    employee_device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    owner_key_id uuid NOT NULL,
    employee_key_id uuid NOT NULL,
    bound_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT relay_peer_binding_endpoints_not_self
        CHECK (owner_device_id <> employee_device_id),
    CONSTRAINT relay_peer_binding_owner_key_fk
        FOREIGN KEY (owner_device_id, owner_key_id)
        REFERENCES relay_private.device_keys(device_id, key_id),
    CONSTRAINT relay_peer_binding_employee_key_fk
        FOREIGN KEY (employee_device_id, employee_key_id)
        REFERENCES relay_private.device_keys(device_id, key_id),
    CONSTRAINT relay_peer_binding_pairing_endpoints_fk
        FOREIGN KEY (pairing_id, owner_device_id, employee_device_id)
        REFERENCES public.owner_employees(id, owner_device_id, employee_device_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS relay_peer_key_bindings_employee_idx
    ON relay_private.peer_key_bindings (employee_device_id, pairing_id);

CREATE TABLE IF NOT EXISTS relay_private.envelopes (
    message_id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    pairing_id uuid NOT NULL REFERENCES public.owner_employees(id) ON DELETE CASCADE,
    owner_device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    employee_device_id uuid NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    client_message_id uuid NOT NULL,
    employee_key_id uuid NOT NULL,
    encrypted_envelope bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    expires_at timestamptz NOT NULL,
    lease_token uuid,
    leased_until timestamptz,
    delivery_attempts integer NOT NULL DEFAULT 0,
    CONSTRAINT relay_envelopes_material_size
        CHECK (pg_catalog.octet_length(encrypted_envelope) BETWEEN 1 AND 1048576),
    CONSTRAINT relay_envelopes_expiry_after_creation
        CHECK (expires_at > created_at),
    CONSTRAINT relay_envelopes_lease_pair
        CHECK ((lease_token IS NULL AND leased_until IS NULL)
            OR (lease_token IS NOT NULL AND leased_until IS NOT NULL)),
    CONSTRAINT relay_envelopes_attempts_valid
        CHECK (delivery_attempts BETWEEN 0 AND 2147483647),
    CONSTRAINT relay_envelopes_pairing_endpoints_fk
        FOREIGN KEY (pairing_id, owner_device_id, employee_device_id)
        REFERENCES public.owner_employees(id, owner_device_id, employee_device_id)
        ON DELETE CASCADE,
    CONSTRAINT relay_envelopes_key_fk
        FOREIGN KEY (employee_device_id, employee_key_id)
        REFERENCES relay_private.device_keys(device_id, key_id),
    CONSTRAINT relay_envelopes_idempotency_key
        UNIQUE (pairing_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS relay_envelopes_fetch_idx
    ON relay_private.envelopes (employee_device_id, expires_at, leased_until, created_at);
CREATE INDEX IF NOT EXISTS relay_envelopes_expiry_idx
    ON relay_private.envelopes (expires_at);

ALTER TABLE relay_private.device_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay_private.peer_key_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay_private.envelopes ENABLE ROW LEVEL SECURITY;

-- A restrictive deny policy remains effective even if a future migration adds
-- broad grants or a permissive policy by mistake. Definers run as the trusted
-- migration/table owner and intentionally bypass these policies.
DROP POLICY IF EXISTS relay_no_client_access ON relay_private.device_keys;
CREATE POLICY relay_no_client_access ON relay_private.device_keys AS RESTRICTIVE
    FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
DROP POLICY IF EXISTS relay_no_client_access ON relay_private.peer_key_bindings;
CREATE POLICY relay_no_client_access ON relay_private.peer_key_bindings AS RESTRICTIVE
    FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);
DROP POLICY IF EXISTS relay_no_client_access ON relay_private.envelopes;
CREATE POLICY relay_no_client_access ON relay_private.envelopes AS RESTRICTIVE
    FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);

REVOKE ALL ON relay_private.device_keys,
    relay_private.peer_key_bindings, relay_private.envelopes
    FROM PUBLIC, anon, authenticated;

-- Keep key state changes in one non-public helper. The public wrappers perform
-- auth/device ownership checks before calling it. Rotating either endpoint's
-- key invalidates every binding touching that device; pending envelopes remain
-- available to the employee's old private key until they expire or are acked.
CREATE OR REPLACE FUNCTION relay_private.set_device_key(
    p_device_id uuid,
    p_key_id uuid,
    p_public_key bytea
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_existing bytea;
    v_is_current boolean;
    v_rotated boolean := false;
    v_now timestamptz := pg_catalog.clock_timestamp();
BEGIN
    SELECT k.public_key, k.is_current
      INTO v_existing, v_is_current
      FROM relay_private.device_keys AS k
     WHERE k.device_id = p_device_id AND k.key_id = p_key_id
     FOR UPDATE;

    IF FOUND THEN
        IF v_existing IS DISTINCT FROM p_public_key THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'key_id_reuse';
        END IF;
        IF NOT v_is_current THEN
            UPDATE relay_private.device_keys AS k
               SET is_current = false, retired_at = COALESCE(k.retired_at, v_now)
             WHERE k.device_id = p_device_id AND k.is_current;
            UPDATE relay_private.device_keys AS k
               SET is_current = true, retired_at = NULL
             WHERE k.device_id = p_device_id AND k.key_id = p_key_id;
            v_rotated := true;
        END IF;
    ELSE
        UPDATE relay_private.device_keys AS k
           SET is_current = false, retired_at = COALESCE(k.retired_at, v_now)
         WHERE k.device_id = p_device_id AND k.is_current;
        INSERT INTO relay_private.device_keys (device_id, key_id, public_key)
        VALUES (p_device_id, p_key_id, p_public_key);
        v_rotated := true;
    END IF;

    IF v_rotated THEN
        DELETE FROM relay_private.peer_key_bindings AS b
         WHERE b.owner_device_id = p_device_id
            OR b.employee_device_id = p_device_id;
    END IF;

    RETURN pg_catalog.jsonb_build_object(
        'device_id', p_device_id,
        'key_id', p_key_id,
        'rotated', v_rotated
    );
END
$function$;

REVOKE ALL ON FUNCTION relay_private.set_device_key(uuid, uuid, bytea)
    FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.relay_register_key(
    p_device_id uuid,
    p_key_id uuid,
    p_public_key bytea
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_device_id IS NULL OR p_key_id IS NULL
       OR p_public_key IS NULL
       OR pg_catalog.octet_length(p_public_key) NOT BETWEEN 1 AND 4096 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_key';
    END IF;
    PERFORM d.id
      FROM public.devices AS d
     WHERE d.id = p_device_id AND d.user_id = v_uid
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;
    RETURN relay_private.set_device_key(p_device_id, p_key_id, p_public_key);
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_rotate_key(
    p_device_id uuid,
    p_key_id uuid,
    p_public_key bytea
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
BEGIN
    RETURN public.relay_register_key(p_device_id, p_key_id, p_public_key);
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_bind_peer_key(
    p_owner_device_id uuid,
    p_employee_device_id uuid,
    p_employee_key_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_pairing_id uuid;
    v_owner_key_id uuid;
    v_employee_key_id uuid;
    v_peer_public_key bytea;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_owner_device_id IS NULL OR p_employee_device_id IS NULL
       OR p_owner_device_id = p_employee_device_id THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_peer_binding';
    END IF;

    -- Match team_pairing's endpoint lock order so removal and key changes
    -- cannot leave a binding pointing at a deleted relationship.
    PERFORM d.id
      FROM public.devices AS d
     WHERE d.id IN (p_owner_device_id, p_employee_device_id)
     ORDER BY d.id
     FOR UPDATE;
    IF NOT EXISTS (
        SELECT 1 FROM public.devices AS d
         WHERE d.id = p_owner_device_id AND d.user_id = v_uid
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;

    SELECT p.id INTO v_pairing_id
      FROM public.owner_employees AS p
     WHERE p.owner_device_id = p_owner_device_id
       AND p.employee_device_id = p_employee_device_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'pairing_not_authorized';
    END IF;

    SELECT k.key_id INTO v_owner_key_id
      FROM relay_private.device_keys AS k
     WHERE k.device_id = p_owner_device_id AND k.is_current
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'owner_key_not_registered';
    END IF;
    SELECT k.key_id, k.public_key INTO v_employee_key_id, v_peer_public_key
      FROM relay_private.device_keys AS k
      WHERE k.device_id = p_employee_device_id
       AND (p_employee_key_id IS NULL OR k.key_id = p_employee_key_id)
       AND k.is_current
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'employee_key_not_current';
    END IF;

    INSERT INTO relay_private.peer_key_bindings AS b (
        pairing_id, owner_device_id, employee_device_id,
        owner_key_id, employee_key_id, bound_at
    ) VALUES (
        v_pairing_id, p_owner_device_id, p_employee_device_id,
        v_owner_key_id, v_employee_key_id, pg_catalog.clock_timestamp()
    )
    ON CONFLICT (pairing_id) DO UPDATE SET
        owner_key_id = EXCLUDED.owner_key_id,
        employee_key_id = EXCLUDED.employee_key_id,
        bound_at = EXCLUDED.bound_at;

    RETURN pg_catalog.jsonb_build_object(
        'pairing_id', v_pairing_id,
        'owner_device_id', p_owner_device_id,
        'employee_device_id', p_employee_device_id,
        'owner_key_id', v_owner_key_id,
        'employee_key_id', v_employee_key_id,
        'peer_public_key_hex', pg_catalog.encode(v_peer_public_key, 'hex')
    );
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_peer_key(
    p_employee_device_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_employee_device_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_peer_lookup';
    END IF;

    -- No key material is returned unless the caller owns the employee endpoint
    -- and a current, doubly-valid binding exists for it.
    RETURN (
        SELECT pg_catalog.jsonb_build_object(
            'pairing_id', b.pairing_id,
            'owner_device_id', b.owner_device_id,
            'employee_device_id', b.employee_device_id,
            'owner_key_id', b.owner_key_id,
            'employee_key_id', b.employee_key_id,
            'owner_public_key_hex', pg_catalog.encode(ok.public_key, 'hex')
        )
          FROM relay_private.peer_key_bindings AS b
          JOIN public.owner_employees AS p
            ON p.id = b.pairing_id
           AND p.owner_device_id = b.owner_device_id
           AND p.employee_device_id = b.employee_device_id
          JOIN public.devices AS d
            ON d.id = b.employee_device_id AND d.user_id = v_uid
          JOIN relay_private.device_keys AS ok
            ON ok.device_id = b.owner_device_id AND ok.key_id = b.owner_key_id
           AND ok.is_current
          JOIN relay_private.device_keys AS ek
            ON ek.device_id = b.employee_device_id AND ek.key_id = b.employee_key_id
           AND ek.is_current
         WHERE b.employee_device_id = p_employee_device_id
    );
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_enqueue(
    p_owner_device_id uuid,
    p_employee_device_id uuid,
    p_client_message_id uuid,
    p_encrypted_envelope bytea,
    p_ttl_seconds integer DEFAULT 86400
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_pairing_id uuid;
    v_employee_key_id uuid;
    v_message_id uuid;
    v_expires_at timestamptz;
    v_deduplicated boolean := false;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_client_message_id IS NULL OR p_encrypted_envelope IS NULL
       OR pg_catalog.octet_length(p_encrypted_envelope) NOT BETWEEN 1 AND 1048576
       OR p_ttl_seconds IS NULL OR p_ttl_seconds NOT BETWEEN 1 AND 604800 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_relay_enqueue';
    END IF;

    PERFORM d.id
      FROM public.devices AS d
     WHERE d.id IN (p_owner_device_id, p_employee_device_id)
     ORDER BY d.id
     FOR UPDATE;
    IF NOT EXISTS (
        SELECT 1 FROM public.devices AS d
         WHERE d.id = p_owner_device_id AND d.user_id = v_uid
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;

    SELECT p.id INTO v_pairing_id
      FROM public.owner_employees AS p
     WHERE p.owner_device_id = p_owner_device_id
       AND p.employee_device_id = p_employee_device_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'pairing_not_authorized';
    END IF;

    -- Resolve an existing idempotency key before checking the current peer
    -- binding. A retry of a successfully accepted request must remain a retry
    -- even if a key rotated between the original request and the retry.
    SELECT e.message_id, e.expires_at
      INTO v_message_id, v_expires_at
      FROM relay_private.envelopes AS e
     WHERE e.pairing_id = v_pairing_id
       AND e.client_message_id = p_client_message_id
     FOR UPDATE;
    IF FOUND THEN
        RETURN pg_catalog.jsonb_build_object(
            'message_id', v_message_id,
            'client_message_id', p_client_message_id,
            'employee_key_id', (SELECT e.employee_key_id
                FROM relay_private.envelopes AS e
                WHERE e.message_id = v_message_id),
            'expires_at', v_expires_at,
            'deduplicated', true
        );
    END IF;

    SELECT b.employee_key_id INTO v_employee_key_id
      FROM relay_private.peer_key_bindings AS b
      JOIN relay_private.device_keys AS ok
        ON ok.device_id = b.owner_device_id AND ok.key_id = b.owner_key_id
       AND ok.is_current
      JOIN relay_private.device_keys AS ek
        ON ek.device_id = b.employee_device_id AND ek.key_id = b.employee_key_id
       AND ek.is_current
     WHERE b.pairing_id = v_pairing_id
     FOR UPDATE OF b;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'key_not_bound';
    END IF;

    v_expires_at := pg_catalog.clock_timestamp()
        + (p_ttl_seconds * interval '1 second');
    INSERT INTO relay_private.envelopes (
        pairing_id, owner_device_id, employee_device_id, client_message_id,
        employee_key_id, encrypted_envelope, expires_at
    ) VALUES (
        v_pairing_id, p_owner_device_id, p_employee_device_id, p_client_message_id,
        v_employee_key_id, p_encrypted_envelope, v_expires_at
    )
    ON CONFLICT (pairing_id, client_message_id) DO NOTHING
    RETURNING message_id, expires_at INTO v_message_id, v_expires_at;

    IF v_message_id IS NULL THEN
        SELECT e.message_id, e.expires_at INTO v_message_id, v_expires_at
          FROM relay_private.envelopes AS e
         WHERE e.pairing_id = v_pairing_id
           AND e.client_message_id = p_client_message_id;
        v_deduplicated := true;
    END IF;

    RETURN pg_catalog.jsonb_build_object(
        'message_id', v_message_id,
        'client_message_id', p_client_message_id,
        'employee_key_id', v_employee_key_id,
        'expires_at', v_expires_at,
        'deduplicated', v_deduplicated
    );
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_fetch(
    p_employee_device_id uuid,
    p_limit integer DEFAULT 50,
    p_lease_seconds integer DEFAULT 60
)
RETURNS TABLE (
    message_id uuid,
    client_message_id uuid,
    encrypted_envelope bytea,
    employee_key_id uuid,
    created_at timestamptz,
    expires_at timestamptz,
    lease_token uuid,
    leased_until timestamptz,
    delivery_attempts integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_pairing_id uuid;
    v_now timestamptz := pg_catalog.clock_timestamp();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_employee_device_id IS NULL
       OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100
       OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 1 AND 3600 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_relay_fetch';
    END IF;

    SELECT p.id INTO v_pairing_id
      FROM public.owner_employees AS p
      JOIN public.devices AS d ON d.id = p.employee_device_id
     WHERE p.employee_device_id = p_employee_device_id AND d.user_id = v_uid
     FOR UPDATE OF p;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'employee_device_not_owned';
    END IF;

    RETURN QUERY
    WITH candidates AS (
        SELECT e.message_id
          FROM relay_private.envelopes AS e
         WHERE e.pairing_id = v_pairing_id
           AND e.expires_at > v_now
           AND (e.leased_until IS NULL OR e.leased_until <= v_now)
         ORDER BY e.created_at, e.message_id
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    ), leased AS (
        UPDATE relay_private.envelopes AS e
           SET lease_token = pg_catalog.gen_random_uuid(),
               leased_until = v_now + (p_lease_seconds * interval '1 second'),
               delivery_attempts = e.delivery_attempts + 1
          FROM candidates AS c
         WHERE e.message_id = c.message_id
        RETURNING e.message_id, e.client_message_id, e.encrypted_envelope,
                  e.employee_key_id, e.created_at, e.expires_at, e.lease_token,
                  e.leased_until, e.delivery_attempts
    )
    SELECT l.message_id, l.client_message_id, l.encrypted_envelope,
           l.employee_key_id, l.created_at, l.expires_at, l.lease_token,
           l.leased_until, l.delivery_attempts
      FROM leased AS l
     ORDER BY l.created_at, l.message_id;
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_ack(
    p_employee_device_id uuid,
    p_message_id uuid,
    p_lease_token uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_employee_device_id IS NULL OR p_message_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_relay_ack';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM public.owner_employees AS p
          JOIN public.devices AS d ON d.id = p.employee_device_id
         WHERE p.employee_device_id = p_employee_device_id AND d.user_id = v_uid
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'employee_device_not_owned';
    END IF;

    DELETE FROM relay_private.envelopes AS e
     USING public.owner_employees AS p
     WHERE e.message_id = p_message_id
       AND e.pairing_id = p.id
       AND p.employee_device_id = p_employee_device_id
       AND e.lease_token = p_lease_token
       AND e.leased_until > pg_catalog.clock_timestamp();
    RETURN FOUND;
END
$function$;

CREATE OR REPLACE FUNCTION public.relay_cleanup_expired(
    p_limit integer DEFAULT 1000
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_deleted integer;
BEGIN
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 10000 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_cleanup_limit';
    END IF;
    WITH doomed AS (
        SELECT e.message_id
          FROM relay_private.envelopes AS e
         WHERE e.expires_at <= pg_catalog.clock_timestamp()
         ORDER BY e.expires_at, e.message_id
         LIMIT p_limit
    )
    DELETE FROM relay_private.envelopes AS e
     USING doomed AS d
     WHERE e.message_id = d.message_id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END
$function$;

REVOKE ALL ON FUNCTION public.relay_register_key(uuid, uuid, bytea),
    public.relay_rotate_key(uuid, uuid, bytea),
    public.relay_bind_peer_key(uuid, uuid, uuid),
    public.relay_peer_key(uuid),
    public.relay_enqueue(uuid, uuid, uuid, bytea, integer),
    public.relay_fetch(uuid, integer, integer),
    public.relay_ack(uuid, uuid, uuid),
    public.relay_cleanup_expired(integer)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.relay_register_key(uuid, uuid, bytea),
    public.relay_rotate_key(uuid, uuid, bytea),
    public.relay_bind_peer_key(uuid, uuid, uuid),
    public.relay_peer_key(uuid),
    public.relay_enqueue(uuid, uuid, uuid, bytea, integer),
    public.relay_fetch(uuid, integer, integer),
    public.relay_ack(uuid, uuid, uuid),
    public.relay_cleanup_expired(integer)
    TO authenticated;

COMMIT;
