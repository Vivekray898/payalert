# PayAlert — Feature Parity Matrix (evidence-based)

**Reference:** `Auto Alert v2.4.5` — package `com.binaryscript.payalert`, versionCode 315,
minSdk 24, targetSdk 36 (read from `.apk_extract/manifest.xml` and the DEX symbol tables).
**Target:** `PayAlert` — package `com.vivekray898.payalert`.
**Monetization:** none. Every feature below is free; there are no paywalls, trials or licence checks.

> **Correction notice.** An earlier revision of this file claimed many features were "Done"
> that did not exist in the repository (no `MainActivity`, no `res/`, no `app/build.gradle.kts`,
> and every Kotlin file failed to compile). Those claims are withdrawn. Statuses below are
> limited to what was actually inspected, compiled, unit-tested, or executed on a device.

## Status vocabulary

| Status | Meaning |
|---|---|
| `implemented and verified` | Real code, compiled, and exercised by an automated test or a runtime check |
| `implemented but unverified` | Real code that compiles and is reachable, but has not been executed |
| `partial` | Some real behaviour, with a named gap |
| `missing` | No implementation |
| `not applicable` | Cannot exist on Android, or deliberately excluded |

## How each status was established

- **source-inspected** — the reference artefact was read (extracted manifest, resources, DEX symbols).
- **compiled** — `./gradlew clean :app:assembleDebug` succeeded (12,506,521-byte debug APK produced).
- **unit-tested** — `make test` ran **141 tests, 0 failures** (17 suites, including
  42 pairing/client/controller tests). Pairing's four Compose tests also passed on Android 14;
  see `BUILD_AND_VERIFICATION.md` §14 for scope and the live-integration gap.
- **schema-verified** — Room's KSP processor validates every `@Query` SQL string at compile time;
  an unknown column or invalid SQL fails the build. Every DAO query compiled.
- **manifest-verified** — component declarations read back out of the *built* APK with `aapt2`.
- **runtime** — driven on a Pixel 8 Pro AVD, Android 14 (API 34), with the real debug APK
  installed; the exact commands and output are recorded in `BUILD_AND_VERIFICATION.md`.

---

## 1. Detection

| # | Feature | Reference evidence (APK) | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|
| 1.1 | Notification-listener detection | `services.notifications.PaymentNotificationListenerService` in DEX | `implemented and verified` | **Runtime:** listener bound and live (`dumpsys notification`), a posted notification parsed and stored with `source=notification`, `sourceDetail=com.android.shell`, bank inferred as `PhonePe` | — |
| 1.2 | Bank SMS detection | `SmsBroadcastReceiver`, `services.passbook.BankSmsReceiver` | `implemented and verified` | **Runtime:** two real SMS deliveries produced `credit 5000.00 ACME STORE / XX1234 / HDFC Bank / sms` and `debit 2000.00 bigbasket@ybl` | — |
| 1.3 | SMS-as-fallback for missed notifications | `services.passbook.SmsCreditFallbackService` (declared FGS subtype) | `implemented and verified` | Both detectors call the same `PaymentPipeline.handle`, so either alone announces; the reference's three separate services are collapsed into one pipeline | — |
| 1.4 | Foreground monitor service | `services.PaymentMonitorService` | `implemented and verified` | **Runtime:** `isForeground=true foregroundId=1001 types=40000000` (special-use), channel `payalert_monitor`, `START_STICKY` | — |
| 1.5 | Restart after reboot | `services.boot.BootReceiver` | `implemented and verified` | **Runtime (real `adb reboot`, app never launched afterwards):** `PaymentMonitorService` came back as a foreground service (`isForeground=true foregroundId=1001 types=40000000`) and the pending reminder alarm was re-armed for `2026-11-15 09:00` | — |
| 1.6 | Restart on unlock | `services.boot.ServiceRestartReceiver` | `implemented but unverified` | The receiver is **registered with the system** for `USER_PRESENT` (`dumpsys package` shows `…/.services.ServiceRestartReceiver filter` under that action), and a genuine unlock was performed on the device (keyguard engaged, PIN accepted) | The *restart of a dead monitor* could not be exercised: the shell may not kill a foreground-service process (`am kill` refuses it, `kill -9` → `EPERM`, `am stop-service` denied) and `am force-stop` puts the package in the stopped state, which suppresses implicit broadcasts. See `BUILD_AND_VERIFICATION.md` §9.6 |
| 1.7 | TTS backup service when throttled | `services.TtsBackupService` | `not applicable` | The engine is warmed by the foreground monitor; `speak()` queues the pending utterance until the engine reports ready | Single engine by design |
| 1.8 | FCM push | `services.firebase.AutoAlertFirebaseMessagingService` | `not applicable` | — | Reference used push for its backend; PayAlert is local-first with no server |
| 1.9 | Scheduled reminders | `SCHEDULE_EXACT_ALARM` in manifest | `implemented and verified` | **Runtime:** `dumpsys alarm` shows `*walarm*:…/.services.ReminderReceiver` `RTC_WAKEUP origWhen=2026-10-14 09:00:00` for a saved reminder | Uses `setAndAllowWhileIdle`, which survives doze without the exact-alarm permission; timing may be inexact |

## 2. Parsing

