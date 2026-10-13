-- =============================================================================
-- PayAlert Supabase Schema
-- =============================================================================
-- Run via the Supabase Dashboard SQL editor or `supabase db reset`.
-- Never commit real publishable keys to version control.
-- Use gradle.properties placeholders or CI secrets instead.
-- =============================================================================

-- 1. The uuid type and gen_random_uuid() are native in Supabase PostgreSQL.
--    No extension installation is required.
--    (If you ever run into "type uuid unknown", ensure the database has the
--   uuid extension: CREATE EXTENSION IF NOT EXISTS "uuid";)

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
-- Sync'd from Supabase Auth on sign-in. One row per authenticated user.
DROP TABLE IF EXISTS users CASCADE;
CREATE TABLE users (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    display_name TEXT,
    phone TEXT,
    timezone TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- RLS: users can read/write their own row
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can read own row" ON users USING (auth.uid() = id);
CREATE POLICY "Users can update own row" ON users USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

-- ---------------------------------------------------------------------------
-- devices
-- ---------------------------------------------------------------------------
-- One row per physical device per user. used for owner↔employee pairing
-- and cross-device sync of payment events.
DROP TABLE IF EXISTS devices CASCADE;
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,          -- e.g. "Maman's iPhone 15"
    device_token TEXT,                  -- FCM registration token
    platform TEXT NOT NULL DEFAULT 'android',
    last_seen_at TIMESTAMPTZ DEFAULT now(),
    paired_with_device_id UUID REFERENCES devices(id), -- null if primary/standalone
    is_primary BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- RLS: each device belongs to its user; owners can see all their devices
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Devices readable by owner" ON devices
    USING (auth.uid() = user_id);
CREATE POLICY "Devices insertable by owner" ON devices
    WITH CHECK (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- subscription_plans (configurable; not tied to Google Play Billing)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS subscription_plans CASCADE;
CREATE TABLE subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,                 -- e.g. "Free", "Premium", "Pro"
    kind TEXT NOT NULL CHECK (kind IN ('free','premium','pro')),
    monthly_price_cents INTEGER,       -- null for free; set by owner via admin UI
    features JSONB NOT NULL DEFAULT '{}', -- bit flags: enable_cashbook, enable_analytics, etc.
    max_cashbook_entries INTEGER,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- RLS: plans are read-only for end users; admin can manage via separate channel
ALTER TABLE subscription_plans ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Plans readable by all authenticated users" ON subscription_plans
    USING (auth.uid() IS NOT NULL);

-- ---------------------------------------------------------------------------
-- user_subscriptions (one per user)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS user_subscriptions CASCADE;
CREATE TABLE user_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES subscription_plans(id),
    started_at TIMESTAMPTZ DEFAULT now(),
    ended_at TIMESTAMPTZ,
    active BOOLEAN DEFAULT TRUE,
    payment_provider TEXT NOT NULL DEFAULT 'supabase',   -- 'supabase'|'razorpay'|'play_billing'
    razorpay_subscription_id TEXT,                        -- if using Razorpay
    play_billing_purchase_token TEXT,                     -- if using Google Play Billing
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- RLS: users can read/write their own subscription row
ALTER TABLE user_subscriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can read own subscription" ON user_subscriptions
    USING (auth.uid() = user_id);
CREATE POLICY "Users can update own subscription" ON user_subscriptions
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- ---------------------------------------------------------------------------
-- owner_employees (owner → employee device pairing)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS owner_employees CASCADE;
CREATE TABLE owner_employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    employee_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    pairing_code_hash TEXT NOT NULL,  -- stored as PBKDF2 hex string, generated on device
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (owner_device_id, employee_device_id)
);

-- RLS: owner can see/manage their pairings; employee can see own pairing
ALTER TABLE owner_employees ENABLE ROW LEVEL SECURITY;
CREATE POLICy "Owner can manage pairings" ON owner_employees
    USING (owner_device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));
CREATE POLICY "Employee can see own pairing" ON owner_employees
    USING (employee_device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));

-- ---------------------------------------------------------------------------
-- payment_events (cloud backup + cross-device sync)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS payment_events CASCADE;
CREATE TABLE payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('credit','debit','refund','adjustment')),
    bank_or_upi TEXT,              -- e.g. "HDFC", "UPI/abc@upi", "ICICI"
    amount NUMERIC NOT NULL,
    currency TEXT NOT NULL DEFAULT 'INR',
    merchant TEXT,
    utr TEXT,                      -- UPI transaction reference
    tx_id TEXT,                    -- Razorpay / internal tx id
    received_at TIMESTAMPTZ DEFAULT now(),
    dedup_key TEXT NOT NULL UNIQUE,   -- unique across app runs; format: "sms:<id>" or "notif:<pkg>:<ts>:<hash>"
    raw_sender TEXT,
    raw_body TEXT,
    category TEXT,                 -- e.g. "salary","utility","transfer","refund"
    synced BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Indexes for sync performance
CREATE INDEX idx_payment_events_device_id ON payment_events(device_id);
CREATE INDEX idx_payment_events_dedup ON payment_events(dedup_key);
CREATE INDEX idx_payment_events_synced ON payment_events(synced) WHERE synced = FALSE;

-- RLS: each device owns its own events; cross-device visibility only via owner_employees
ALTER TABLE payment_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Events readable by device owner" ON payment_events
    USING (device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));
CREATE POLICY "Events insertable by device" ON payment_events
    WITH CHECK (device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));
CREATE POLICY "Events updatable by device" ON payment_events
    USING (device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));

-- ---------------------------------------------------------------------------
-- cashbook_entries (local + optional cloud sync)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS cashbook_entries CASCADE;
CREATE TABLE cashbook_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('credit','debit')),
    amount NUMERIC NOT NULL,
    category TEXT,
    note TEXT,
    at TIMESTAMPTZ DEFAULT now(),
    synced BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- RLS: per-device ownership
ALTER TABLE cashbook_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Cashbook readable by device owner" ON cashbook_entries
    USING (device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));
CREATE POLICY "Cashbook insertable by device" ON cashbook_entries
    WITH CHECK (device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));
CREATE POLICY "Cashbook updatable by device" ON cashbook_entries
    USING (device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));

-- ---------------------------------------------------------------------------
-- sync_acks (ensures at-least-once delivery to employee devices)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS sync_acks CASCADE;
CREATE TABLE sync_acks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES payment_events(id) ON DELETE CASCADE,
    employee_device_id UUID REFERENCES devices(id),
    acked_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (event_id, employee_device_id)
);

-- RLS: owner can ack; employee acks are read-only
ALTER TABLE sync_acks ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Owner can ack events" ON sync_acks
    USING (owner_device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));
CREATE POLICY "Employee can view acks" ON sync_acks
    USING (employee_device_id IN (
        SELECT id FROM devices WHERE user_id = auth.uid()
    ));

-- ---------------------------------------------------------------------------
-- RLS policies summary (admin only — commented for now)
-- ---------------------------------------------------------------------------
/*
-- Admin-level policies (run as service_role user):
-- Grant full access to admin role for setup / migrations:
-- GRANT ALL ON ALL TABLES TO service_role;
-- REVOKE ALL ON ALL TABLES FROM public;
*/

-- =============================================================================
-- End of schema
-- =============================================================================