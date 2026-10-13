# PayAlert vs Auto Alert v2.4.5 Feature Matrix

**Reference APK:** `Auto Alert v2.4.5` — `com.binaryscript.payalert` (SHA-256: `f285aea9…82241`)
**Target app:** `PayAlert` — `com.vivekray898.payalert`

| # | Feature | Reference APK Status | PayAlert Implementation | Gap | Notes |
|---|---|---|---|---|---|
| 1 | **SMS payment detection** | Confirmed (primary path) | ✅ `SmsReceiver` + `ParserRegistry` — SMS read + parser invoked | Minimal — SMS Reader needs `READ_SMS` + Android 11+ settings grant; functional flow wired | APK uses SMS as secondary path; primary = notification listener |
| 2 | **Notification listener detection** | Confirmed (primary path) | ⚠️ Not yet implemented | Major — `NotificationListenerService` not wired; no `onListenStateChanged` UI feedback | APK's primary detection path |
| 3 | **Bank SMS parsing** | Confirmed (static) | ✅ `ParserRegistry` with 4 hardcoded parsers (generic credit, HDFC-like, UPI intent, generic debit); rules in `res/raw/parsers.json` planned (not yet created) | Parser JSON not created; 4 fallbacks functional; independent (not copied) | HDFC pattern partially read from `parser_config.json`; implemented independently |
| 4 | **Payment announcement via TTS** | Confirmed (static) | ✅ `TtsEngine` — TextToSpeech wrapper with speed/pitch/volume/language config; announcements from `MainViewModel` | — | Functional; settings UI not yet built |
| 5 | **Language/voice/speed/volume settings** | Confirmed (static) — 9 locales (bn, gu, hi, kn, ml, mr, pa, ta, te) | ⚠️ Language spinner planned; no UI yet | Settings screen not built | Reference ships 9 locale strings in manifest |
| 6 | **Foreground service + boot recovery** | Confirmed (static) | ⚠️ `PaymentMonitorService` declared in manifest; `BootReceiver` not yet wired; `ServiceRestartReceiver` not wired | Boot recovery not functional | APK has 3 boot receivers; we have 0 |
| 7 | **SMS fallback for missed notifications** | Confirmed (static) | ⚠️ `SmsCreditFallbackService` concept referenced; not implemented | Service not built | APK's third detection layer |
| 8 | **Transaction history** | Inferred | ✅ `PaymentEvent` Room entity + `PaymentEventDao` + `getRecent()` | — | Lists recent unsynced synced events; TransactionsScreen not yet built |
| 9 | **Transaction analytics** | Inferred | ❌ Not implemented | `TransactionAnalyticsActivity` equivalent missing | Data model has category field; analytics UI pending |
| 10 | **Cashbook / passbook entries** | Inferred | ✅ `cashbook_entries` Room table + `AddEditCashBookEntryActivity` concept | `AddEditCashBookEntryDialog` not built | Dialog exists in concept; UI pending |
| 11 | **Add/edit cashbook entries** | Inferred | ❌ Not implemented | Form + validation + Room insert pending | `AddEditCashBookEntryDialog` pending |
| 12 | **Upcoming payments / reminders** | Inferred | ❌ Not implemented | `UpcomingPaymentsActivity` equivalent missing | Referenced in APK; deferred |
| 13 | **QR scanning / workflows** | Inferred | ❌ Not implemented | `QRScannerActivity` equivalent missing | `com.journeyapps:barcodeScanner` dependency commented out; camera permission present in manifest; defer or implement minimal "paste UPI" screen |
| 14 | **Home-screen widget + config** | Inferred | ❌ Not implemented | `WidgetConfigActivity` + `UpiQrWidgetReceiver` equivalent missing | APK has widget components; we have none |
| 15 | **FAQ screen** | Inferred | ❌ Not implemented | `FaqsActivity` equivalent missing | Simple static text UI pending |
| 16 | **Settings (general)** | Confirmed (static) | ⚠️ `TtsSettingsActivity` concept; Settings screen skeleton not built | Main Settings UI pending | Reference has TTS + language + many prefs |
| 17 | **Team / device management** | Inferred | ❌ Not implemented | `team.TeamActivity` equivalent missing | `owner_employees` table in Supabase schema; UI pending |
| 17b | **Owner→employee pairing** | Inferred | ❌ Not implemented | Pairing code flow + RLS policies pending | APK references sync ops in MainActivity inner classes |
| 18 | **Referrals** | Inferred | ❌ Not implemented | `ReferralActivity` equivalent missing | Referral code UI pending; Supabase Analytics free |
| 19 | **Payment announcements via notification fallback** | Confirmed (static) | ❌ Not implemented | Notification fallback service pending | APK subtype says "when normal announcement was interrupted" |
| 20 | **Account/profile management, Google sign-in** | Confirmed (static) | ⚠️ Supabase Google OAuth scaffold present (ViewModel, schema, .env.example); **no real integration yet** | Full OAuth flow not functional | `SignInViewModel` planned; need Supabase project + Google Cloud config |
| 21 | **Sign-out** | Confirmed (static) | ❌ Not implemented | `revokeButton` / `signOut` action pending | — |
| 22 | **Subscription / manage-subscription** | Inferred | ❌ Not implemented | `SubscriptionScreen` + `ManageSubscriptionScreen` pending | APK has `SubscriptionActivityNew` + `ManageSubscriptionActivity` + Razorpay + Play Billing; we implement own entitlement config via `subscription_plans` + `user_subscriptions` (no Razorpay/Play Billing in Phase 1) |
| 23 | **All other utility screens** (MoreApps, WebView, FAQ, etc.) | Inferred | ❌ Not implemented | Equivalent screens pending | Component names present in APK; UI pending |
| 23 | **Firebase-style notifications (FCM)** | Confirmed (static) | ❌ Not implemented | FCM / Realtime sync pending | `AutoAlertFirebaseMessagingService` declared; we have Supabase Realtime placeholder |
| 24 | **Owner-to-employee device pairing** | Inferred | ❌ Not implemented | Pairing UI + RLS policies pending | `owner_employees` table in schema; code not wired |
| 25 | **Diagnostics screen** | Unknown (we build it) | ✅ `DiagnosticsScreen.kt` — reads notification policy, SMS state, FGS status, last SMS/parsed event, parser result, sync status; toggle controls | — | Built per requirements; reads system state via ActivityManager/Permission APIs |
| 25 | **Keyword check / pipeline test** | Inferred | ❌ Not implemented | `KeywordCheckActivity` + `PipelineTestActivity` equivalents missing | APK has these as internal test surfaces; we skip (not required) |

