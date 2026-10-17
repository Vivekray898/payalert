-- Add fcm_updated_at to devices so the app can decide when to refresh an FCM token.
-- The existing device_token column already holds the FCM registration token; this
-- column records when it was last refreshed so a stale token can be rotated.

BEGIN;

ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_token text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS fcm_updated_at timestamptz;

COMMIT;
