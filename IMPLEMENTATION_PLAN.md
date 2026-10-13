# Implementation Plan — Full-App Rebuild (Auto Alert v2.4.5)

**Reference APK:** `Auto Alert v2.4.5` — package `com.binaryscript.payalert`
**Target package ID:** `com.vivekray898.payalert`
**Display name:** `PayAlert`
**minSdk:** 24 (matching reference compatibility). Target: 34 (Android 12L, current Android requirements). Foreground-service constructor changes at API 26/31; we'll use `if` branches per version.

**Constraints:** third-party reference. Reimplement functionality independently. No copied assets, parser rules, or proprietary server-side identifiers. Supabase Free tier. Google OAuth only. No paid dependencies.

---

## 1. Project scaffold

- Gradle + Kotlin + Compose Material3.
- `build.gradle.kts` (app + project), `settings.gradle.kts`, `gradle.properties`
- `AndroidManifest.xml` stub with: `com.vivekray898.payalert`, minSdk 24, targetSdk 34, required permissions, launcher `SplashActivity`, `PaymentMonitorService`, `NotificationListenerService`, `SmsReceiver`.
- `compose/` entry point.

---

## 2. Supabase backend (schema ready)

Supabase project to be created by user later. File: `supabase/schema.sql`.

Tables:

```sql
-- users (sync from Supabase Auth)
users (
  id uuid PRIMARY KEY REFERENCES auth.users(id),
  email text,
  display_name text,
  phone text,
  timezone text,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

-- devices (one row per physical device per user)
devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_name text NOT NULL,
  device_token text, -- FCM registration token
  platform text NOT NULL DEFAULT 'android',
  last_seen_at timestamptz DEFAULT now(),
  paired_with_device_id uuid REFERENCES devices(id), -- for owner↔employee pairing
  is_primary boolean DEFAULT true
);

-- subscription plans (configurable; not tied to Google Play Billing)
subscription_plans (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL, -- e.g. "Free", "Premium", "Pro"
  kind text NOT NULL CHECK (kind IN ('free','premium','pro')),
  monthly_price_cents integer, -- null for free; set by owner via admin UI
  features jsonb NOT NULL DEFAULT '{}', -- bit flags: enable_cashbook, enable_analytics, enable_sync, etc.
  max_cashbook_entries integer,
  created_at timestamptz DEFAULT now()
);

-- user subscription (one per user)
user_subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plan_id uuid NOT NULL REFERENCES subscription_plans(id),
  started_at timestamptz DEFAULT now(),
  ended_at timestamptz,
  active boolean DEFAULT true,
  payment_provider text NOT NULL DEFAULT 'supabase', -- 'supabase' | 'razorpay' | 'play_billing'
  razorpay_subscription_id text, -- if using Razorpay
  play_billing_purchase_token text, -- if using Google Play Billing
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

-- paired employee devices (owner → employee)
owner_employees (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  employee_device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  pairing_code_hash text NOT NULL, -- stored as PBKDF2 hex
  created_at timestamptz DEFAULT now(),
  UNIQUE (owner_device_id, employee_device_id)
);

-- payment_events (cloud backup + cross-device sync)
payment_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('credit','debit','refund','adjustment')),
  bank_or_upi text, -- e.g. "HDFC", "UPI/abc@upi", "ICICI"
  amount numeric NOT NULL,
  currency text NOT NULL DEFAULT 'INR',
  merchant text,
  utr text, -- UPI transaction reference
  tx_id text, -- Razorpay / internal tx id
  received_at timestamptz DEFAULT now(),
  dedup_key text NOT NULL, -- unique across app runs; format: "sms:<sms-id>" or "notif:<pkg>:<ts>:<hash>"
  raw_sender text,
  raw_body text,
  category text, -- e.g. "salary","utility","transfer","refund"
  synced boolean DEFAULT false,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

-- cashbook entries (local + optional cloud sync)
cashbook_entries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('credit','debit')),
  amount numeric NOT NULL,
  category text, -- user-defined or auto
  note text,
  at timestamptz DEFAULT now(),
  synced boolean DEFAULT false
);

-- sync acknowledgments (ensures at-least-once delivery)
sync_acks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id uuid NOT NULL REFERENCES payment_events(id) ON DELETE CASCADE,
  employee_device_id uuid REFERENCES devices(id), -- null if owner ack
  acked_at timestamptz DEFAULT now(),
  UNIQUE (event_id, employee_device_id)
);

-- RLS policies (per-device ownership, per-user admin)

-- payment_events RLS:
--   USING (auth.uid() = device.user_id) -- each device owns its own events
--   WITH CHECK (auth.uid() = device.user_id);

-- user_subscriptions RLS:
--   USING (auth.uid() = user_id);
--   WITH CHECK (auth.uid() = user_id);

-- All other tables similar: row-level ownership via `user_id` or `device_id` + `auth.uid()`.

-- Row-level security:
alter table users enable row level security;
create policy "Users can read own row" on users using (auth.uid() = id);
create policy "Users can update own row" on users using (auth.uid() = id);

alter table devices enable row level security;
create policy "Devices readable by owner" on devices using (true); -- or via user_id
-- policy: each device row belongs to a user; owner can CRUD their own devices.

-- ... repeat for all tables above.

-- Indexes for performance:
create index idx_payment_events_device_id on payment_events(device_id);
create index idx_payment_events_dedup on payment_events(dedup_key);
create index idx_payment_events_synced on payment_events(synced) WHERE synced = false;
create index idx_cashbook_entries_device_id on cashbook_entries(device_id);
```