| # | Feature | Reference evidence | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|
| 2.1 | Rules shipped as data | `assets/parser_config.json` (92 KB) | `implemented and verified` | `assets/parser_config.json` loaded on device — Home and Diagnostics report **42 banks / 5 wallets / 95 sender ids**; `ParserConfigLoader` unit-tested by `ParserConfigTest` | Rule *schema* is PayAlert's own; coverage scope informed by the reference |
| 2.2 | Typed rule engine | `com.binaryscript.parser.core` (82 classes) | `implemented and verified` | `ParserConfig` + `ParserRegistry` are fully config-driven (no bank literals in code); skip/direction/merchant/currency rules are typed data | No per-bank object hierarchy — rules are one shared engine plus data |
| 2.3 | Credit parsing | `ParsedSms.Transaction` + `TransactionType` | `implemented and verified` | Unit-tested **and runtime**: HDFC-style credit from a real SMS *and* from a real notification (amount, `XX1234`, UTR, bank) | — |
| 2.4 | Debit parsing | same | `implemented and verified` | **Runtime:** UPI debit SMS → `debit 150.00 zomato@ybl`; shell notification → `debit 250.00 swiggy@ybl PhonePe`; both stored | — |
| 2.5 | Skip non-payments | `SkipReason` enum | `implemented and verified` | Unit-tested (OTP, EMI-due, balance-only, promo); **runtime:** a non-payment notification produced `no_match` and stored nothing | Marker lists are an extensible starting set |
| 2.6 | Amount extraction | `CompiledPatterns.Amount` | `implemented and verified` | Unit-tested for `Rs`, `Rs.`, `INR`, thousands separators; runtime `Rs.5,000.00` → `5000.0` | — |
| 2.7 | Reference/UTR extraction | `CompiledPatterns.Reference` | `implemented and verified` | Unit-tested labelled `UTR`/`Ref` plus bare 12-digit fallback; runtime `412345678901`, `998877665544` | — |
| 2.8 | Account masking | `CompiledPatterns.Account` | `implemented and verified` | Runtime `XX1234` / `XX4321` extracted and stored; drives the account filter | — |
| 2.9 | Merchant extraction | `CompiledPatterns.Merchant` | `implemented and verified` | Runtime `ACME STORE`, `zomato@ybl`, `swiggy@ybl`, `bigbasket@ybl`; stop-words, leading-label stripping, VPA and UPI-narration forms | No merchant-name dictionary |
| 2.10 | Balance-update messages | `ParsedSms.BalanceUpdate` | `partial` | The quoted balance *on a payment* is extracted into the `balance` column (shown in transaction detail); a balance-**only** message is still skipped as `no_match` | No balance history / balance-change tracking |
| 2.11 | Mandate / autopay parsing | `ParsedSms.MandateActivation`, `MandateRevocation`, `MandateInfo` | `missing` | Mandate messages are skipped as non-payments | Not modelled |
| 2.12 | Bank-specific rules | `CompiledPatterns.HDFC` | `implemented and verified` | Bank table (senders + keywords + catch-all) is data; `HDFCBK` → HDFC Bank, `VK-ICICIB` → ICICI Bank observed at runtime | Not one hand-written rule set per bank |
| 2.13 | Bank/app identification | inferred from sender id | `implemented and verified` | Runtime: `HDFC Bank`, `PhonePe`; unit-tested sender-prefix and package matching | — |
| 2.14 | Currency detection (non-INR) | reference currency handling | `implemented but unverified` | Config-driven currency table with generated foreign-amount patterns; `detectCurrency` unit-tested | No foreign-currency alert was fed in |

## 3. Duplicate prevention

| # | Feature | Reference evidence | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|---|
| 3.1a | Suppress duplicates: same detector, same source | log de-dup implied by `checkAndSyncNotificationLogsInBackground` | `implemented and verified` | `DedupKeyBuilder.exactKey` + `dedup_index`; unit-tested, and **runtime:** notification then SMS of one payment → `duplicate`, one row | — |
| 3.1b | Suppress duplicates: cross-app semantic (UPI apps) | — | `implemented and verified` | **Runtime on the notification path** (the SMS path is 3.1a): three stub APKs posting as `com.phonepe.app` / `net.one97.paytm` / `com.google.android.apps.nbu.paisa.user` — three wordings of the same ₹500 credit to `zomato@ybl`, three different UTRs so only the semantic tier could collapse them — produced parser decisions `accepted / duplicate / duplicate`, **1 `payment_events` row**, 1 distinct semantic key, and 1 TTS utterance (raw commands and DB rows in `BUILD_AND_VERIFICATION.md` §10.6–10.7). Unit: `DedupKeyBuilderTest` semantic suite + `PaymentPipelineTest` Test A | The bucket is 5 minutes; a same-amount payment inside the bucket with a different reference is treated as the same payment |
| 3.2 | Dedup survives restart | — | `implemented and verified` | **Runtime:** keys persisted in Room across process restarts/reinstalls; a repeated SMS was still suppressed afterwards | — |
| 3.3 | Configurable dedup window | — | `implemented and verified` | Unit-tested window boundary (default 5 min); adjustable 1–30 min in Settings and applied by the pipeline | — |
| 3.4 | Old key pruning (exact keys, 3-day window) | — | `implemented and verified` | **Runtime:** a row seeded with `seen_at = now − 10 days` was present before a payment was processed and **gone** afterwards, while a row from `now − 1 h` and the new key survived (`pruneBefore(now − 3 days)` runs after each accepted event) | — |
| 3.5 | Dedup check-and-insert serialization (Mutex) | — | `implemented and verified` | `PaymentPipeline` wraps the dedup check + insert in a `Mutex`, TTS outside the lock. **Unit:** `PaymentPipelineTest` Test B runs three `async(Dispatchers.Default)` handles of the same payment against a store with 100 ms checks and 50 ms writes and asserts exactly 1 `accepted` / 2 `duplicate` / 1 stored event / 1 announcement; a **control test** on the identical store with three separate (unshared-mutex) pipelines asserts 3 `accepted`, so the race window is proven real and the mutex is what closes it (`BUILD_AND_VERIFICATION.md` §10.3, XML `tests="6" failures="0"`). **Runtime:** the three-notification run (§10.6) exercised the same code path on-device | — |
| 3.6 | Semantic key pruning (10-minute window) | — | `implemented and verified` | `pruneSemanticBefore(now − 10 min)` runs after each accepted event. **Unit:** `PaymentPipelineTest` Test D seeds a semantic row and an exact row at `now − 11 min`, runs a payment, and asserts the semantic row is pruned while the exact row survives. **Runtime:** the same seed was pushed into the device DB; after an accepted payment the semantic row was gone and the exact row survived (`BUILD_AND_VERIFICATION.md` §10.9). This check exposed and fixed a real bug: the old `semantic_key IS NOT NULL` predicate also deleted exact keys at 10 minutes, defeating their 3-day window (3.4) | — |

