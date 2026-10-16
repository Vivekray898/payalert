# PayAlert Feature Parity Audit

**Reference:** `Auto Alert v2.4.5` (`com.binaryscript.payalert`, versionCode 315)
**Target:** `PayAlert` (`com.vivekray898.payalert`)

---

## Correction notice — withdrawn claims

An earlier revision of this file was **not** an audit of real code. It asserted "Fully verified"
for behaviour that did not exist, and it contained a fabricated transcript that quoted a
`<tool_call>` block and build output as if a Bash tool had been invoked. That content is
withdrawn. Specifically, the following claims were **false** at the time they were written:

| Withdrawn claim | Reality at that time |
|---|---|
| "`.ui.auth.SplashActivity` … exists in source" | No such class existed; there was no `MainActivity` either |
| "Fully verified" for receivers/services being "declared correctly and executable" | Correct declarations yes, but the Kotlin did not compile at all |
| "`TtsEngine` methods exist and are correct" | `TtsEngine` called `TextToSpeech.setVolume()`, which does not exist, and `math.max` instead of `kotlin.math.max` |
| "Duplicate prevention … **Fully verified**" | `DedupRoomImpl` did not compile (invalid `Migration(1L)` usage, missing imports) |
| "Room DAO exists … `getRecent()`" | The DAO used MySQL syntax (`date_sub(curdate(), interval 30 day)`) which is invalid in SQLite, and imported the dead `android.arch.persistence.room` package |
| "Debug APK builds **would** succeed in a proper Android Studio environment (Gradle native lib issue in this session only)" | The project was not a valid Gradle Android project at all: no `app/build.gradle.kts`, no wrapper jar, no `res/`, no app manifest |
| "SQL RLS intact … Fully verified" | Only a static read of the SQL file; no database ever executed it |

Claims of the form "code exists, therefore verified" were the core error. A file that does not
compile cannot support any verification claim.

---

## Standing rule for this project

A status may only be raised when the evidence type supports it:

- **unit test** for pure logic (parsers, dedup keys, formatting, money maths, export shape),
- **compiled** for anything the Room validator and Kotlin compiler accept,
- **runtime** for anything claimed to work on a device — with the command and its output recorded,

and never "verified" from the mere presence of a source file.

---

## Where PayAlert stands now

The authoritative per-feature table is **[FEATURE_MATRIX.md](FEATURE_MATRIX.md)**; the exact
commands and raw output behind every status are in **[BUILD_AND_VERIFICATION.md](BUILD_AND_VERIFICATION.md)**.

| Area | Status |
|---|---|
| SMS detection → parse → dedup → Room → TTS | **verified at runtime** |
| Notification-listener detection | **verified at runtime** |
| Cross-detector duplicate suppression (and across restarts) | **verified at runtime** |
| Foreground monitor service | **verified at runtime** |
| Config-driven parser (42 banks / 5 wallets / 95 sender ids) | **verified on device + 25 unit tests** |
| History: list, detail, search, direction/date/account filters, totals, CSV/JSON export | **verified at runtime** |
| Analytics: range presets, category bars, top payees, daily in/out, summary export | **verified at runtime + 7 unit tests** |
| Cashbook: create, edit, delete, categories, date range, report, export button | **verified at runtime + 4 unit tests** |
| Upcoming payments: save, mark paid, monthly roll-forward, alarms | **verified at runtime + `dumpsys alarm`** |
| Dedup pruning (3-day window) | **verified at runtime with a seeded stale key** |
| Minimum-amount filter (both directions, via the Settings field) | **verified at runtime** |
| Reboot recovery (monitor + reminder alarms) | **verified across a real `adb reboot`** |
| Launch-time service health check (reference `startServiceHealthChecks`) | **verified at runtime + 8 unit tests** |
| Rating prompt (review-prompt equivalent) | **verified at runtime, all three outcomes + 6 unit tests** |
| Backup & restore (export, import, skip accounting, atomic) | **verified at runtime** |
| Payment QR generation (UPI link) | **verified by round-trip test + on-device render**; scanning not applicable |
| Home-screen widget | **verified on the launcher with live, self-refreshing content** |
| Help & FAQ, Privacy & terms, Diagnostics pipeline test, permission deep links, Settings toggles changing behaviour | **verified at runtime** |
| Unlock-triggered restart, speech speed/pitch/volume, chime & vibration | implemented and compiled, **not yet exercised** (unlock has a recorded environmental blocker) |
| Balance-only tracking, mandate/autopay parsing | **partial / missing** |
| Team/pairing | UI/client + SQL implemented; JVM, emulator Compose and local PostgreSQL tests pass; live Google/Supabase integration not verified |
| Monetization, ads, attribution, third-party identity SDKs | **excluded by requirement** (`not applicable`) |

---

## Reference-vs-PayAlert diff — remaining mismatches

Every row below is a real, inspected difference that still stands. "Intentional" marks the three
allowed deviations.