**Setup instructions** (user-facing, Phase 1 step 7):

1. Create a Supabase project at https://supabase.com.
2. Enable Email + Google auth in Auth settings.
3. Copy `SUPABASE_PUBLISHABLE_URL` and `SUPABASE_PUBLISHABLE_KEY` into `gradle.properties` (never commit).
4. Run `supabase/schema.sql` in the Supabase SQL editor.
5. Add Android app: project ref → package `com.vivekray898.payalert` → download `google-services.json` from Firebase Console (Google OAuth client config).
6. In Google Cloud Console: create OAuth 2.0 Web client for Supabase redirect URI: `https://<project>.supabase.co/auth/v1/callback`.
7. In Supabase Dashboard → Authentication → URL Configuration: add the same redirect.
8. (Optional) Create `subscription_plans` rows: `id`, `name`, `kind`, `monthly_price_cents`, `features`.
9. For owner→employee pairing: generate PBKDF2 hashes on the device, store `pairing_code_hash` in `owner_employees`.

**Important:** Never commit `SUPABASE_PUBLISHABLE_URL` or `SUPABASE_PUBLISHABLE_KEY` to VCS. Use `gradle.properties` with `.gitignore` protection.

---

## 3. Architecture & build pipeline

```
app/
├─ data/
│  ├─ dao/        Room DAOs (payment_events, cashbook_entries, dedup_index, settings)
│  ├─ repo/       Repos: local-first + SupabaseSyncClient (offline-first queuing)
│  ├─ parser/     BankSmsParser, UpiParser, ParserRegistry — pure Kotlin, testable, rules in res/raw/parsers.json
│  └─ supabase/   Supabase client config (url, anon key from gradle.properties)
├─ di/            Manual service locator (no Hilt) — provides Repo, ParserRegistry, TtsEngine
├─ ui/
│  ├─ main/       SplashActivity → MainNavHost (Compose Navigation) → home/dashboard
│  ├─ auth/       GoogleSignInViewModel, SupabaseAuthGate, splash
│  ├─ diagnostics/ DiagnosticsScreen (per requirements)
│  ├─ transactions/ TransactionsListScreen, TransactionDetailScreen
│  ├─ cashbook/   CashbookListScreen, AddEditCashBookEntryDialog
│  ├─ settings/   TtsSettingsScreen, LanguagePicker, AnnouncementSettingsScreen
│  ├─ subscription/ SubscriptionScreen, ManageSubscriptionScreen (Supabase plans)
│  ├─ qr/         QrScannerScreen (Journeyapps BarcodeScanner)
│  ├─ widget/     WidgetConfigScreen, WidgetProvider (if we implement)
│  ├─ faq/        FaqScreen
│  ├─ team/       TeamManagementScreen (owner→employee pairing)
│  ├─ referral/   ReferralScreen (referral code display + copy)
│  └─ FaqScreen
├─ services/
│  ├─ PaymentMonitorService.kt       (foreground FGS; version-aware constructor)
│  ├─ NotificationListenerService.kt  (system notification reader)
│  ├─ SmsReceiver.kt                  (SMS broadcast receiver)
│  ├─ BootReceiver.kt                 (BOOT_COMPLETED + user-present rearm)
│  └─ ServiceRestartReceiver.kt
├─ tts/           TtsEngine (TextToSpeech wrapper + sound picker)
├─ parsers/       BankSmsParser, UpiParser, ParserRegistry
├─ sync/          SupabaseSyncClient (best-effort offline-queued upserts)
└─ widgets/       (optional; Phase 2)
```