## 4. Announcement

| # | Feature | Reference evidence | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|
| 4.1 | Speak payments via TTS | `FOREGROUND_SERVICE_SPECIAL_USE` subtype mentions announcements | `implemented and verified` | **Runtime:** an accepted payment triggered `Synthesis request for locale eng-IND`; the Diagnostics screen also shows the exact sentence spoken | Audibility not assessed (headless emulator) |
| 4.2 | Language selection | 10 language labels (verified with `aapt2 dump strings`: Bengali, English, Gujarati, Hindi, Kannada, Malayalam, Marathi, Punjabi, Tamil, Telugu) + `TtsSettingsActivity` | `implemented and verified` | **Runtime:** the persisted default `en-IN` was the locale the platform engine used; the picker offers the same 10 locales and speaks a confirmation on change | Native voice availability depends on the device |
| 4.3 | Speech speed / pitch | `TtsSettingsActivity` | `implemented but unverified` | Sliders persist and are applied via `setSpeechRate`/`setPitch` | Not heard |
| 4.4 | Volume | `TtsSettingsActivity` | `implemented but unverified` | Slider persists; applied via `KEY_PARAM_VOLUME` (TextToSpeech has no `setVolume`) | Not heard |
| 4.5 | Custom announcement format | announcement customisation | `implemented and verified` | `AnnouncementFormatter` with `{amount} {merchant} {bank} {kind}`, per-direction templates with a live preview; 4 unit tests | — |
| 4.6 | Custom announcement sounds | Reference sound resources | `implemented and verified` | The supplied `kaching-sound-effect.mp3` is bundled as `res/raw/kaching.mp3`; runtime logcat shows `chime_loaded resource=kaching`, volume-backed starts, and completion for payment and preview paths (`BUILD_AND_VERIFICATION.md` §12) | Audibility cannot be confirmed by me; check by ear is required |
| 4.7 | Per-direction announcement toggle | — | `implemented and verified` | **Runtime:** switching "Money received" off made the real pipeline return `kind_disabled` for a credit; switching it back on restored `accepted` | — |
| 4.8 | Minimum-amount filter | — | `implemented and verified` | **Runtime, through the Settings field itself:** threshold `1000` → a `Rs.600` credit returned `below_minimum` and stored nothing; a `Rs.1500` credit in the same session was stored. Clearing the field to `0` and re-sending the ₹600 message then stored it — the same message, opposite outcome | Entry field was uneditable (re-seeded from settings on every keystroke); fixed in this pass |
| 4.9 | Sound modes + vibration | `TtsSettingsActivity` | `implemented and verified` | Four emulator SMS-payment captures show mode decisions under `PayAlertSound`; silent mode logs vibration only when enabled, voice-only logs speech only, and both chime modes log the configured vibration/chime transitions (`BUILD_AND_VERIFICATION.md` §12) | Audibility and physical vibration cannot be confirmed by me; check by ear and by feel are required |
| 4.10 | Preview on Sound chip selection | Settings sound selector | `implemented and verified` | Four chip-tap logcat captures have `preview=true`, with no payment-pipeline transition in the preview path (`BUILD_AND_VERIFICATION.md` §12) | Audibility cannot be confirmed by me; check by ear is required |
| 4.11 | Chime-then-voice chaining | `TtsSettingsActivity` | `implemented and verified` | Five `CHIME_AND_SPEECH` SMS captures show `speak_audio_start - chime_end` gaps of 24, 25, 25, 26 and 24 ms; median 25 ms, worst 26 ms. The raw sequence and timestamps are in `BUILD_AND_VERIFICATION.md` §13 | Audibility cannot be confirmed by me; check by ear is required |

## 5. Data, history and cashbook

