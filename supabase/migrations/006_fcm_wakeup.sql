-- Store the employee device's FCM token through an ownership-checked RPC.
-- Direct device UPDATE privileges remain revoked by team_pairing.sql.

BEGIN;

ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_token text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS fcm_updated_at timestamptz;

CREATE OR REPLACE FUNCTION public.team_update_fcm_token(
    p_device_id uuid,
    p_device_token text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_token text := pg_catalog.btrim(p_device_token);
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_device_id IS NULL
       OR v_token IS NULL
       OR pg_catalog.char_length(v_token) NOT BETWEEN 1 AND 4096 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_fcm_token';
    END IF;

    UPDATE public.devices AS d
       SET device_token = v_token,
           fcm_updated_at = pg_catalog.clock_timestamp(),
           last_seen_at = pg_catalog.clock_timestamp()
     WHERE d.id = p_device_id AND d.user_id = v_uid;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'device_not_owned';
    END IF;

    RETURN pg_catalog.jsonb_build_object(
        'device_id', p_device_id,
        'fcm_updated_at', pg_catalog.clock_timestamp()
    );
END
$function$;

REVOKE ALL ON FUNCTION public.team_update_fcm_token(uuid, text)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.team_update_fcm_token(uuid, text)
    TO authenticated;

COMMIT;