**Layers (from UI down):**
- UI: Compose `@Composable` functions — no Android view references.
- Use cases / ViewModel: expose `StateFlow`s; handle loading, error, empty states.
- Repo: local DAO + SupabaseSyncClient (merge strategy: local wins unless online + newer).
- Parser: pure Kotlin functions — no Android context needed; testable with unit tests.
- TTS: pure wrapper — `text, language, speed, pitch, volume` params; UI calls it.

**Version-aware Foreground Service:**
- API 26+ (`minSdk` 24 supports both): use `ServiceCompat.startForegroundService(Intent)` wrapper.
- At API 31+ (`TARGET_SDK_VERSION >= 31`): Foreground service requires `foregroundServiceType` parameter in `Notification`. We'll declare `foregroundServiceType: "mediaProjection"` or use `PRIORITY_LOW_BACKGROUND` intent extras. Reference app uses `FOREGROUND_SERVICE_SPECIAL_USE` which maps to the new API.
- We'll implement a `ForegroundServiceStarter` that picks the right constructor/path based on `Build.VERSION.SDK_INT`.

---

## 4. Feature implementation order (Phase 1 → Phase 4)

### Phase 1: Scaffold + diagnostics + auth + TTS + foreground service
1.1 Gradle + Compose project scaffolding (package `com.vivekray898.payalert`, minSdk 24, targetSdk 34).
1.2 `supabase/schema.sql` placed; user creates project, runs migrations.
1.3 Manual DI container (no Hilt).
1.4 DiagnosticsScreen — reads: notification policy state, SMS state, battery opt-out state, FGS running, last SMS parsed, last notification parsed, parser result, sync status.
1.5 Supabase Google OAuth flow: SplashActivity → restore session → sign-in button → onSuccess → ensure `users` + `devices` rows → register FCM token.
1.6 TtsEngine — `TextToSpeech` init; language/speed/volume/voice picker; announcement text template config; fallback system sound.
1.7 PaymentMonitorService — version-aware FGS starter; wake lock; TTS announcement queue; boot rearm (BootReceiver).
1.6 SmsReceiver — read SMS (needs `READ_SMS` permission; on Android 11+ user grants in settings); hand off to ParserRegistry.
1.7 ParserRegistry — pure Kotlin bank/UPI SMS parsers; rules in `res/raw/parsers.json` (authored independently). First parsers: generic Indian bank credit/debit, HDFC-like patterns (read-only reference, not copied).
1.8 DedupIndex — in-memory + Room `dedup_index` table; key format `sms:<id>` / `notif:<pkg>:<ts>:<hash>`.

### Phase 2: Core detection + announcement pipeline
2.1 SmsReceiver → ParserRegistry.match() → ParsedEvent?
    ↓ (if parse succeeded)
2.2 DedupIndex.check(dedupKey)
    ↓ (if new)
2.3 RoomInsert(transaction)
    ↓
2.4 AnnouncementComposer.compose(event, settings)
    ↓
2.5 TtsEngine.announce(text, volume, speed, voice, soundId)
    ↓
2.6 SyncClient.upsertPaymentEvent → Supabase (best-effort; queue if offline).

### Phase 3: Transaction & cashbook UI
3.1 TransactionsListScreen — Room‑backward list; grouped by kind; empty state; loading; error.
3.2 TransactionDetailScreen — amounts, UTR, merchant, date, kind badge.
3.3 CashbookListScreen — debit/credit entries; totals at top.
3.4 AddEditCashBookEntryDialog — form with kind picker, amount, category, note; validation; save → Room + optional sync.
3.5 CashbookEntry entity + DAO.