| # | Feature | Reference evidence | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|
| 5.1 | Transaction history | `ui.transactions.TransactionsActivity` | `implemented and verified` | **Runtime:** every stored payment rendered with merchant, timestamp, source, account and signed amount | — |
| 5.2 | Transaction detail | `TransactionsActivity` | `implemented and verified` | **Runtime:** tapping a row opened the detail dialog with amount + currency, bank/app, merchant, reference, account, balance, category, detector, source and raw message | — |
| 5.3 | Search transactions | — | `implemented and verified` | **Runtime:** searching `zomato` reduced the list to `1 shown` with `Out: 150.00`; matches merchant, bank, reference and raw text in SQL | — |
| 5.4 | Filter by direction | — | `implemented and verified` | **Runtime:** the `Out` chip produced `In: 0.00 / Out: 400.00 / 2 shown` | — |
| 5.5 | Running totals | `TransactionAnalyticsActivity` | `implemented and verified` | **Runtime:** header totals follow the active filter | — |
| 5.6 | Date-range filter on history | filter sheet in reference | `implemented and verified` | **Runtime:** `All time / 7 days / 30 days / This month` chips; range applied in SQL | Custom start/end picker not offered |
| 5.7 | Account filter | — | `implemented and verified` | **Runtime:** account chips are derived from stored data (`Any account`, `XX1234`) | — |
| 5.8 | Export history | export in reference | `implemented and verified` | **Runtime:** Export → CSV wrote `payalert-history.csv` (279 bytes) through the system share sheet; file content read back and matched the filter exactly; JSON offered too | — |
| 5.9 | Cashbook create | `ui.cashbook.AddEditCashBookEntryActivity` | `implemented and verified` | **Runtime:** entry `Chai / 40 / Food` persisted to `cashbook_entries` (read back from Room); empty-form Save rejected with `Enter a label` | — |
| 5.10 | Cashbook delete | same | `implemented and verified` | **Runtime (earlier pass):** Delete removed the row and the UI returned to the empty state | — |
| 5.11 | Cashbook edit | same | `implemented and verified` | **Runtime:** Edit → changed `40` to `99` → Save. Room read back `1\|Chai\|debit\|99.0\|Food` with the id, label, category and date unchanged, and the UI showed `Balance: -99.00` | — |
| 5.12 | Cashbook totals & balance | — | `implemented and verified` | **Runtime:** after insert `Out: 40.00 / Balance: -40.00`; after delete back to `0.00` | — |
| 5.13 | Cashbook categories | category support in reference | `implemented and verified` | **Runtime:** the category chip `Food` appeared from stored data and filtered the list | — |
| 5.14 | Cashbook date-range report | report screen in reference | `implemented and verified` | **Runtime:** the Report dialog showed `Money in 0.00 / Money out 40.00 / Net -40.00 / Entries 1` plus per-category `Food in 0.00 · out 40.00`; maths unit-tested (4 tests) | — |
| 5.15 | Cashbook export | — | `implemented and verified` | **Runtime:** tapping the Cashbook **Export** button opened the system share sheet with `payalert-cashbook.csv`; the cached file contained `occurred_at,direction,amount,label,category,note` plus the **edited** row `2026-10-13 22:59,debit,99.00,Chai,Food,`; builder unit-tested | — |
| 5.16 | Transaction analytics screen | `ui.analytics.TransactionAnalyticsActivity` | `implemented and verified` | **Runtime:** totals, category bars, top payees and daily in/out rendered from real data; range presets switch; summary CSV export | No chart library — proportional bars only |
| 5.17 | Upcoming payments / passbook | `ui.passbook.UpcomingPaymentsActivity`, `PassbookSmsScanService` | `implemented and verified` | **Runtime:** a non-repeating reminder (`Electricity / 1500 / Bills`) was created with a live alarm; **Mark paid** set `is_paid=1` and the `dumpsys alarm` batch for the package dropped to zero. A monthly reminder (`Broadband / 799`, switch toggled in the dialog) scheduled `2026-10-15 09:00`; **Mark paid & repeat** moved `due_at` to `2026-11-15 09:00`, kept `is_paid=0` and moved the alarm to the same new date | — |
| 5.18 | Backup & restore | backup/restore in reference | `implemented and verified` | **Runtime:** export wrote a 2,629-byte JSON (4 payments + 1 cashbook + 1 reminder) to Downloads via the system picker; after clearing, the same file restored 4 payments and correctly reported the 2 records already present as skipped | Settings/speech prefs deliberately excluded |

## 6. Other reference features

