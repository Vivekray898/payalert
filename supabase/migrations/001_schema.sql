-- =============================================================================
-- PayAlert — optional cloud-sync schema (NON-DESTRUCTIVE migration)
-- =============================================================================
-- The Android app is local-first: payment features require no account or sync.
-- Optional Team & devices uses users/devices plus supabase/team_pairing.sql.
-- Apply this file FIRST, then team_pairing.sql (which restricts device writes).
-- Payment/cashbook cloud tables remain unused; pairing grants no access to them.
--
-- This script is safe to re-run. It NEVER drops a table or deletes rows: every
-- statement is CREATE ... IF NOT EXISTS, or replaces a policy/index definition.
--
-- Extensions: none are required. `gen_random_uuid()` is built into PostgreSQL 13+
-- (and therefore into every Supabase project), so `uuid-ossp` / `uuid_ossl` is
-- deliberately NOT installed or referenced anywhere below.
--
-- Run via the Supabase SQL editor, or `supabase db push` with this file as a migration.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    display_name TEXT,
    phone TEXT,
    timezone TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE users ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read own row" ON users;
CREATE POLICY "Users can read own row" ON users
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update own row" ON users;
CREATE POLICY "Users can update own row" ON users
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

-- ---------------------------------------------------------------------------
-- devices
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    device_token TEXT,                                    -- FCM registration token
    platform TEXT NOT NULL DEFAULT 'android',
    last_seen_at TIMESTAMPTZ DEFAULT now(),
    paired_with_device_id UUID REFERENCES devices(id),    -- null if standalone
    is_primary BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Devices readable by owner" ON devices;
CREATE POLICY "Devices readable by owner" ON devices
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Devices insertable by owner" ON devices;
CREATE POLICY "Devices insertable by owner" ON devices
    WITH CHECK (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- payment_events
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('credit','debit')),
    bank_or_upi TEXT,
    amount NUMERIC NOT NULL,
    currency TEXT NOT NULL DEFAULT 'INR',
    merchant TEXT,
    utr TEXT,
    account TEXT,                       -- masked account, e.g. "XX1234"
    balance NUMERIC,                    -- balance quoted by the alert, when present
    received_at TIMESTAMPTZ DEFAULT now(),
    -- Same content-based key the app uses on-device, so a re-upload is idempotent.
    dedup_key TEXT NOT NULL UNIQUE,
    raw_sender TEXT,
    raw_body TEXT,
    category TEXT,
    synced BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Columns added after the first release; ADD COLUMN IF NOT EXISTS keeps an existing
-- deployment in step without touching the rows already stored.
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS account TEXT;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS balance NUMERIC;

CREATE INDEX IF NOT EXISTS idx_payment_events_device_id ON payment_events(device_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_dedup ON payment_events(dedup_key);
CREATE INDEX IF NOT EXISTS idx_payment_events_synced ON payment_events(synced) WHERE synced = FALSE;

ALTER TABLE payment_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Events readable by device owner" ON payment_events;
CREATE POLICY "Events readable by device owner" ON payment_events
    USING (device_id IN (SELECT id FROM devices WHERE user_id = auth.uid()));

DROP POLICY IF EXISTS "Events insertable by device" ON payment_events;
CREATE POLICY "Events insertable by device" ON payment_events
    WITH CHECK (device_id IN (SELECT id FROM devices WHERE user_id = auth.uid()));

DROP POLICY IF EXISTS "Events updatable by device" ON payment_events;
CREATE POLICY "Events updatable by device" ON payment_events
    USING (device_id IN (SELECT id FROM devices WHERE user_id = auth.uid()));

-- ---------------------------------------------------------------------------
-- cashbook_entries
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cashbook_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('credit','debit')),
    amount NUMERIC NOT NULL,
    category TEXT,
    note TEXT,
    occurred_at TIMESTAMPTZ DEFAULT now(),
    synced BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE cashbook_entries ADD COLUMN IF NOT EXISTS label TEXT;

ALTER TABLE cashbook_entries ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Cashbook readable by device owner" ON cashbook_entries;
CREATE POLICY "Cashbook readable by device owner" ON cashbook_entries
    USING (device_id IN (SELECT id FROM devices WHERE user_id = auth.uid()));

DROP POLICY IF EXISTS "Cashbook insertable by device" ON cashbook_entries;
CREATE POLICY "Cashbook insertable by device" ON cashbook_entries
    WITH CHECK (device_id IN (SELECT id FROM devices WHERE user_id = auth.uid()));

DROP POLICY IF EXISTS "Cashbook updatable by device" ON cashbook_entries;
CREATE POLICY "Cashbook updatable by device" ON cashbook_entries
    USING (device_id IN (SELECT id FROM devices WHERE user_id = auth.uid()));

-- =============================================================================
-- Deliberately NOT included
-- =============================================================================
-- There is no subscription, entitlement, purchase, trial, licence or billing table
-- anywhere in this schema, and no monetization column on any table. Every PayAlert
-- feature is available to every user.
--
-- =============================================================================
-- Full reset (DESTRUCTIVE — never run this on live data)
-- =============================================================================
-- Only for a throwaway development project that should start empty:
--
--   DROP TABLE IF EXISTS payment_events CASCADE;
--   DROP TABLE IF EXISTS cashbook_entries CASCADE;
--   DROP TABLE IF EXISTS devices CASCADE;
--   DROP TABLE IF EXISTS users CASCADE;
-- =============================================================================
