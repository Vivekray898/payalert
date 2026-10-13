# Reverse Engineering Report — Auto Alert v2.4.5

**Reference APK:** `/Users/mamanjonpal/Downloads/website/trials/rea/Auto Alert v2.4.5.apk`
**SHA-256:** `f285aea91b3eacb2f3cf682a9c1c85d4e43e92d407279085fdb6bb5fcad82241`
**Package:** `com.binaryscript.payalert` · versionName `2.4.5` · versionCode `315`
**minSdk:** 24 · **targetSdk:** 36 · compileSdk 36

**Ownership:** treated as third-party reference for personal-rebuild purposes. This document describes observed behavior for independent reimplementation. Parser rules, audio assets, brand marks, and proprietary server-side identifiers are not extracted.

**Tools used:** REA MCP (`open_binary`, `inspect_artifact`, `inspect_android_package` — blocked on missing jadx, `decode_android_resources` blocked on missing apktool), `aapt2` from build-tools 36.0.0, `dexdump`, `unzip`. jadx/apktool/decompiler output was NOT available; class names below are from DEX symbol tables only. Where noted `I` = inferred.

---

## 1. Purpose (C(static))

A UPI + bank payment monitor for the Indian market. Listens for bank/UPI notifications on the device, reads incoming bank SMS, parses transaction details, announces payments aloud via TTS, keeps a transaction log / cashbook, and monetizes via Razorpay subscriptions with Play Licensing verification. AdMob, Google Analytics, install-referrer, and Truecaller account-state SDKs are integrated.

Language variants shipped: bn, gu, hi, kn, ml, mr, pa, ta, te (application labels only, in manifest).

---

## 2. Components (C(static))

### 2.1 Activities (33 declared; all app-owned unless annotated I)

**Auth / entry:**
- `ui.auth.SplashActivity` — launcher (`launchable-activity`)
- `MainActivity` — hub / home

**Money / transactions:**
- `ui.transactions.TransactionsActivity`
- `ui.cashbook.AddEditCashBookEntryActivity`
- `ui.passbook.UpcomingPaymentsActivity` (I: recurring-payment reminder list)
- `ui.analytics.TransactionAnalyticsActivity`

**Subscription / entitlement:**
- `ui.settings.SubscriptionActivityNew`
- `ui.settings.subscription.ManageSubscriptionActivity`
- `com.pairip.licensecheck.LicenseActivity` (I: third-party license-verification host; **NOT to be copied**)
- `com.razorpay.DeeplinkActivity` (I: Razorpay SDK deeplink; **NOT to be copied**)