| # | Feature | Reference evidence | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|
| 6.1 | Notification access + SMS + battery permission handling | manifest permissions + `services.PermissionService` | `implemented and verified` | **Runtime:** Home, Settings and Diagnostics reported live system state. Both deep links were tapped: notification access opened `com.android.settings/.Settings$NotificationAccessSettingsActivity`; with the exemption revoked, the battery button opened `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`, whose `Allow` re-added the package to the device-idle whitelist | — |
| 6.2 | Diagnostics / pipeline test | `ui.KeywordCheckActivity`, `ui.PipelineTestActivity` | `implemented and verified` | **Runtime:** the on-screen "Run through pipeline" fed text through the real pipeline and reported `accepted`, parsed amount, direction, merchant, dedup key, rule set (`config-v1`) and the spoken sentence — and stored the row | — |
| 6.3 | Payment QR code | `ui.scanner.QRScannerActivity` + barcode scanner | Generation `implemented and verified`; scanning `not applicable` | Generation: the UPI link **round-trips through zxing's decoder in a unit test**, the quiet zone is standards-compliant, and the code renders on device (720×720 image with the correct URI shown as text). Scanning: excluded by the project requirement, and a camera capture cannot be exercised on the headless AVD (its emulated camera cannot be pointed at a QR code), so no status could be supported by evidence | The payee can be typed, copied as a UPI link or opened in a UPI app instead — see §9.7 of `BUILD_AND_VERIFICATION.md` |
| 6.4 | Home-screen widget | `widget.WidgetConfigActivity`, `widget.UpiQrWidgetReceiver` | `implemented and verified` | **Runtime:** placed on the launcher and rendering live content — `In 6,250.00 · Out 400.00 (this month)` and the latest payment; it refreshed itself after a new SMS arrived | No widget configuration activity |
| 6.5 | FAQ screen | `ui.FaqsActivity` | `implemented and verified` | **Runtime:** Help & FAQ rendered six Q&A cards | — |
| 6.6 | In-app terms / privacy | `ui.WebViewActivity` | `implemented and verified` | **Runtime:** a native Privacy & terms screen (no WebView needed) covers local data, permissions, no-subscription and no-affiliation | Rebuilt natively rather than as a WebView |
| 6.7 | Team / device management | `ui.team.TeamActivity` | `implemented and verified` | Settings → Team & devices: owner invite/copy/cancel, employee join, names, list refresh, remove/leave, role badge, forwarding counters; 53 JVM tests, local PostgreSQL authorization/readback suite. **Runtime (2026-10-17):** owner = `emulator-5554` (account A `dbd4766f-…`, device `8b455841-…`) and employee = physical `M2101K6I` (account B `9bc0fd7e-…`, device `8584d44c-…`) signed in to two different accounts simultaneously. Pairing resolved on both sides: owner pref `local_device_role=OWNER`, employee pref `local_device_role=EMPLOYEE` + `paired_owner_device_id=8b455841-…`. `team_register_device` returned `employees=[{device_id:8584d44c-…, pairing_id:18cedda9-…}]` to the owner and `owner={device_id:8b455841-…}` to the employee. Both relay keys registered in `relay_private.device_keys` (owner `684d6b01-…`, employee `f86d249d-…`, 112 bytes each). A live cold start of both apps produced no `FATAL EXCEPTION`, and the employee logged `Background started FGS: Allowed … EmployeeRealtimeService` | Live Google/Supabase/two-phone pairing exercised end to end. Still unexercised: remove/leave, invite expiry, and recovery from a key rotation while a message is queued |
| 6.8 | Encrypted owner → employee forwarding | `relay.OwnerForwarder`, `relay.EmployeeReceiver`, `relay.RelayCrypto` | `implemented and verified` | Credit-only forwarding with a durable retry queue, 24-hour employee retention, AES-256-GCM envelopes from on-device X25519 keys, role-based listening, re-pair recovery; JVM relay suite and relay RPC/RLS SQL suite. **Runtime (2026-10-17 00:06:08–00:06:15):** an SMS to the owner (`Rs.555 credited … AMAZON@ybl. UTR 260003332222`) produced `PayAlertRelay owner_enqueued message_id=230ec8ad-…`; the owner queue row ended `delivered` with a 480-character envelope. Server counters for the run: `relay_bind_peer_key` 1, `relay_enqueue` 2, `relay_fetch` 12, `relay_ack` 2, `peer_key_bindings` 1. The employee logged `PayAlertRelay employee_ws_received message_id=230ec8ad-…` then `employee_announced …` and spoke it (`PayAlertSound speak_start` → `speak_audio_start`). **Announcement wording (same day, later pass):** the spoken sentence is now unprefixed and uses spoken rupee words — re-verified on both devices as `PayAlertRelay employee_announced text=Payment received. 321 rupees from MYNTRA@ybl.` (see BUILD_AND_VERIFICATION §18). The employee stored one `received_announcements` row (`status=processed`, `decrypt_failed=0`, `amount=555.0`, `currency=INR`, `merchant=AMAZON@ybl`) and `payment_events` remained 0 — a relayed payment is never adopted as the employee's own. Retry recovery also observed: the earlier `key_not_bound` row `3a8b29f5-…` was delivered on attempt 3 and announced (with the wording in force at that moment: `1,234.00 from SWIGGY@ybl`). Measured end to end: 6.6 s from event to the employee's spoken audio, dominated by the 5.7 s delivery hop | Key binding is server-brokered, so an actively malicious server at pairing time is not detected. The `postgres_changes` WebSocket path is deliberately not used — see 6.17. FCM push remains unavailable (see 17.4) |
| 6.8 | Referrals | `ui.referral.ReferralActivity` | `not applicable` | — | Growth feature tied to the reference's backend |
| 6.9 | More-apps cross-promotion | `ui.apps.MoreAppsActivity` | `not applicable` | — | Deliberately excluded |
| 6.10 | Google sign-in | Firebase Auth activities | `implemented but unverified` | Optional browser PKCE Supabase client, encrypted session restoration/refresh, callback routing and sign-out; protocol/request behavior JVM-tested | Live Google OAuth not exercised; local features still require no account |
| 6.11 | Subscription / manage subscription | `SubscriptionActivity`, `ManageSubscriptionActivity`, Razorpay, Play Billing, `pairip.licensecheck` | `not applicable` | Monetization grep sweep is clean for the app: the only hit is the word `purchase` as a **debit keyword** in the parser rules; the Terms screen states the policy | Removed by requirement |
| 6.12 | Ads / attribution / Truecaller | AdMob, AdServices, install-referrer, Truecaller SDK | `not applicable` | No such dependency in `app/build.gradle.kts` | Deliberately excluded |
| 6.13 | Splash screen | `ui.auth.SplashActivity` (launcher) | `not applicable` | `ui.MainActivity` is the launcher and paints immediately | No artificial delay screen |
| 6.15 | Launch-time service health check | `MainActivity$startServiceHealthChecks`, `services.boot.ServiceHealthWorker` | `implemented and verified` | `ServiceHealthEvaluator` (8 unit tests) + `ServiceHealth.inspect/repair` run from `MainActivity.onStart`. **Runtime:** killing the app so the monitor was down produced the banner *"Detection repaired on launch: started payment monitor, re-bound notification listener, re-armed 1 reminder(s)"*; revoking notification access instead produced *"Detection needs attention: notification access"* with a working **Fix** deep link | Advisory checks (battery) are reported but never block |
| 6.16 | Rating prompt (review-prompt equivalent) | no Play Core `ReviewManager` in the reference DEX; a local prompt is the equivalent behaviour | `implemented and verified` | `ReviewPromptPolicy` (6 unit tests) + a Compose dialog shown from `MainActivity`. **Runtime, all three outcomes:** *Later* → `prompt_count=1` and no re-prompt on relaunch; *Rate on Play* → `rated=true` and the store opened on the app's listing (`com.android.vending/…UnauthenticatedMainActivity`); *No thanks* → `dismissed=true` and permanently suppressed | Play Core's In-App Review flow was not added (needs Play Services on a Play-installed device); the store deep link is used instead |
| 6.17 | Employee-side real-time relay pump | `relay.EmployeeRealtimeService` | `implemented but unverified` | Foreground service started by `MainActivity.onStart` and `BootReceiver` when `role == EMPLOYEE`; runs `runRelayPump` on a 10-second cadence (foreground) plus `RelayPumpScheduler` (15-minute WorkManager floor). Compiled and manifest-verified (`aapt2` dump xmltree shows the service with `foregroundServiceType="dataSync"`). **Not exercised:** no second device to observe a live pump | Only one emulator attached (`emulator-5554`) |
| 6.18 | FCM wake-up fallback | `services.PayAlertMessagingService`, `relay.RelayMessageFcmWork`, `relay.RelayUpdateFcmTokenWorker`, `supabase/functions/relay-notify/index.ts` | `implemented but unverified` | FCM BOM + messaging-ktx added to `app/build.gradle.kts`; the service handles `type=relay_message` (with `message_id` only — never the envelope) and `type=pairing_changed`; the Edge Function is deployed to project `aqwstxagvicucgfssnac`. **Not exercised:** no Firebase project configured (`app/google-services.json` is a placeholder), no push received on a device | No Firebase project; no second device |
| 6.19 | Cross-channel dedup | `relay.ProcessedRelayMessage`, `relay.ProcessedRelayMessageDao`, `PayAlertDatabase.RELAY_PROCESSED_MIGRATION` (v6→v7) | `implemented but unverified` | Room table created with the v7 migration; `INSERT OR IGNORE` makes a duplicate arrival a no-op; a 7-day prune index exists. **Not exercised:** no duplicate arrival observed | No second device |
| 6.20 | Realtime publication on the relay table | `supabase/migrations/004_realtime_relay.sql` | `implemented and verified` | `supabase db push` applied the migration; `supabase db query --linked` confirms `relay_private.envelopes` is in `supabase_realtime` with `replica identity full` | — |
| 6.21 | FCM token column | `supabase/migrations/005_fcm_token.sql` | `implemented and verified` | `devices.fcm_updated_at timestamptz` now exists (verified by query against the linked project) | — |
| 6.14 | Diagnostics for rule coverage | — | `implemented and verified` | **Runtime:** Diagnostics and Settings both report the live rule-set size (42 banks · 5 wallets · 95 sender ids) and flag an asset-load failure instead of hiding it | — |