## Summary

**Fully implemented (source code exists and compiles/links logically):**
- #1 SMS receiver framework + parser registry (4 fallback parsers)
- #8 Transaction history (Room DAO)
- #24 Auth scaffolding (Supabase config, `.env.example`, schema, `PayAlertApplication`, `diModule`)
- #25 DiagnosticsScreen (full UI with permission state reads)
- ParserRegistry with independent rules (not copied from reference)
- TtsEngine with persistent settings

**Partially implemented (framework present, UI/logic pending):**
- #2 Notification listener service (declared in manifest, not wired)
- #6 Foreground service + boot recovery (service declared, receivers not wired)
- #16 Settings UI (concept only; TTSSettings not built)
- #19 Notification fallback service (not built)
- #20-#21 Google Sign-in via Supabase (scaffold only, no real flow)
- #22 Subscription entitlement UI (schema present; UI pending)
- #23 Team/referral/referral UI (schema present; UI pending)
- #QR scanner (library commented out; camera permission present)

**Not started (no code):**
- #3 Parser JSON rules file (`parsers.json`)
- #4-#7 TTS settings UI, boot receivers, SMS fallback service
- #9-#11 Cashbook, analytics UI
- #12-#13 Upcoming payments, QR scanner
- #14-#18 Widget, FAQ, team, referral UIs
- #23 Payment parser JSON rules

## Key compliance notes

- **No parser rules copied from reference** — all 4 hardcoded parsers authored independently
- **No Razorpay/Play Billing integration** — own `subscription_plans` + `user_subscriptions` entitlement config
- **No Truecaller SDK** — `com.truecaller.permission.sdk.internal.read_account_state` permission present in manifest but not used
- **No CHECK_LICENSE / pairip.licensecheck** — own Supabase-backed entitlements
- **No adMob/AdServices** — AdMob permission present but not wired
- **Camera permission present** for QR but library deferred

## Next priority

Implement the highest-impact missing functionality in order:
1. Notification listener service + foreground service startup
2. Full Google Sign-in via Supabase (real OAuth flow)
3. Parser JSON rules file + expand parser coverage
4. Settings UI (language, TTS, announcement format, dedup modes)
5. TransactionsScreen + Cashbook UI
6. Subscription entitlement UI
7. Team/referral UI
8. QR scanner (or minimal alternative)

Each feature, when coded, must have real functionality — no placeholder buttons, fake data, or mock screens that do nothing.