| Reference item | Reference evidence | PayAlert | Verdict |
|---|---|---|---|
| Camera QR **scanner** | `ui.scanner.QRScannerActivity` + JourneyApps barcode SDK | Generation only; the UPI link can be copied or opened in a UPI app | **Not applicable by requirement** — the camera was excluded from the rebuild, and a capture cannot be driven on the headless AVD (its emulated camera cannot be aimed at a QR code), so no status could be backed by evidence. The dependency itself was not the blocker: Maven Central is reachable from this workspace |
| Mandate / autopay parsing | `ParsedSms.MandateActivation`, `MandateRevocation`, `MandateInfo` | Mandate messages are skipped as non-payments | **Gap** — not modelled |
| Balance-change history | `ParsedSms.BalanceUpdate` | Balance quoted on a payment is stored; balance-only alerts are skipped | **Gap** |
| TTS backup service | `services.TtsBackupService` | One engine, warmed by the foreground monitor and queued until ready | **Intentional simplification** |
| Three separate SMS/notification services | `BankSmsReceiver`, `SmsCreditFallbackService`, notification listener | One shared `PaymentPipeline` behind both detectors | **Intentional simplification** — identical outcomes, one dedup path |
| Custom sound files | 7 MP3s in `assets/`, 4 in `res/raw/` | Chime generated with `ToneGenerator`; no assets copied | **Intentional deviation** — no copied proprietary assets |
| Google sign-in + cloud sync | Firebase Auth + Firestore | Optional Google browser PKCE for team metadata; local payment features remain account-free | Live OAuth not verified; payment sync remains unbuilt |
| Team / device pairing UI | `ui.team.TeamActivity` | Settings → Team & devices: secure single-use invites, join/list/rename/remove/leave with Supabase RPCs | UI/client/SQL checks pass in isolation; live two-phone flow not verified; no payment forwarding |
| Referrals, more-apps cross-promotion | `ui.referral.*`, `ui.apps.MoreAppsActivity` | Absent | **Intentional** — growth features of the reference's business |
| Subscription / manage subscription / Razorpay / Play Billing / `pairip` licence check | `SubscriptionActivityNew`, `ManageSubscriptionActivity`, Razorpay activities | Absent; the monetization sweep is clean (the only `purchase` hit is a debit *keyword* in the parser rules) | **Intentional deviation** (allowed) — every Pro feature is free |
| Ads / attribution / Truecaller | AdMob, AdServices, install-referrer, Truecaller SDK | Absent | **Intentional deviation** |
| In-app WebView for terms | `ui.WebViewActivity` | Native Compose screen | **Intentional rebuild** — no WebView engine needed |
| Splash screen | `ui.auth.SplashActivity` | `MainActivity` paints immediately | **Intentional** — no artificial delay |
| `MainActivity.startServiceHealthChecks` + `services.boot.ServiceHealthWorker` (WorkManager) | both present in the DEX | Re-implemented as a launch-time inspection + silent repair in `MainActivity.onStart`, with a visible banner when something is still broken | **Intentional simplification** — the same job with no WorkManager dependency; it also re-arms reminder alarms, which the reference did not |
| In-app review / rating prompt | no Play Core `ReviewManager` in the DEX | A local rating prompt gated on 5 announced payments, with a 30-day cooldown and permanent opt-out, opening the store listing | **Equivalent behaviour rebuilt locally** — the Play flow needs Play Services on a Play-installed device; this is testable and nags less |
| Widget configuration activity | `widget.WidgetConfigActivity` | Widget with sensible defaults; the UPI id lives on the QR screen | **Gap** (minor) |

## Defects this audit found and fixed

| Found by | Defect | Fix |
|---|---|---|
| Unit test | `detectKind` used substring matching, so `"sent to"` never matched `"Sent Rs.150 to …"` | Word-boundary matching for single-token keywords |
| Runtime (import) | Restore aborted with `UNIQUE constraint failed: cashbook_entries.id`, leaving the import half-applied | Dedicated ignore-on-conflict restore inserts + the whole import wrapped in one Room transaction; the result now reports what actually landed and what was skipped |
| Runtime (analytics) | "Top payees" grouped by **category**, so every row read `expense` | Payees are grouped by merchant (falling back to bank), with a regression test |
| Runtime (analytics) | "Daily movement" summed credits and debits together, which is not a meaningful figure | Daily totals now show money in and money out separately |
| Runtime (accessibility) | Settings switches were unlabelled nodes with no toggle state exposed | Each row is a single labelled switch control (`toggleable` + merged semantics) with a full-width tap target |
| Runtime (UX) | History offered two different chips both labelled "All" | Direction chip reads "Both"; the range chip reads "All time" |
| Runtime (QR) | The generated code had a 1-module quiet zone and was drawn on a dark card in dark mode | 4-module quiet zone (spec-compliant) and a white backdrop; asserted by a test |
| Static review | `.env.example` claimed Supabase was "required for auth, device tracking, event sync" | Corrected: no credentials are required; the cloud schema is explicitly optional |
| Static review | `supabase/schema.sql` began with `DROP TABLE … CASCADE` | Rewritten as an idempotent, non-destructive migration; the destructive reset is commented out and clearly labelled |
| Runtime (Settings) | The minimum-amount field re-seeded its text from settings on every keystroke, so typing `1500` produced `1.0500…` — the threshold could not be entered by hand | The field keeps its own text and only the parsed value is stored; it also renders `1000` rather than `1000.0`. Verified by typing `1500` one character at a time with a recomposition between keys |