## 7. UI/UX

Statuses here follow the same rule as everywhere else in this file: **`implemented and
verified`** requires a rendered frame on disk, and the path is cited. Every frame below was
captured with `adb exec-out screencap -p` on the `Pixel_8_Pro` AVD (Android 14 / API 34,
1344×2992 @ 480 dpi) from the APK produced by the `clean :app:assembleDebug` run recorded in
`BUILD_AND_VERIFICATION.md` §11.

| # | Screen | Reference evidence (APK) | PayAlert status | Evidence | Gap |
|---|---|---|---|---|---|
| 7.1 | Home | `ui.MainHomeActivity` | `implemented and verified` | `screenshots/after/home.png` (191,202 B) and `screenshots/after/night/home.png` (188,166 B). Frame shows teal `In` / orange `Out` chips with a `headlineSmall` navy `Net`, six icon-led detection rows coloured by state, a filled `Transactions` + outlined `Payment QR`, and recent payments with a leading direction avatar and the merchant rendered as stored. The a11y dump lists both recent rows with `content-desc="Money out"` / `"Money in"` | — |
| 7.2 | History (transactions) | `ui.transactions.TransactionsActivity` | `implemented and verified` | `screenshots/after/history.png` (259,265 B), `screenshots/after/night/history.png` (260,499 B), and the top-of-list proof `screenshots/after/history-top-scrolled.png` (259,265 B). The frame shows the standard small top app bar, `In`/`Out`/`Items` stat chips, search, the `DIRECTION`/`RANGE`/`ACCOUNT` groups, and `Analytics`, `Export history` and `Clear filters` actions. The entire filter block is the first non-sticky `LazyColumn` content, so the first row is fully visible below it; rows retain the direction avatar, source label and `From owner` badge when applicable. | — |
| 7.3 | Analytics | `ui.analytics.TransactionAnalyticsActivity` | `implemented and verified` | `screenshots/after/analytics.png` (228,089 B) and `screenshots/after/night/analytics.png` (225,861 B). Frame shows the four range chips on one line in a scroll row, the summary card with `In`/`Out` chips, spending-by-category bars, a populated `Top payees` list, the single-day header `Today`, and a right-aligned tonal `Share summary (CSV)` | No chart library — proportional bars only |
| 7.4 | Cashbook | `ui.cashbook.CashbookActivity` | `implemented and verified` | `screenshots/after/cashbook-populated.png` (140,690 B) and `screenshots/after/night/cashbook-populated.png` (144,102 B). Frame shows `Balance` over `₹40.00` in `displaySmall`, `In 40.00`/`Out 0.00`/`Entries 1` chips, a `Chai · Food` row with a teal up-arrow avatar, a filled `+ Add entry` + outlined `Report`, the overflow `More actions` action in the app bar | — |
| 7.5 | Upcoming payments | `ui.passbook.UpcomingPaymentsActivity` | `implemented and verified` | `screenshots/after/upcoming-populated.png` (122,543 B) and `screenshots/after/night/upcoming-populated.png` (123,797 B). Frame shows `To pay 0.00` / `To receive 0.00` chips, a single filled `+ Add reminder`, an icon-led empty state, and **no** `Re-arm alarms` on the surface — its a11y dump contains `Add reminder` only, with the maintenance action inside the `More actions` overflow | — |
| 7.6 | Settings | `ui.settings.SettingsActivity` + per-feature settings screens | `implemented and verified` | `screenshots/after/settings.png` (230,874 B) and `screenshots/after/night/settings.png` (217,995 B). Frame shows one consistent card header style, the three sound-mode chips on a single scroll row, permission values coloured teal when granted and orange when not, and secondary actions demoted to outlined buttons | The reference's own Settings defect list was truncated in the brief, so the changes made are the ones listed in `screenshots/COMPARISON.md` rather than a point-for-point match |
| 7.7 | Bottom navigation | bottom tab bar in reference | `implemented and verified` | `screenshots/after/home.png` and the a11y dump of every captured screen list exactly five items — `Home`, `History`, `Cashbook`, `Upcoming`, `Settings` — each with an icon and one label; there is no two-letter abbreviation anywhere. The indicator is `tertiaryContainer` and the selected icon/label `tertiary`; probed on the frame as `rgb(225,241,239)` in day and `rgb(3,71,61)` in night | — |
| 7.8 | Settings — persistent label on numeric input | — | `implemented and verified` | `screenshots/after/settings-min-amount-filled.png` (232,203 B). The "Ignore amounts below (₹)" `labelLarge` text sits above the `OutlinedTextField` and remains visible while the user types `500`; the supporting text `"Payments under this amount are not announced"` appears below. The field keeps its own text state and only the parsed value is persisted. Source: `ui/settings/SettingsScreen.kt` lines 101–115 | — |
| 7.9 | Settings — slider endpoint labels | — | `implemented and verified` | `screenshots/after/settings-duplicate-window.png` (232,952 B). The `SliderRow` composable renders `"1 min"` on the left and `"30 min"` on the right in `bodySmall onSurfaceVariant`, plus a chip showing `"5 min"` that updates as the slider moves. Source: `ui/components/Common.kt` lines 398–449, called from `SettingsScreen.kt` lines 143–144 | — |
| 7.10 | Settings — bottom nav does not occlude content | — | `implemented and verified` | `screenshots/after/settings-scrolled-bottom.png` (205,684 B) and `screenshots/after/night/settings-scrolled-bottom.png` (205,550 B). The `LazyColumn` has `.padding(bottom = 96.dp)` (line 91 of `SettingsScreen.kt`), and the pixel data confirms the last visible card row ends at y ≈ 2632 while the nav bar band begins at y 2672 — a 40 px gap with no overlap. The `Reset all settings` button (a11y bounds y 1978–2038) is fully tappable | — |
| 7.11 | Semantic palette mapping (credit teal / debit orange / warning orange / healthy teal) | — | `implemented and verified` | Pixel samples in `screenshots/PALETTE.md` §Semantic application: credit amount `#058E7A` (1808 px), healthy status icons `#058E7A` (1288 px each), degraded warning icon `#F65026` (898 px). See §2 below for full detail | — |
| 7.12 | Top app bar inset consistency | — | `implemented and verified` | The shared nested-Scaffold inset fix is in `ui/PayAlertApp.kt:121–124`. Pixel-row evidence in `BUILD_AND_VERIFICATION.md` §16.1 measures the status-bar region boundary to the History title layout at **54 px after** the fix (under 60 px); the literal status-text-baseline-to-glyph measurement is also recorded there as 131 px because it includes normal small-app-bar centering. Day/night evidence: `screenshots/after/history-top-scrolled.png` and `screenshots/after/night/history.png`, with `screenshots/after/cashbook-top.png` for Cashbook. | — |
| 7.13 | History filter does not occlude the first payment row | — | `implemented and verified` | `screenshots/after/history-top-scrolled.png` shows the first `FLIPKART@ybl` payment's merchant, `+888.00` amount, timestamp, source label and leading avatar fully visible below the filter block. The filter is the first item in the same `LazyColumn`; no sibling overlay or sticky header remains. | — |