### Phase 4: Settings, pairing, subscription, utility screens
4.1 SettingsActivity (Compose) — subtabs:
  - 4.1.1 Language & region (spinner, 9 locale options; on change: update TtsEngine language + SharedPrefs + sync to Supabase if online).
  - 4.1.2 TTS: voice, speed (float 0.1–2.0), pitch (float 0.5–2.0), default sound (picker: system notifications or our 2-3 original chimes). SharedPrefs; sync flag.
  - 4.1.3 Announcement format: template string editor (e.g. "You received {amount} from {merchant} at {time}"); live preview.
  - 4.1.4 Duplicate prevention: dedup key display; SMS-id vs notification hash modes; reset button.
4.2 SubscriptionScreen — display `subscription_plans` rows; let user pick a plan → update `user_subscriptions` row; basic entitlement toggle UI (no Razorpay/Play Billing in Phase 1; we flag features per-plan).
4.3 ManageSubscriptionScreen — view active plan, start date, revoke.
4.4 TeamManagementScreen — owner generates 6-char code → PBKDF2 hash → upsert `owner_employees.employee_device_id` → employee enters code → same flow in reverse; read-access grant to `payment_events` rows on owner's device via RLS; revoke button.
4.5 ReferralScreen — display referral code (device_id + user_id hash); copy-to-clipboard; optionally track clicks via Supabase Analytics (free).
4.6 FaqScreen — static FAQ entries; simple accordion.
4.7 QrScannerScreen — use `com.journeyapps:barcode-scanner` dependency; on scan, launch intents or show UPI QR (auto-fill payee field). I: journeyapps library; free Apache 2.0.
4.8 WidgetConfigScreen — intent builder for widget; we'll create a basic AppWidget subclass (minimal) and config activity; reference had `WidgetConfigActivity` + `UpiQrWidgetReceiver`.

### Phase 5: Polish & testing
5.1 Replace placeholder sounds with 2-3 original short chimes (generated at build or bundled).
5.2 Accessibility talkback labels on all interactive elements.
5.3 Error handling: network-offline degradation, parser-no-match, TTS-not-available, permission-denied.
5.4 Unit tests for parsers, dedup, TtsEngine.
5.5 Install to device, take screenshot comparisons against reference (matching sizes).
5.6 Fix defects before promoting to next phase.

---

## 5. Feature coverage ledger (per-feature status; updated each phase)

| # | Feature | Status | Notes / Blocker |
|---|---|---|---|
| 1 | SMS + notification payment detection | Confirmed | Two paths; primary = notification listener, secondary = SMS |
| 2 | Bank SMS parsing | Confirmed (static) | Rules authored independently; not copied from reference |
| 3 | Payment announcement via TTS | Confirmed (static) | `TextToSpeech` + config; system sound fallback |
| 4 | Language/voice/speed/volume settings | Confirmed (static) | 9 locales; SharedPrefs + TtsEngine update |
| 5 | Foreground service + boot recovery | Confirmed (static) | Version-aware starter; 3 boot receivers |
| 6 | SMS fallback for missed notifications | Confirmed (static) | `SmsCreditFallbackService` semantics |
| 7 | Transaction history | Inferred | Room-backed; UI in Phase 3 |
| 8 | Transaction analytics | Inferred | UI; basic analytics-ready data model |
| 9 | Cashbook / passbook entries | Inferred | `AddEditCashBookEntryActivity` → AddEditCashBookEntryDialog |
| 10 | Add/edit cashbook entries | Inferred | Form + validation + Room |
| 11 | Upcoming payments / reminders | Inferred | `UpcomingPaymentsActivity`; may defer to Phase 2 |
| 12 | QR scanning / workflows | Blocked dependency | `journeyapps-barcode-scanner` library (free Apache 2.0); needs camera permission; optional — defer if camera not wanted |
| 13 | Home-screen widget + config | Inferred | `WidgetConfigActivity` + `UpiQrWidgetReceiver`; AppWidget minimal implementation |
| 14 | FAQ screen | Inferred | `FaqsActivity`; static text |
| 15 | Settings (general) | Confirmed (static) | `TtsSettingsActivity` + many prefs; we build SettingsActivity |
| 16 | Team / device management | Inferred | `team.TeamActivity` + sync ops; owner→employee via RLS + `owner_employees` table |
| 17 | Referrals | Inferred | `ReferralActivity` + referral code; Supabase Analytics free |
| 18 | Payment announcements via notification fallback | Confirmed (static) | Subtype semantics |
| 19 | Account/profile management, Google sign-in | Confirmed (static) | Supabase Google OAuth |
| 20 | Sign-out | Confirmed (static) | `RevocationBoundService` semantics |
| 21 | Subscription / manage-subscription | Inferred | `SubscriptionActivityNew` + `ManageSubscriptionActivity` + Razorpay + Play Billing; we implement own entitlement config in `user_subscriptions` + `subscription_plans` |
| 22 | All other utility screens (MoreApps, WebView, FAQ, etc.) | Inferred | Component names present; we build equivalents |
| 23 | Firebase-style notifications (FCM) | Confirmed (static) | `AutoAlertFirebaseMessagingService`; we use Supabase Realtime + optional FCM |
| 24 | Owner-to-employee device pairing | Inferred | `owner_employees` table + pairing code; RLS policies |