**Discovery / marketing:**
- `ui.WebViewActivity` (I: terms, privacy, in-app help)
- `ui.FaqsActivity`
- `ui.team.TeamActivity`
- `ui.apps.MoreAppsActivity` (I: cross-promotion of dev's other apps)
- `ui.referral.ReferralActivity`

**Scanning:**
- `ui.scanner.QRScannerActivity` (uses `com.journeyapps.barcodescanner.CaptureActivity`; I: QR-based payment-initiation)

**Settings:**
- `ui.settings.TtsSettingsActivity`
- `WidgetConfigActivity`

**Framework hosts:**
- `com.google.firebase.auth.internal.GenericIdpActivity`, `RecaptchaActivity`, `GoogleApiActivity`, `SignInHubActivity`, `RevocationBoundService` — Google sign-in
- `androidx.glance.appwidget.action.ActionTrampolineActivity`, `InvisibleActionTrampolineActivity` — widget click-through
- `com.google.android.play.core.common.PlayCoreDialogWrapperActivity` — Play Core licensing

### 2.2 Services

- `services.PaymentMonitorService` — main foreground monitor (special-use FGS subtype declared in manifest: *"Payment monitoring service that listens for UPI payment notifications and announces them via TTS"*)
- `services.TtsBackupService` — I: fallback announcer when normal FGS is throttled by battery optimization (manifest property subtype confirms)
- `services.passbook.SmsCreditFallbackService` — I: SMS-based payment announcement fallback when notification listener missed the event (subtype confirms)
- `services.notifications.PaymentNotificationListenerService` — `NotificationListenerService` for reading other apps' notifications
- `services.firebase.AutoAlertFirebaseMessagingService` — FCM push

### 2.3 Receivers

- `SmsBroadcastReceiver` — top-level SMS dispatcher
- `services.passbook.BankSmsReceiver` — likely filters bank SMS specifically
- `services.notifications.PaymentReceiver` — I: internal event receiver
- `services.boot.BootReceiver` — re-arm on boot
- `services.boot.ServiceRestartReceiver` — restart FGS on system events
- `services.boot.TtsBackupReceiver`
- `services.passbook.SmsCreditFallbackReceiver`
- `widget.UpiQrWidgetReceiver` — UPI QR widget action

### 2.4 Providers

Manifest has 4 `provider` declarations at lines 235, 241, 260 — these are typically Firebase/Google file/providers plus the Glance content provider. Not app-specific content providers.

---

## 3. Parser architecture (C(static))

`assets/parser_config.json` is a shipped, externally-readable rules file. Structural shape (from partial read):

- `checksum`: currently empty string.
- `bankRules[]`: array of bank configs.
  - `bankId`, `bankName`, `catchAll`, `currency`, `senderPatterns` (with `exactMatch`, `dltPatterns`, `containsMatch` sub-keys).
  - `extractionRules`: for each field (amount, account, balance, merchant) a list of extraction rules with typed strategies: `BETWEEN_MARKERS`, `CONDITIONAL_PATTERN`, etc.
  - Each rule has `id`, `type`, `flags` (e.g. `CASE_INSENSITIVE`), `priority`, `preconditions` (typed predicates), `captureGroup`, `postProcess` (chained transforms like `CLEAN_MERCHANT`).
  - Supports NEFT/RTGS/IMPS/UPS variants with bank-specific regexes.

`com.binaryscript.parser.core` contains 82 classes including:
- `BankParser`, `BaseIndianBankParser`, `BankParserRegistry`
- `CompiledPatterns` with inner classes: `Account`, `Amount`, `Balance`, `Cleaning`, `Currency`, `Date`, `HDFC`, `Mandate`, `Merchant`, `Reference`, `Time`
- Result types: `BaseBalanceUpdateInfo`, `MandateIdResult`

**Design observations (I):**
- Rules are shipped as data + typed rule engine in code. Enables adding banks without recompiling.
- Bank-specific regexes exist (e.g. HDFC card-merchant between " At … On ", NEFT/RTGS Cr- pattern).
- Parser handles multiple intents: transaction (credit/debit), balance update, mandate setup, refund.
- Priority-based rule selection with preconditions means multiple rules can attempt a match.

**Not copied in rebuild:** specific regex patterns, bank IDs, rule IDs, and bank-specific marker strings.

---

## 4. Data & storage (I from class names + Room presence)

`com.binaryscript.payalert.data` contains 492 classes. Room is linked (see `androidx.room.MultiInstanceInvalidationService`). No content providers are exposed, so all data is local. I:
- Room database with entities for transactions, cashbook entries, subscriptions, TTS preferences, notification logs.
- Shared prefs for: language, TTS voice/speed/volume, keyword config, referral code, user profile.
- Firebase Cloud Storage or HTTP for cross-device sync (I: FCM + "fetchAppDataInBackground" / "syncLanguageToBackendInBackground" / "sendLocationToBackendInBackground" in `MainActivity` inner classes suggest backend sync).

`MainActivity` inner classes (from DEX) reveal the app-level sync operations:
- `fetchAppDataInBackground`, `updateFcmTokenInBackground`, `syncLanguageToBackendInBackground`, `sendLocationToBackendInBackground`, `refreshSubscriptionAndEnforce`, `checkAndSyncNotificationLogsInBackground`, `startServiceHealthChecks`

**Interpretation (I):** The app calls its own backend over HTTPS for user profile, subscription enforcement, FCM token registration, optional location telemetry, and notification-log sync. Location upload appears opt-in.

---

## 5. Auth & entitlement (C(static))

- **User auth:** Firebase Auth (Firebase SDKs present: `GenericIdpActivity`, `RecaptchaActivity`, `SessionLifecycleService`, `initializeAuth$1` in `AutoAlertApplication`). No `READ_PHONE_STATE` beyond `READ_BASIC_PHONE_STATE`. Google sign-in flow via Firebase.
- **Subscription:** Razorpay SDK (`DeeplinkActivity`, `BIND_GET_INSTALL_REFERRER_SERVICE`, `com.android.vending.CHECK_LICENSE`, `play.core.common.PlayCoreDialogWrapperActivity`) + Google Play Billing via Play Core.
- **License verification:** `com.pairip.licensecheck` (third-party server-side validator) plus Google Play Licensing (`CHECK_LICENSE`).
- **Ads/identity:** AdMob (`AD_ID`, `ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION`, `ga_ad_services_config.xml`), Firebase Analytics, install-referrer service.
- **Truecaller:** `com.truecaller.permission.sdk.internal.read_account_state` — I: reads Truecaller account state for contact relationships (privacy-sensitive).

**Not rebuilt in Phase 1:** Razorpay, Play Licensing, AdMob, Truecaller SDK, Firebase Analytics. Rebuilt: Google auth via Supabase; own entitlement tables.

---

## 6. Permissions (C(static))

Grouped by purpose:

**Payment detection:**
- `RECEIVE_SMS`, `READ_SMS` — read bank/UPI SMS
- `BIND_NOTIFICATION_LISTENER_SERVICE` — read notifications from UPI apps
- `POST_NOTIFICATIONS` — system notification channel

**Announcement:**
- `MODIFY_AUDIO_SETTINGS`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — TTS + haptic cues in background
- `WAKE_LOCK` — keep CPU alive during announcement
- `SYSTEM_ALERT_WINDOW` — I: possibly overlay UI for payment alerts
- `WRITE_SETTINGS` — I: battery optimization whitelist
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — keep services alive
- `SCHEDULE_EXACT_ALARM` — timed passbook reminders

**Boot / background persistence:**
- `RECEIVE_BOOT_COMPLETED`

**Connectivity & telemetry:**
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_ADSERVICES_ATTRIBUTION`, `ACCESS_ADSERVICES_AD_ID`

**Device / contacts (privacy-sensitive):**
- `READ_BASIC_PHONE_STATE`, `com.truecaller.permission.sdk.internal.read_account_state`

**Hardware:**
- `CAMERA` (QR scanner)

**Platform integrations:**
- `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE`
- `com.google.android.c2dm.permission.RECEIVE`
- `com.google.android.providers.gsf.permission.READ_GSERVICES`
- `com.android.vending.CHECK_LICENSE`
- `BIND_GET_INSTALL_REFERRER_SERVICE`

**Not rebuilt:** Truecaller, CHECK_LICENSE, ad/adservices, install-referrer, camera.

---

## 7. Third-party SDKs (C(static))

From META-INF and class inventory:
- **Firebase:** Core, Auth, Messaging, Analytics, Config (I)
- **Google Play Services:** Auth, Billing (via Play Core), Location (I: Fused), Ads AdId
- **Razorpay SDK**
- **Google Play Licensing** + `pairip.licensecheck` companion
- **AdMob / GMAPI** + AdServices
- **Truecaller SDK**
- **Firebase Cloud Messaging** for remote notifications
- **AndroidX:** Compose Material3 (I from `activity-compose_release.kotlin_module`), Room, Work, Glance (widgets), Browser (Custom Tabs), Lifecycle, Hilt/DI (I from class patterns — not confirmed)
- **ZXing (journeyapps BarcodeScanner)** for QR scanning
- **Apache HTTP legacy**

---

## 8. Notifications & background behavior (C(static) + I)

- **Notification listener** (`PaymentNotificationListenerService`) reads incoming notifications from other apps (UPI, banks) — this is the primary detection path since UPI apps no longer send bank SMS reliably.
- **SMS receivers** (`SmsBroadcastReceiver`, `BankSmsReceiver`) read SMS; used as a secondary path and specifically as a "credit fallback" for passbook features.
- **Foreground service** (`PaymentMonitorService`) holds the wake lock and drives TTS.
- **TTS backup service** for when the main FGS is being throttled (declared manifest subtype: *"Backup TTS service that announces payments when normal announcement was interrupted by device battery optimization"*).
- **SmsCreditFallbackService**: declared subtype says *"Announces incoming payments detected from bank SMS when the app notification was missed"*. So the app layers three detection paths.
- **Boot receivers** re-arm all services on reboot.
- **Health checks** (`startServiceHealthChecks`) suggest the app self-monitors service liveness.
- **Exact alarms** for scheduled passbook reminders.
- **Battery optimization opt-out** requested explicitly.

## 9. Duplicate event prevention (I)

`MainActivity` has a `checkAndSyncNotificationLogsInBackground` inner class — suggests notifications are logged with unique IDs (I: notification key + timestamp) and de-duped across SMS/notification paths. `PaymentReceiver` presumably handles the dedup merge.

## 10. Diagnostics (I)

- `ui.KeywordCheckActivity` — keyword test surface.
- `ui.PipelineTestActivity` — pipeline test surface.

Neither is a full diagnostics dashboard; the app uses in-app testing tools instead. **We build our own diagnostics screen in Phase 1 (per user requirement).**

## 11. Assets (C(static))

Seven MP3 announcement sounds: `cashier_ka_ching.mp3`, `coin_recieved.mp3`, `gun_shot.mp3`, `info.txt` (build metadata), `service_bell_ring.mp3`, `spinning_coin.mp3`, `templebell.mp3`, plus `parser_config.json` and AndroidX R8 baseline profiles.

**Not copied.** We will use either stock Android notification sounds or generate originals.

## 12. Unknowns

- Exact set of supported banks in `parser_config.json` — I read HDFC partially; the file is likely large. Not enumerated.
- Exact UI layout for all screens (no jadx to render Compose/XML). App uses Compose — confirmed from `META-INF/activity-compose_release.kotlin_module`.
- Whether Compose or XML for screens: Compose confirmed.
- Whether DI framework is Hilt: not confirmed without class inspection.
- Backend endpoint domains, feature flags, telemetry schema — not inspected (prohibited to extract secrets).
- Subscription plan pricing and premium-feature entitlements per plan — not inspected (prohibited to bypass licensing).
- QR scanner exact UI flow and integration points — only confirmed from component name.

## 13. Compliance & prohibited actions honored

- No Play Licensing bypass. Our rebuild will use our own Supabase-backed entitlements, not Google Play Billing.
- No receipt forgery or subscription spoofing.
- No third-party vendor server access (Truecaller account state, pairip license, Razorpay).
- No asset/regext-rule copy. Parser rules in the rebuild will be authored from public knowledge of common UPI SMS formats (Indian bank SMS is semi-standard: `UTR`, amount, bank, merchant).

## 14. Feature coverage by status

| Feature | Status | Notes |
|---|---|---|
| SMS + notification payment detection | Confirmed (static) | Two detection paths; primary = notification listener, secondary = SMS |
| Bank SMS parsing | Confirmed (static) | Rules shipped in `parser_config.json`; HDFC partially readable |
| Payment announcement via TTS | Confirmed (static) | `FOREGROUND_SERVICE_SPECIAL_USE` subtype declared |
| Language/voice/speed/volume settings | Confirmed (static) | Application labels in 9 locales |
| Foreground service + boot recovery | Confirmed (static) | Three boot receivers; FGS subtype declared |
| SMS fallback for missed notifications | Confirmed (static) | `SmsCreditFallbackService` declared subtype |
| Transaction history | Inferred | Room database; `TransactionsActivity` |
| Transaction analytics | Inferred | `TransactionAnalyticsActivity` |
| Cashbook / passbook entries | Inferred | `AddEditCashBookEntryActivity`, `UpcomingPaymentsActivity` |
| Add/edit cashbook entries | Inferred | Inner classes `refreshSubscriptionAndEnforce`, `sendLocationToBackendInBackground` suggest CRUD |
| Upcoming payments / reminders | Inferred | `UpcomingPaymentsActivity` exists |
| QR scanning / workflows | Inferred | `QRScannerActivity` + `BarcodeScanner` dependency |
| Home-screen widget + config | Inferred | `WidgetConfigActivity`, `UpiQrWidgetReceiver` |
| FAQ screen | Inferred | `FaqsActivity` exists |
| Settings (general) | Inferred | `TtsSettingsActivity` + many preference configs |
| Team / device management | Inferred | `team.TeamActivity` + `MainActivity` sync ops |
| Referrals | Inferred | `ReferralActivity` + referral code in settings |
| Payment announcements via notification fallback | Confirmed (static) | Subtype says "when normal announcement was interrupted" |
| Account/profile management, Google sign-in | Confirmed (static) | Firebase Auth + Google sign-in SDKs |
| Sign-out | Confirmed (static) | `RevocationBoundService` suggests session revocation |
| Subscription / manage-subscription | Inferred | `SubscriptionActivityNew`, `ManageSubscriptionActivity` + Razorpay + Play Billing |
| All other utility screens (MoreApps, FAQ, WebView, etc.) | Inferred | Component names present |
| Firebase-style notifications (FCM) | Confirmed (static) | `AutoAlertFirebaseMessagingService` declared |
| Owner-to-employee device pairing | Inferred | Sync ops in `MainActivity` inner classes suggest cross-device |
| Diagnostics screen | Unknown | Not a named activity; we build it |

---

## 15. Architecture and backend (for rebuild)

* Keep core payment detection and speech announcements operational offline.
* Use Supabase for authentication, user profiles, optional cloud backups, transaction synchronization, and paired-device synchronization.
* Add secure owner-to-employee device pairing, event IDs, duplicate suppression, and retry handling.
* Use Google OAuth instead of mobile-number authentication.
* Make cloud-dependent features degrade gracefully when offline.
* Avoid paid dependencies where practical.
* Never embed privileged Supabase credentials in the APK.

## 16. UI and quality requirements

Recreate the reference app's complete screen hierarchy and user workflows as closely as practical, using my own branding and independently created assets. Include empty states, loading states, validation, error handling, accessibility, and responsive layouts. Do not use fake implementations or placeholder buttons as completed features. Build, test, and verify each feature incrementally. Compare screenshots and navigation against the reference wherever runtime inspection is possible.

## 17. Execution

Continue using REA for supported inspection tasks. Scaffold the project and proceed in implementation phases without stopping after the planning stage. Keep the original APK and unrelated files untouched. If a feature cannot be reproduced because a proprietary service, API, or entitlement is unavailable, document that specific blocker and implement the best legitimate alternative. Report progress by completed features, build results, and actual test evidence. Ask only when a decision truly requires input.