Night-mode frames were produced by `adb shell cmd uimode night yes` followed by a full
capture pass; the day frames by `cmd uimode night no`. The two passes are distinguishable by
measurement (mean luminance ≈ 223–230 day vs ≈ 32–38 night), so the night column is not a
re-photograph of the light one.

## 8. Theme

| # | Item | Status | Evidence |
|---|---|---|---|
| 8.1 | Palette source | `implemented and verified` | The four scheme anchors are **sampled from the launcher icon**, not invented: `app/src/main/ic_launcher-playstore.png` (512×512) → primary navy `#122550`, secondary orange `#F65026`, tertiary teal `#058E7A`, accent gold `#FE8D13`. The extraction command, its literal output and the per-cluster pixel counts are in `screenshots/PALETTE.md`. Confirmed in the frames: the status bar and the primary action button probe as `rgb(18,37,80)` = `#122550`, the page background as `rgb(250,250,250)` = `#FAFAFA` |
| 8.2 | Dynamic colour decision | `implemented and verified` | **Disabled deliberately.** `PayAlertTheme` always passes the explicit `LightColors` / `DarkColors` scheme and never calls `dynamicLightColorScheme` / `dynamicDarkColorScheme`, so a wallpaper-derived scheme can never displace the icon palette. Verified by reading `ui/theme/Theme.kt` and by the day/night frames still showing the brand navy and teal on an API 34 device that supports dynamic colour |
| 8.3 | Semantic colour mapping | `implemented and verified` | teal = money in and healthy, orange = money out and warning, red = failure, navy = primary action and headings. The three status colours live in one place (`ui/components/Common.kt`) and are shared by Home, History, Analytics and Settings, so the screens cannot drift apart. Visible in `screenshots/after/home.png` and `screenshots/after/settings.png` |
| 8.4 | Night palette | `implemented and verified` | Hand-tuned from the same four source colours (not an inversion): background `#10131C`, surface `#171B24`, primary `#6F68A8`, tertiary `#4FD9B5`, statuses `#4FD9B5` / `#FF8A5C` / `#F2B8B5`. Probed on `screenshots/after/night/home.png` as `rgb(16,19,28)`, `rgb(23,27,36)`, `rgb(111,104,168)`. The values table is in `screenshots/PALETTE.md` |
| 8.5 | Before / after visual comparison | `implemented and verified` | Six day+night before/after frame pairs captured from the pre-rebrand APK (`f4b31e5`) vs the rebranded APK (`9ec72de` + settings 1a–1c fixes). See `screenshots/COMPARISON.md` for the side-by-side embeds and per-screen change notes. The git commands that produced the before frames are recorded in §3 of that file. Blocker: the "Analytics" entry point in the pre-rebrand build is hidden behind the health-check banner on the Transactions screen; a workaround was used (tap `Fix` then navigate directly) |