**Bl blockers (documented per feature):**
- QR scanner requires camera permission + journeyapps library — camera is explicitly not rebuilt per requirements (user said "avoid paid deps", camera also raises privacy concerns). **Decision:** we will either defer QR scanning entirely or implement a minimal "paste UPI address" screen instead. I'll note this in Phase 2.
- Play Licensing / Razorpay: not rebuilt. We use our own `subscription_plans` + `user_subscriptions` with free-tier + paid-plan flags; user configures plans via Supabase dashboard. No payment gateway integration in Phase 1.
- Truecaller SDK: not rebuilt. We skip `com.truecaller.permission.sdk.internal.read_account_state`.
- CHECK_LICENSE / pairip licensecheck: not rebuilt. Our entitlements are Supabase-backed.

---

## 6. Testing checklist (per completed feature)

- Parser regex unit tests (≥ 3 per parser; known-good strings + edge cases).
- Dedup index test (first hit saves; second hit drops).
- TtsEngine init + language/speed/pitch/volume change tests.
- Diagnostics screen reads correct system-state values (via Robolectric or real device).
- Auth flow: anon → Google sign-in → session persists across app restart.
- SmsReceiver on device (Android 13+ requires both manifest and settings grant).
- PaymentMonitorService starts in foreground; stops when app goes to background.
- Room DB migrations tested (incremental add of columns).
- SyncClient upsert + offline queue test (mock offline, then reconcile online).
- Subscription-plan UI: plan pick updates `user_subscriptions` row.

---

## 7. What I need from you to start scaffold

| Field | Value |
|---|---|
| **Package ID** | `com.vivekray898.payalert` (already locked) |
| **Display name** | `PayAlert` (already locked) |
| **minSdk** | 24 (locked — matching reference) |
| **targetSdk** | 34 (I've set; acceptable for Android 12L + tools) |
| **Supabase project** | Not yet created. I've prepared `supabase/schema.sql`; you create the project, enable Google + Email auth, copy `SUPABASE_PUBLISHABLE_URL` + `SUPABASE_PUBLISHABLE_KEY` into `gradle.properties`. |
| **Google OAuth client** | Not yet configured. After scaffold, we create a Web‑application OAuth client in Google Cloud Console; add redirect `https://<project>.supabase.co/auth/v1/callback`; add Android client ID (package `com.vivekray898.payalert` + SHA‑1 from debug keystore). |
| **minSdk vs targetSdk decision** | I've set minSdk=24, targetSdk=34. If you want a different target, speak up. |

**Once confirmed**, I'll scaffold Gradle + `AndroidManifest.xml` stub + `supabase/schema.sql` + core source files (DI, Auth, TtsEngine, FGS, Diagnostics, SmsReceiver, ParserRegistry) and we'll build + install on device from there.

No code will be written until these 5 fields are confirmed. I'll also flag the QR‑scanner / camera blocker and the Play‑Licensing / Razorpay skip in the feature ledger above.

Skipped for now: QR scanning with camera, Play Billing/Razorpay, Truecaller SDK, CHECK_LICENSE / pairip licensecheck, adMob/AdServices, camera, Bluetooth. All documented as blockers above.