---

## Summary

**Verified by automated test (141 JVM tests, 0 failures, read from this run's report):** parser rules
(credit/debit/skip/amount/UTR/account/merchant/direction/currency), config loading, source
inference, dedup keys and windowing (including the concurrent-mutex proof and its control,
and both prune cutoffs), announcement formatting, analytics maths, cashbook report maths, CSV
export shape, UPI link building, QR encode→decode round-trip, the launch-time service health
evaluator and the rating-prompt policy.
The pure sound-mode decision function is also covered by eight assertions in
`SoundPlaybackTest`.
Pairing adds 42 JVM tests for PKCE/session/RPC/controller behavior and four instrumented Compose
tests. The SQL suite executes ownership, single-use/expiry, readback, revocation and rate limits
against a disposable PostgreSQL database. These checks do not prove live Google/Supabase integration.

**Verified at runtime on Android 14 (API 34) with the real debug APK:** SMS detection,
notification detection, cross-app semantic duplicate suppression **on the notification path**
(three UPI-package stubs → 1 row, new-reference payment 8.5 min later → row 2, seeded prune
check — `BUILD_AND_VERIFICATION.md` §10), cross-detector duplicate suppression, foreground
monitor service, TTS
synthesis, History (list, detail, search, direction/date/account filters, totals, CSV export),
Analytics (range presets, category bars, top payees, daily in/out), Cashbook (create, edit,
delete, export button, category filter, date range, report dialog), Upcoming payments (create,
mark paid, monthly roll-forward, real alarms), reboot recovery, minimum-amount filtering,
dedup pruning, Backup & restore (export, import, skip accounting), QR rendering, the
home-screen widget (placed, live, self-refreshing), Help & FAQ, Privacy & terms, the system
permission deep links, the launch-time health check, the rating prompt, Settings toggles
affecting the pipeline, the four Settings sound modes, sound-chip previews, chime-to-voice
chaining and the Diagnostics pipeline test. No crash occurred in any run.

**Added in the last pass and verified at runtime:** reboot recovery (real `adb reboot`),
Cashbook edit, Cashbook export button, reminder mark-paid and monthly roll-forward (Room plus
`dumpsys alarm`), the minimum-amount filter (both directions, through the Settings field),
dedup pruning (a seeded 10-day-old key removed by a live payment), the system-Settings deep
links, the launch-time service health check and the rating prompt.

**Implemented, compiled and manifest-verified, not yet executed:** unlock-triggered restart
(the receiver is registered and a real unlock was performed, but a dead monitor could not be
created without root/SELinux changes — the reason is recorded in §9.6 of the verification
record), and speech speed/pitch/volume audibility.

**Partial:** balance-only message tracking (the balance *on* a payment is captured),
team/pairing (UI/client and SQL implemented and tested in isolation; live integration not verified).

**Missing relative to the reference:** mandate/autopay parsing and the balance-change history.

**Not applicable by requirement:** a camera-based QR scanner (generation is implemented and
verified; capture cannot be exercised on the headless AVD).

**Excluded by requirement:** all monetization, ads, attribution and third-party identity SDKs.

---

## Added in this pass

The notification-path verification (`BUILD_AND_VERIFICATION.md` §10): the `PaymentStore`
seam that makes the pipeline unit-testable, `PaymentPipelineTest` (6 tests including the
concurrent mutex proof and its control), the three UPI-identity stub APKs and
`scripts/build-notif-stubs.sh`, the runtime three-notification / regression / seeded-prune
runs, the TTS utterance log line, and the `pruneSemanticBefore` fix.

**Verified this pass:** (each item with the command that proved it)

| Item | Command |
|---|---|
| Build after the seam change | `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`, exit 0 |
| 95 unit tests, 0 failures (12 suites) | `./gradlew :app:testDebugUnitTest --rerun-tasks` + per-file XML counts |
| Mutex serialises concurrent check-and-insert (Test B + control) | the two `async(Dispatchers.Default)` tests, XML `tests="6" failures="0"` |
| Semantic collapse across three UPI package identities, notification path | 3 stub launches → logcat `accepted / duplicate / duplicate`, 1 `payment_events` row, 1 semantic key, 1 TTS utterance (§10.6–10.7) |
| Six-minute regression, notification path | new-UTR stub 8.5 min later → `accepted`, 2 rows, buckets `…:5973572` vs `…:5973574` (§10.8) |
| 10-minute semantic prune, seeded on device | seeded `now−11min` rows → semantic row gone, exact row survives (§10.9) |
| Exact-key 3-day retention restored (prune bug fix) | same seeded run + Test D |

**Unverified this pass:** (blocker named)

| Item | Blocker |
|---|---|
| Unlock-triggered restart | no way to kill a foreground-service process and keep the package deliverable without root/SELinux changes (§9.6) |
| Speech rate/pitch/volume, chime, vibration — audibility | headless emulator, nothing was listened to |
| The reference app's own dedup constants | the reference exposes no readable 5-/10-minute values; PayAlert's constants are its own |
| SMS path re-run | not re-executed this pass; §9.4.5 remains its standalone evidence and does not cover 3.1b |
