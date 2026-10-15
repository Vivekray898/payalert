# PayAlert — Build & Verification Record

All results below are from commands actually executed in this workspace. Nothing here is
inferred or assumed.

## 1. Environment (measured)

| Item | Value | How measured |
|---|---|---|
| Host | macOS, `arm64` | `uname -m` |
| JDK used by Gradle | Temurin **21.0.12.1** | `org.gradle.java.home` in `gradle.properties`; `./gradlew --version` → `Launcher JVM: 21.0.12.1` |
| Gradle | **9.6.0** (cached distribution, real wrapper generated) | `./gradlew --version` |
| Android Gradle Plugin | **9.4.1** | `build.gradle.kts` / resolved from cache |
| Kotlin | **2.2.10** (AGP built-in Kotlin + Compose compiler plugin) | `app/build.gradle.kts` |
| KSP | **2.2.10-2.0.2** (Room annotation processing) | `app/build.gradle.kts` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 | `app/build.gradle.kts`; confirmed in built APK |
| build-tools | 36.0.0 | `app/build.gradle.kts` |
| Android SDK platforms present | `android-34`, `android-37.0` | `ls $ANDROID_HOME/platforms` |

### The real cause of the original build failure

The repository was **not a working Gradle Android project**, so the reported
`libnative-platform.dylib` symptom was a consequence, not the cause. Actual defects found:

1. `gradlew` was a 527-byte hand-edited script whose last line invoked the non-existent
   main class `/org/gradle/wrapper/GradleWrapperMain` (an absolute filesystem path instead of
   `org.gradle.wrapper.GradleWrapperMain`).
2. `gradle/wrapper/gradle-wrapper.jar` **did not exist**.
3. `gradle-wrapper.properties` pointed `zipStorePath` at an unrelated project
   (`/Users/…/website/trials/rea/.gradle/wrapper` was stale and inconsistent).
4. The **root** `build.gradle.kts` was actually an *application-module* file (it contained
   `android { namespace … defaultConfig … }`) but applied no Android plugin at all — it used
   `kotlin("android")`, `kotlin("android.extensions")` and `compose("1.6.1")`, none of which are
   valid plugin ids.
5. `settings.gradle.kts` declared `include(":app")` while **`app/build.gradle.kts` did not exist**.
6. There was no `AndroidManifest.xml` under `app/src/main/` and **no `res/` directory at all**,
   although the stray root manifest referenced `@mipmap/ic_launcher`, `@style/Theme.PayAlert`
   and `@xml/network_security`.
7. **Every** Kotlin file failed to compile: they imported the dead `android.arch.persistence.room`
   package, referenced fields that do not exist (`parsed.dedupKey`, `parsed.currency`), used
   MySQL syntax (`date_sub(curdate(), interval 30 day)`) inside a Room query, contained invalid
   annotation syntax in `diModule.kt`, called `math.max` instead of `kotlin.math.max`, overrode
   non-existent notification-listener methods, started the receiver instead of the service from
   `BootReceiver`, and called `TextToSpeech.setVolume()` which does not exist.

### Fix applied

- Generated a genuine wrapper (jar + scripts) from the cached Gradle 9.6.0 distribution.
- Rewrote `settings.gradle.kts`, the root `build.gradle.kts` and added a real `app/build.gradle.kts`.
- Adopted AGP 9's **built-in Kotlin** (the standalone `org.jetbrains.kotlin.android` plugin is
  rejected by AGP 9) and set AGP's documented `android.disallowKotlinSourceSets=false` so KSP can
  register its generated sources.
- Created `app/src/main/AndroidManifest.xml` and the missing `res/` tree (theme, strings, colors,
  network-security config, backup rules, adaptive + fallback launcher icons).
- Rewrote the entire Kotlin source tree (34 files) so it actually compiles.

## 2. Build (real output)

```
$ export JAVA_HOME=/Users/mamanjonpal/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
$ export ANDROID_HOME=/Users/mamanjonpal/Library/Android/sdk
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 7s
37 actionable tasks: 8 executed, 29 up-to-date
```

Artifact:

```
app/build/outputs/apk/debug/app-debug.apk   11,825,498 bytes
```

`aapt2 dump badging` on the produced artefact:

```
package: name='com.vivekray898.payalert' versionCode='1' versionName='1.0'
        compileSdkVersion='37'
minSdkVersion:'24'
targetSdkVersion:'37'
application-label:'PayAlert'
```

Components read back out of the **built APK**:

```
activity  com.vivekray898.payalert.ui.MainActivity
service   com.vivekray898.payalert.services.PaymentMonitorService
service   com.vivekray898.payalert.services.PaymentNotificationListener
receiver  com.vivekray898.payalert.services.SmsReceiver
receiver  com.vivekray898.payalert.services.BootReceiver
receiver  com.vivekray898.payalert.services.ServiceRestartReceiver
```

## 3. Unit tests (real output)

```
$ ./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL in 2s
```

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `parsers.ParserRegistryTest` | 8 | 0 | 0 |
| `parsers.DedupKeyBuilderTest` | 5 | 0 | 0 |
| `settings.AnnouncementFormatterTest` | 4 | 0 | 0 |
| **Total** | **17** | **0** | **0** |

One genuine defect was found and fixed by these tests: `detectKind` used plain substring
matching, so `"sent to"` never matched `"Sent Rs.150 to …"`. Direction detection now uses
word-boundary matching for single-word keywords, which also prevents `"sent"` matching inside
`"consent"`.

The Room schema and every DAO query are additionally validated at compile time by the KSP
processor — an invalid column or SQL string fails `:app:kspDebugKotlin`. All three entities and
every query compiled.

## 4. On-device verification (Android 14 / API 34 emulator)

`Pixel_8_Pro` AVD, `arm64-v8a` image, headless (`-no-window -gpu swiftshader_indirect`).

```
$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Success
```

### 4.1 App launches, UI renders

`topResumedActivity = com.vivekray898.payalert/.ui.MainActivity`; `uiautomator` dump of the
accessibility tree contained the real screen content (status, `Notification listener: Not
connected`, `SMS permission: Not granted`, totals, and the five navigation destinations).
No `FATAL EXCEPTION` in logcat.

### 4.2 End-to-end SMS pipeline

```
$ adb shell pm grant com.vivekray898.payalert android.permission.RECEIVE_SMS     (and READ_SMS, POST_NOTIFICATIONS)
$ adb emu sms send HDFCBK "Rs.5000.00 credited to A/c XX1234 on 12-05-24 from ACME STORE. UTR 412345678901"
OK
```

The SMS was sent **twice**. Reading the real Room database out of the device data dir:

```
sqlite> select id,kind,amount,currency,merchant,utr,account,bank_or_upi,source,source_detail from payment_events;
1|credit|5000.0|INR|ACME STORE|412345678901|XX1234|HDFC Bank|sms|HDFCBK

sqlite> select count(*) from payment_events;
1

sqlite> select dedup_key from dedup_index;
utr:412345678901|credit|5000.00
```

One row, not two: parse + dedup + persistence all work together.

### 4.3 Notification path and cross-detector dedup

```
$ adb shell 'cmd notification post -t PhonePe tagE "You received Rs 1200 from Quoted Shop"'
$ adb emu sms send HDFCBK "Rs.1200.00 credited to A/c XX1234 from Quoted Shop"
```

```
D PayAlert: Notification from com.android.shell: PhonePe You received Rs 1200 from Quoted Shop
I PayAlert: Notification pipeline result: accepted
D PayAlert: Notification from com.google.android.apps.messaging: HDFCBK Rs.1200.00 credited to A/c XX1234 from Quoted Shop
I PayAlert: Notification pipeline result: duplicate
```

```
3|credit|1200.0|Quoted Shop|notification|com.android.shell     <-- one row only
dedup key: amt:1200.00|credit|quotedshop|5973033
```

The same payment seen by two different detectors produced **one** announcement and **one** row.

### 4.4 TTS actually synthesised

```
22:19:19  I PayAlert: Notification pipeline result: accepted
22:19:20  I GoogleTTSServiceImpl: For default lang en-in is name en-IN-language
22:19:20  I GoogleTTSServiceImpl: currentLocale = en-IN
22:19:21  I GoogleTTSServiceImpl: Synthesis request for locale eng-IND and name en-IN-language
```

The engine spoke the announcement in the persisted default language (`en-IN`).

### 4.5 Foreground monitor service

```
ServiceRecord{… com.vivekray898.payalert/.services.PaymentMonitorService}
  isForeground=true foregroundId=1001 types=40000000   (FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
  foregroundNoti=Notification(channel=payalert_monitor …)
  startCommandResult=1                                  (START_STICKY)
```

### 4.6 UI interaction

| Action | Result |
|---|---|
| Tap **History** | Rendered `Transaction history`, `In: 6,500.00`, and all three stored payments with merchant, time, source and signed amount |
| Tap **Settings** | Rendered detection switches, minimum amount, duplicate window, language `English (India)`, speed/pitch/volume |
| Tap **Cashbook** | Rendered totals, balance and the empty state |
| Tap **Add entry** | Dialog opened with Label / Amount / Money in / Money out / Category / Note / Cancel / Save |
| Tap **Save** on the empty form | Validation error `Enter a label` — invalid input correctly rejected |
| Type "Groceries" + "450", tap **Save** | Row persisted (`1\|Groceries\|debit\|450.0`), UI showed the entry and `Out: 450.00` / `Balance: -450.00` |
| Tap **Delete** | Row removed (table back to 0 rows); UI returned to the empty state, `Balance: 0.00` |
| Tap **Diagnostics** | Rendered live status: monitoring enabled, listener connected, SMS granted, notification access granted, `Stored payments 3`, `Dedup keys 3` |

No `FATAL EXCEPTION` from this package appeared in logcat at any point in the session.

## 5. What is NOT verified

- **Boot / unlock recovery** — the receivers are declared and compile, but no device reboot was
  performed, so `BOOT_COMPLETED` handling is unproven.
- **Audibility of speech** — the headless emulator produced synthesis requests, but the audio was
  not listened to.
- **Transaction detail dialog, search box, kind filter chips, Cashbook edit** — implemented and
  compiled; the interactions were not driven on the emulator.
- **The Diagnostics "Run through pipeline" button** — the screen was rendered and its live status
  verified, but the button itself was not tapped.
- **Real bank traffic** — the inputs were emulator-generated SMS and a shell-posted notification,
  not genuine bank alerts. The parser also relies on a marker list that will need widening against
  real-world messages.
- **Release build / minification** — only `assembleDebug` was run; R8 is off.
- **Physical device** — verification was on an emulator only.

## 6. Exact commands to reproduce

```bash
cd /Users/mamanjonpal/Downloads/website/trials/rea
export ANDROID_HOME=/Users/mamanjonpal/Library/Android/sdk

./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # -> see section 7 for the current count
```

---

# 7. Second verification pass (parity implementation)

Sections 1–5 above record the first pass. Everything below is a later pass in the same
workspace: the remaining reference features were implemented and then verified on the device.
Where the two disagree, this section supersedes.

## 7.1 Build and tests (real output, `--rerun-tasks` so nothing came from cache)

```
$ ./gradlew clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 6s
26 actionable tasks: 26 executed

app/build/outputs/apk/debug/app-debug.apk   12,473,753 bytes
```

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `parsers.ParserRegistryTest` | 19 | 0 | 0 |
| `parsers.ParserConfigTest` | 6 | 0 | 0 |
| `parsers.DedupKeyBuilderTest` | 5 | 0 | 0 |
| `settings.AnnouncementFormatterTest` | 4 | 0 | 0 |
| `analytics.AnalyticsCalculatorTest` | 7 | 0 | 0 |
| `cashbook.CashbookReportCalculatorTest` | 4 | 0 | 0 |
| `export.TransactionExporterTest` | 5 | 0 | 0 |
| `upi.UpiLinkTest` | 6 | 0 | 0 |
| `upi.QrPayloadTest` | 4 | 0 | 0 |
| **Total** | **60** | **0** | **0** |

## 7.2 Runtime verification (Pixel_8_Pro AVD, Android 14 / API 34)

Every check below was driven against the installed debug APK. The Room database was read back
by pulling `databases/payalert.db*` (including the `-wal`) with `run-as` and opening it with
SQLite, so the stored rows are quoted rather than inferred from the UI.

### Detection, parsing and dedup (re-run after the pipeline changed)

```
$ adb emu sms send VK-HDFCBK "Rs.5,000.00 credited to A/c XX1234 on 13-10-26 from ACME STORE. UTR 412345678901"
  -> payment_events: (1, credit, 5000.0, ACME STORE, XX1234, HDFC Bank, sms, VK-HDFCBK,
                      dedup_key=utr:412345678901|credit|5000.00)
$ adb emu sms send VK-HDFCBK "<same message again>"
  -> still 1 event, 1 dedup row
$ adb shell "cmd notification post -t 'PhonePe' tag2 'Rs.250.00 debited from A/c XX1234 to VPA swiggy@ybl on 13-10-26. UTR 112233445566'"
  -> payment_events: (3, debit, 250.0, swiggy@ybl, PhonePe, notification, com.android.shell)
$ adb emu sms send VK-HDFCBK "<same debit text>"
  -> event count still 3; log: "Notification pipeline result: duplicate"
```

A non-payment notification was also fed in (`PhonePe Rs.150.00`, no direction or merchant) and
correctly produced `no_match` without storing anything.

### UI screens driven through the accessibility tree

| Screen / action | Observed result |
|---|---|
| Bottom navigation | Five tabs render: `Home`, `Transactions`, `Cashbook`, `Upcoming`, `Settings` |
| Home | This-month totals, four live permission rows, and `Rule set 42 banks / 5 wallets / 95 sender ids` |
| History | `In: 5,000.00 / Out: 400.00 / 3 shown`; account chip `XX1234` derived from data |
| History search | typed `zomato` → `1 shown`, `Out: 150.00` |
| History direction filter | `Out` chip → `In: 0.00 / Out: 400.00 / 2 shown` |
| History detail | Tapping `swiggy@ybl` opened the dialog: amount + currency, PhonePe, swiggy@ybl, UTR, `XX1234`, balance `—`, category, detector, source |
| History export | Export → CSV opened the system share sheet with `payalert-history.csv`; the cached file contained the header plus exactly the one filtered row |
| Analytics | `Money in 6,250.00 / Money out 400.00 / Net 5,850.00 / Payments detected 4`, category bars, `swiggy@ybl 250.00`, `zomato@ybl 150.00` |
| Cashbook | Added `Chai / 40 / Food`; Room read back `(1, 'Chai', 'debit', 40.0, 'Food')`; balance `-40.00`; the `Food` chip appeared |
| Cashbook report | `Money in 0.00 / Money out 40.00 / Net -40.00 / Entries 1`, `Food in 0.00 · out 40.00` |
| Upcoming payments | Added `Electricity / 1500 / Bills`; listed as pending with `14 Oct 2026, 09:00` |
| Reminder alarm | `dumpsys alarm` → `*walarm*:com.vivekray898.payalert/.services.ReminderReceiver RTC_WAKEUP origWhen=2026-10-14 09:00:00.000`, Room row `(1, 'Electricity', 1500.0, 'debit', 'Bills', 1791948600000, 0, 0)` |
| Diagnostics | Live status (`Notification listener: Connected`, `Stored payments 4`, `Dedup keys 4`), rule-set row, and the last pipeline outcome |
| Diagnostics pipeline test | `Run through pipeline` → `accepted`, amount `1,250.00`, direction `credit`, merchant `ACME STORE`, key `utr:412345678901\|credit\|1250.00`, rule set `config-v1`, spoken `Payment received. 1,250.00 from ACME STORE.`; row (4) persisted |
| Settings | Detection sources, minimum amount, duplicate window, sound modes, vibration, language, speed/pitch/volume, per-direction templates with live preview, permissions with live state, app version |
| Settings → pipeline | Turning **Money received** off made the real pipeline return `kind_disabled` for a credit; turning it back on returned `accepted` |
| Help & FAQ | Six Q&A cards rendered |
| Privacy & terms | Native screen: local data, permissions, no-subscription, no-affiliation |
| Backup export | System `CreateDocument` picker → `SAVE` wrote a 2,629-byte JSON to `/sdcard/Download/payalert-backup.json` containing 4 events, 1 cashbook entry and 1 reminder; UI reported `Exported 6 records.` |
| Backup import | After clearing payments, the same file reported `Imported 4 records (4 payments, 0 cashbook, 0 reminders). 2 were already present and left untouched.` — and Room showed exactly those 4 events restored |
| Re-import without clearing | `Imported 0 records … 6 were already present and left untouched.` |
| QR | Entering a UPI id rendered a 720×720 image (`content-desc="UPI payment QR code"`) and the link `upi://pay?pa=vivek%40okhdfcbank&pn=Vivek&cu=INR` |
| Widget | Placed on the launcher: `PayAlert`, `In 6,250.00 · Out 400.00 (this month)`, `13 Oct 23:15 · ACME STORE +1,250.00`, `Refresh` / `Open`. `dumpsys appwidget` shows the bound instance. A new SMS then changed it to `Out 2,400.00` with `13 Oct 23:36 · bigbasket@ybl -2,000.00` — i.e. the pipeline refreshes the widget |

`adb logcat -b crash` was empty throughout.

### QR payload checked by decoding it

A rendered code that cannot be read is a broken feature, so `QrPayloadTest` encodes the UPI
link with the same zxing hints the UI uses and decodes it again with zxing's reader, asserting
the payload round-trips, that the payee/currency survive, that the first row and column are
blank margin (the spec's quiet zone), and that an over-long payload returns *null* (so the
screen shows an error instead of a broken code).

## 7.3 Fixes made because verification failed

| Found by | Failure | Cause | Fix |
|---|---|---|---|
| `TransactionExporterTest` | Header assertion failed | The test compared a 40-character prefix against a 37-character string | Corrected the assertion to the real header |
| Runtime import | `Import failed: UNIQUE constraint failed: cashbook_entries.id (code 1555 SQLITE_CONSTRAINT_PRIMARYKEY)` — payments restored, cashbook/upcoming aborted | `@Insert` defaults to `OnConflictStrategy.ABORT`, so an already-present row threw instead of being skipped | Added ignore-on-conflict restore inserts for cashbook and reminders and wrapped the whole import in one Room transaction; the UI now reports inserted vs skipped counts |
| Runtime analytics | "Top payees" listed `expense` for every row | Payees were grouped by **category**, and the parser labels every debit `expense` | Grouped by merchant with a bank fallback; regression test added |
| Runtime analytics | "Daily movement" showed one number mixing credits and debits | `DayTotal` summed all amounts | `DayTotal` now carries money in and money out separately |
| Runtime accessibility | Settings switches were unlabelled nodes with no exposed toggle state | A bare `Switch` beside a sibling `Text` | The row is now the control (`toggleable` + `Role.Switch` + merged semantics), giving one labelled switch and a full-width tap target |
| Runtime UX | Two chips both labelled "All" on History | Direction filter and date-range filter both used "All" | "Both" and "All time" |
| Runtime QR | Code had a 1-module quiet zone and was drawn on a dark card in dark mode | `MARGIN = 1`, no backdrop | `MARGIN = 4` plus a white backdrop |
| Runtime UX | Returning from a secondary screen reset the Settings scroll position | The overlay replaced the tab content entirely | Overlays are drawn **above** the tab content in a `Box`, so tab state survives; a tab tap also closes an open overlay |
| Static review | `.env.example` described Supabase as "required for auth, device tracking, event sync" | Stale file from before the local-first rewrite | Rewritten: no credentials required; the cloud schema is optional and the app never reads it |
| Static review | `supabase/schema.sql` opened with `DROP TABLE … CASCADE` | Written as a green-field setup script | Rewritten as an idempotent, non-destructive migration (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `DROP POLICY IF EXISTS` + recreate); the destructive reset is commented out and labelled; no `uuid-ossp` extension is used or required |

## 7.4 Monetization sweep (re-run at the end)

```
$ grep -rniE "subscription|premium|paywall|billing|purchase|entitlement|licen[cs]e|razorpay|upgrade|pricing" \
    --include="*.kt" --include="*.kts" --include="*.xml" --include="*.json" . | grep -v build/
```

| Hit | Classification |
|---|---|
| `app/src/main/assets/parser_config.json` — `"purchase"` in the debit keyword list | **Keep** — a detection keyword (bank alerts say "purchase of Rs…") |
| `ui/help/HelpScreens.kt` — "No subscription" | **Keep** — user-facing policy statement |
| `FEATURE_MATRIX.md`, `FEATURE_PARITY_AUDIT.md` — reference subscription classes | **Keep** — documentation of what was removed |
| `.apk_extract/**` — `SubscriptionActivityNew`, Razorpay, `pairip.licensecheck`, Play Billing | **Reference evidence only**, not in the app |
| `.agents/**` — unrelated agent skill docs | **Not app code** |

No monetization code, dependency, resource or database object exists in the app. The optional
cloud schema contains no subscription, entitlement or billing table, and no `uuid-ossp`
extension is installed or referenced.

## 7.5 Still not verified

> Superseded in part by **§9**, which re-tested most of this list on the device. Kept as the
> record of what the second pass had and had not exercised at the time.

- **Boot / unlock recovery** — receivers compile and are declared, but no reboot was performed.
- **Audibility** — speech, the generated chime and vibration were not listened to / felt; only
  synthesis requests and the settings that drive them were verified.
- **Cashbook edit**, reminder **mark-paid / monthly repeat / re-arm alarms**, the
  **minimum-amount** filter and **dedup pruning** — implemented and compiled, not driven.
- **System-settings deep links** (notification access, battery exemption) — present, not tapped.
- **Cashbook export** — the builder is unit-tested and the same share path was verified through
  History, but the Cashbook export button itself was not tapped.
- **Physical device / release build** — emulator debug only; R8 is off.
- **Widget configuration activity** — not implemented (the reference has one).
- **Camera QR scanning** — not implemented; only generation is verified.
- **The cloud SQL** — `supabase/schema.sql` was rewritten as an idempotent migration but was
  never executed: this workspace has no PostgreSQL instance and no Supabase project. Its syntax
  is reviewed, not proven. It is also dead code with respect to the app, which has no sync client.

## 8. Files changed in the parity pass

Created:

```
app/src/main/assets/parser_config.json                     (session 1; extended here)
app/src/main/java/com/vivekray898/payalert/ui/nav/Routes.kt
app/src/main/java/com/vivekray898/payalert/ui/components/ExportShare.kt
app/src/main/java/com/vivekray898/payalert/ui/home/HomeViewModel.kt
app/src/main/java/com/vivekray898/payalert/ui/home/HomeScreen.kt
app/src/main/java/com/vivekray898/payalert/ui/passbook/PassbookViewModel.kt
app/src/main/java/com/vivekray898/payalert/ui/passbook/PassbookScreen.kt
app/src/main/java/com/vivekray898/payalert/ui/analytics/AnalyticsViewModel.kt
app/src/main/java/com/vivekray898/payalert/ui/analytics/AnalyticsScreen.kt
app/src/main/java/com/vivekray898/payalert/ui/help/HelpScreens.kt
app/src/main/java/com/vivekray898/payalert/ui/qr/QrScreen.kt
app/src/main/java/com/vivekray898/payalert/ui/backup/BackupScreen.kt
app/src/main/java/com/vivekray898/payalert/export/TransactionExporter.kt
app/src/main/java/com/vivekray898/payalert/cashbook/CashbookReport.kt
app/src/main/java/com/vivekray898/payalert/upi/UpiLink.kt
app/src/main/java/com/vivekray898/payalert/upi/QrEncoder.kt
app/src/main/java/com/vivekray898/payalert/widget/PayAlertWidgetProvider.kt
app/src/main/res/layout/widget_payalert.xml
app/src/main/res/drawable/widget_background.xml
app/src/main/res/xml/widget_payalert_info.xml
app/src/main/res/xml/file_paths.xml
app/src/test/java/com/vivekray898/payalert/upi/UpiLinkTest.kt
app/src/test/java/com/vivekray898/payalert/upi/QrPayloadTest.kt
app/src/test/java/com/vivekray898/payalert/export/TransactionExporterTest.kt
app/src/test/java/com/vivekray898/payalert/cashbook/CashbookReportCalculatorTest.kt
app/src/test/java/com/vivekray898/payalert/analytics/AnalyticsCalculatorTest.kt
```

Modified:

```
app/build.gradle.kts                         + zxing core (QR generation)
app/src/main/AndroidManifest.xml             + widget receiver, + FileProvider
app/src/main/res/values/strings.xml          + widget strings
app/src/main/java/.../ui/PayAlertApp.kt      5 tabs + overlay stack that preserves tab state
app/src/main/java/.../ui/components/Common.kt        OverlayHeader; accessible SwitchRow
app/src/main/java/.../ui/transactions/TransactionsScreen.kt / TransactionsViewModel.kt
app/src/main/java/.../ui/cashbook/CashbookScreen.kt / CashbookViewModel.kt
app/src/main/java/.../ui/settings/SettingsScreen.kt
app/src/main/java/.../ui/diagnostics/DiagnosticsScreen.kt / DiagnosticsViewModel.kt
app/src/main/java/.../data/PaymentEventDao.kt        filtered()/observeFiltered(account, range)
app/src/main/java/.../data/CashbookDao.kt            range query, categories, insertIgnore
app/src/main/java/.../data/UpcomingPaymentDao.kt     insertIgnore
app/src/main/java/.../pipeline/PaymentPipeline.kt    onStored callback (widget refresh)
app/src/main/java/com/vivekray898/payalert/AppGraph.kt
app/src/main/java/.../backup/BackupManager.kt        transactional, truthful import counts
app/src/main/java/.../analytics/AnalyticsCalculator.kt  payees by merchant; daily in/out
.env.example                                 corrected: no credentials required
supabase/schema.sql                          non-destructive, idempotent migration
FEATURE_MATRIX.md, FEATURE_PARITY_AUDIT.md, BUILD_AND_VERIFICATION.md
```

Not committed — the working tree is left as-is for review. No file outside this project was
touched, and `local.properties` / `.env` were left alone.

---

# 9. Third pass (task reconciliation and the remaining gaps)

This pass started from the task list, not from the previous report. Every claim below was
re-established by reading the source, compiling it, or driving the installed APK and reading
the Room database back out of the device.

## 9.1 The task list against the repository

The list had 12 items. Reconciled by source inspection first, then by execution:

| # | Task | Was | Now | Where the evidence is |
|---|---|---|---|---|
| 1 | Gradle/Android scaffold that builds | done | done | §9.2 |
| 2 | Detection pipeline: SMS + notification → parse → Room | done | done | §9.4.5 |
| 3 | Config-driven parser (42 banks / 5 wallets / 95 senders) | done | done | §7.2, `ParserConfigTest` |
| 4 | TTS announcement + voice/sound/template settings | done | done | §7.2 (`Synthesis request for locale eng-IND`) |
| 5 | Foreground monitor + boot/unlock recovery | **partial** | **done** (boot verified; unlock blocked, §9.6) | §9.4.6 |
| 6 | Diagnostics screen + pipeline test | done | done | §7.2 |
| 7 | History: list/detail/search/filters/export | done | done | §7.2 |
| 8 | Analytics | done | done | §7.2 |
| 9 | Cashbook create/**edit**/delete/report/**export button** | **partial** | **done** | §9.4.1 |
| 10 | Upcoming payments + reminders (**mark paid**, **monthly roll-forward**) | **partial** | **done** | §9.4.2 |
| 11 | **MainActivity lifecycle** (service health check, review-prompt equivalent) | **not started** | **done** | §9.4.4 |
| 12 | Camera QR scanning | **not started** | **not applicable** (reason recorded) | §9.7 |

Also finished in this pass, because the task list named them and the source alone did not
prove them: the **minimum-amount filter** and **dedup pruning** (§9.4.3), and the
**system-settings deep links** (§9.4.4).

## 9.2 Build and unit tests (real output)

```
$ export JAVA_HOME=/Users/mamanjonpal/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
$ ./gradlew clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 7s
45 actionable tasks: 45 executed
EXIT=0

app/build/outputs/apk/debug/app-debug.apk   12,506,521 bytes
```

Per-class results, read from `app/build/test-results/testDebugUnitTest/*.xml`:

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| `parsers.ParserRegistryTest` | 19 | 0 | 0 |
| `parsers.ParserConfigTest` | 6 | 0 | 0 |
| `parsers.DedupKeyBuilderTest` | 5 | 0 | 0 |
| `settings.AnnouncementFormatterTest` | 4 | 0 | 0 |
| `analytics.AnalyticsCalculatorTest` | 7 | 0 | 0 |
| `cashbook.CashbookReportCalculatorTest` | 4 | 0 | 0 |
| `export.TransactionExporterTest` | 5 | 0 | 0 |
| `upi.UpiLinkTest` | 6 | 0 | 0 |
| `upi.QrPayloadTest` | 4 | 0 | 0 |
| `health.ServiceHealthEvaluatorTest` **(new)** | 8 | 0 | 0 |
| `health.ReviewPromptPolicyTest` **(new)** | 6 | 0 | 0 |
| **Total** | **74** | **0** | **0** |

## 9.3 Fixes made in this pass

| Found by | Failure | Cause | Fix |
|---|---|---|---|
| Driving the minimum-amount field on the device | `1500` could not be typed: the field became `1.0500…` | `remember(settings.minimumAmount)` re-seeded the text from the stored value on **every** keystroke, and each keystroke wrote the parsed value back to settings | The field owns its text (`remember` with no key); only the parsed value is persisted. It also renders `1000` instead of `1000.0`. Re-verified by typing one character at a time |
| Code review while wiring the health check | `PaymentMonitorService` had no way to answer "are you running?" | Only the system knows, and `getRunningServices` is deprecated | Added a `@Volatile isRunning` set in `onCreate`/`onDestroy`, read by the health check |

## 9.4 Device evidence (Pixel_8_Pro AVD, Android 14 / API 34)

Every item below was driven on the installed debug APK. Room was read back by pulling
`databases/payalert.db*` (including the `-wal`) through `run-as` and opening it with SQLite, so
the rows are quoted rather than inferred from the UI.

### 9.4.1 Cashbook edit and the export button

```
tap Edit → dialog pre-filled ('Edit entry', Chai / 40.0 / Food)
tap the Amount field → type 99 → tap Save

sqlite> select id,label,kind,amount,category,occurred_at from cashbook_entries;
1|Chai|debit|99.0|Food|1791912553013      <-- same row, updated in place
UI: Balance: -99.00   Out: 99.00   1 entries

tap Export → system share sheet opened with 'payalert-cashbook.csv'

$ cd cache/exports && cat payalert-cashbook.csv
occurred_at,direction,amount,label,category,note
2026-10-13 22:59,debit,99.00,Chai,Food,
```

The exported row carries the **edited** amount, so the export reads the live range and not a
stale snapshot.

### 9.4.2 Reminder mark-paid and monthly roll-forward

```
sqlite> select id,label,amount,repeat_monthly,is_paid,due_at from upcoming_payments;
1|Electricity|1500.0|0|0|1791948600000     due 14 Oct 2026, 09:00
$ dumpsys alarm | grep ReminderReceiver       -> scheduled for 1791948600000

tap 'Mark paid'
sqlite> 1|Electricity|1500.0|0|1|1791948600000     is_paid=1
        UI: '14 Oct 2026, 09:00 · Bills · paid',  To pay 0.00
        pending alarm batches for the package: 0     <-- alarm cancelled

tap 'Add reminder' → Broadband / 799 / 'To pay' / switch 'Repeat every month' → Save
sqlite> 2|Broadband|799.0|debit|1|0|1792035000000     monthly, pending, due 15 Oct 09:00
        alarm scheduled for 2026-10-15 09:00:00.000

tap 'Mark paid & repeat'
sqlite> 2|Broadband|debit|1|0|1794713400000            due_at moved to 2026-11-15 09:00
        UI: '15 Nov 2026, 09:00 · monthly · pending'
        alarm moved to 2026-11-15 09:00:00.000
```

### 9.4.3 Minimum-amount filter and dedup pruning

The dedup test needed a key that was older than the 3-day window, so a stale row was seeded
into the live database before the run (and one recent row, to prove pruning is not blanket):

```
seeded:  stale:test:key  seen_at = now - 10 days
         recent:test:key seen_at = now - 1 hour

$ adb emu sms send VK-ICICIB "Rs.3200.00 credited to A/c XX7788 … UTR 900900900900"

sqlite> select dedup_key, seen_at from dedup_index;
recent:test:key|1791912787000            <-- kept
utr:900900900900|credit|3200.00|…        <-- inserted
   (stale:test:key is gone)
```

Minimum amount, set through the Settings field (`Ignore amounts below` → `1000`):

```
$ adb emu sms send VK-SBININ "Rs.600.00 credited to A/c XX4433 … UTR 600600600600"
  log: 'Notification pipeline result: below_minimum';  no row, no dedup key
$ adb emu sms send VK-SBININ "Rs.1500.00 credited to A/c XX4433 … UTR 700700700700"
  sqlite> 8|1500.0|BIG SHOP|sms|utr:700700700700|credit|1500.00

field cleared to 0, then the identical ₹600 message re-sent:
  sqlite> 9|600.0|SMALL SHOP|sms|utr:600600600600|credit|600.00
```

One message, two outcomes depending only on the setting — which is the point of the filter.

### 9.4.4 MainActivity lifecycle: health check, deep links, rating prompt

```
launch with the monitor dead and a reminder pending:
  banner: 'Detection repaired on launch: started payment monitor, re-bound notification
           listener, re-armed 1 reminder(s)'
  dumpsys activity services -> PaymentMonitorService isForeground=true foregroundId=1001

revoke notification access, relaunch:
  banner: 'Detection needs attention: notification access'
  Home row: 'Notification access  Not granted'
  tap Fix -> com.android.settings/.Settings$NotificationAccessSettingsActivity

tap Settings 'Notification access'
  -> com.android.settings/.Settings$NotificationAccessSettingsActivity
deviceidle whitelist -com.vivekray898.payalert, then tap 'Ask to be exempt…'
  -> com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations   (Allow)
  deviceidle whitelist -> package present again

rating prompt (store file reset first so the run was deliberate):
  'Enjoying PayAlert?  PayAlert has announced 6 payments so far. …'
  Later      -> rated=false dismissed=false count=1 last=now;  relaunch: no prompt
  Rate on Play -> rated=true;  topResumedActivity = com.android.vending/…
                  (the store opened on this package's own listing)
  No thanks  -> dismissed=true;  relaunch: no prompt
```

### 9.4.5 Detection and duplicate suppression (re-run after the pipeline changed)

The same payment delivered twice — by SMS *and* mirrored by the messaging app's notification —
produced exactly one row and one dedup key:

```
$ adb emu sms send VK-AXISBK "Rs.777.00 debited from A/c XX2211 to VPA dup@ybl … UTR 555111222333"
  (sent twice, 5s apart)
sqlite> 7|777.0|dup@ybl|utr:555111222333|debit|777.00
sqlite> select count(*) from payment_events;   -> 6   (one new row, not two)
log: 'Notification pipeline result: duplicate' for the second sighting
```

### 9.4.6 Reboot recovery (real reboot)

```
$ adb reboot          # the app had been left running; it was never launched afterwards
$ adb shell getprop sys.boot_completed
1

$ adb shell dumpsys activity services com.vivekray898.payalert
  * ServiceRecord{…/.services.PaymentMonitorService}
    isForeground=true foregroundId=1001 types=40000000 foregroundNoti=Notification(channel=payalert_monitor …)

$ adb shell dumpsys alarm | grep -A3 ReminderReceiver
  type=RTC_WAKEUP origWhen=2026-11-15 09:00:00.000        <-- the rolled-forward reminder, re-armed
```

So `BOOT_COMPLETED` restarts the monitor **and** re-arms reminders, with no user action.

### 9.4.7 A caveat about the test device

While seeding the stale dedup row I deleted the app's `-wal` file with the app stopped, which
discarded the rows that were still only in the write-ahead log; the app then showed an empty
database. This was my mistake against the **emulator's** copy of the data, not a defect in the
app and not a change to any source file. It was recovered by restoring the database from the
pull taken immediately before, and the restored state was verified (4 payments) before the
run continued. The pushes that follow use `adb push /data/local/tmp/… + run-as cp`, which does
not truncate.

## 9.5 Device state left behind

- Notification-listener access, SMS/notification permissions and the battery exemption are
  **restored** to their working values; the temporary test PIN was cleared (`locksettings clear`)
  and `adb unroot` was called.
- The minimum-amount threshold is back to `0`; the review prompt is left in the `dismissed`
  state produced by the last test (`dismissed=true`), and one synthetic `recent:test:key` row is
  still in `dedup_index` — it matches no generated key and the app prunes it within 3 days.
- Stored user data (9 payments, 1 cashbook entry, 1 reminder) was intentionally left in place.

## 9.6 Still not verified

- **Unlock-triggered restart** (`ServiceRestartReceiver` → `USER_PRESENT`). The receiver is
  registered with the system (`dumpsys package … USER_PRESENT: … ServiceRestartReceiver filter`)
  and a genuine unlock was performed (keyguard engaged, PIN accepted). What could not be
  produced is a *dead monitor while the package stays deliverable*, which is the precondition
  for observing the restart: `am kill` refuses a process holding a foreground service,
  `kill -9` returns `EPERM` even after `adb root` (SELinux), `am stop-service` on another app is
  denied, and `am force-stop` puts the package in the stopped state, which suppresses implicit
  broadcasts — `am broadcast USER_PRESENT` is rejected as a protected broadcast. Proving it
  would need `setenforce 0` on the emulator, which I did not do. Boot recovery is verified
  (§9.4.6) and covers the same receiver pattern.
- **Audibility** — speech, the generated chime and vibration are still only verified as
  synthesis requests plus the settings that drive them; the emulator was not listened to.
- **Camera QR scanning** — not applicable, see §9.7.
- **Physical device / release build** — emulator debug only; R8 is off.
- **`supabase/schema.sql`** — still never executed; no PostgreSQL or Supabase project exists
  here, and it is dead code with respect to the app.

## 9.7 Camera QR scanning: not applicable

The decision, and why it is not a silent omission:

1. **It was excluded by the project's own requirements.** The rebuild was scoped to reimplement
   functionality independently, and the reference's scanner is a third-party barcode SDK
   (`journeyapps`) that was on the excluded list together with the camera permission.
2. **A capture cannot be exercised on this test target.** The AVD advertises camera features,
   but it runs headless with an emulated scene, so it cannot be aimed at a QR code; there is no
   way to produce the evidence a runtime claim requires. The project's standing rule is that a
   status may only be raised when the evidence type supports it.
3. **The dependency was not the blocker.** Maven Central is reachable from this workspace
   (`repo1.maven.org -> 200`), so CameraX could be added; the blocker is that the feature could
   not then be *tested*.
4. **The workflow is already covered, and verified.** PayAlert *generates* UPI QR codes — the
   link round-trips through zxing's decoder in a unit test and renders on device — and the payee
   can be typed directly, copied as a `upi://pay?…` link, or opened in any UPI app.

If camera scanning is wanted later, it needs a CameraX (or Camera2) capture path plus zxing
core for decoding — the decoder is already a dependency — and a test rig that can feed the
camera a known QR image.

---

# 10. Cross-app semantic dedup — notification path

This section exists because an earlier pass reported the three-notification case as verified
while its evidence was an **SMS** test, its `scripts/verify-dedup.sh` could not spoof a
posting package (`cmd notification post` usage is `post [flags] TAG TEXT` — the "package"
argument lands in the notification *tag*), the script's third payload used the **debit**
keyword "paid to" while the first two were credits (the semantic keys could never match),
and `PaymentPipelineTest.kt` did not exist. Every claim below was re-established by running
the commands and pasting their output.

Two real defects were found and fixed while producing this evidence:

1. **`DedupDao.pruneSemanticBefore` destroyed exact keys.** The old predicate
   `WHERE semantic_key IS NOT NULL AND seen_at < :cutoff` also matched accepted-payment
   rows (they carry their semantic key in the column), so the 10-minute prune deleted exact
   keys that row 3.4 of `FEATURE_MATRIX.md` promises survive 3 days. Fixed to
   `WHERE dedup_key = semantic_key AND seen_at < :cutoff`. Guarded by unit Test D and by
   the on-device seeded-prune check below.
2. **The pipeline could not be unit-tested** because `PaymentPipeline` took `PayAlertDatabase`
   directly, and a JVM test cannot build an in-memory Room database without Robolectric
   (not in this repo; no new dependency was added). Fixed by a narrow seam:
   `pipeline/PaymentStore.kt` (7-method interface + `RoomPaymentStore` delegating to the
   existing DAOs), wired in `AppGraph`. No behaviour change; the same DAO calls run.

## 10.1 Build after the seam change

```
$ export JAVA_HOME=/Users/mamanjonpal/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
$ ./gradlew :app:assembleDebug
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 5s
37 actionable tasks: 5 executed, 32 up-to-date
ASSEMBLE_EXIT=0
```

The Room processor re-validated every `@Query` (including the changed
`pruneSemanticBefore` SQL) during this build.

## 10.2 Unit tests — the real count, read from this run's XMLs

```
$ ./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 8s
TEST_EXIT=0

$ for f in app/build/test-results/testDebugUnitTest/*.xml; do
    echo "$(basename $f): $(grep -o 'tests="[0-9]*"' $f | head -1) …"
  done
TEST-…analytics.AnalyticsCalculatorTest.xml:             tests="7"  failures="0" errors="0"
TEST-…cashbook.CashbookReportCalculatorTest.xml:         tests="4"  failures="0" errors="0"
TEST-…export.TransactionExporterTest.xml:                tests="5"  failures="0" errors="0"
TEST-…health.ReviewPromptPolicyTest.xml:                 tests="6"  failures="0" errors="0"
TEST-…health.ServiceHealthEvaluatorTest.xml:             tests="8"  failures="0" errors="0"
TEST-…parsers.DedupKeyBuilderTest.xml:                   tests="20" failures="0" errors="0"
TEST-…parsers.ParserConfigTest.xml:                      tests="6"  failures="0" errors="0"
TEST-…parsers.ParserRegistryTest.xml:                    tests="19" failures="0" errors="0"
TEST-…pipeline.PaymentPipelineTest.xml (new):            tests="6"  failures="0" errors="0"
TEST-…settings.AnnouncementFormatterTest.xml:            tests="4"  failures="0" errors="0"
TEST-…upi.QrPayloadTest.xml:                             tests="4"  failures="0" errors="0"
TEST-…upi.UpiLinkTest.xml:                               tests="6"  failures="0" errors="0"
TOTAL: 95 tests, 0 failures, 0 errors
```

(The earlier "74 tests" figure was read from §9.2 of this file rather than from the run.
The 20-test `DedupKeyBuilderTest` had already been on disk; the new `PaymentPipelineTest`
brings the total to 95.)

## 10.3 The concurrent mutex test — source and result

`app/src/test/java/com/vivekray898/payalert/pipeline/PaymentPipelineTest.kt`, Test B:

```kotlin
@Test
fun `Test B - three concurrent notifications produce exactly one accepted`() = runBlocking {
    // Identical store latency to the control test below (100 ms checks, 50 ms
    // writes). The only difference from the control is the shared pipeline mutex:
    // the control triple-stores on this store, the mutex must not.
    val store = FakePaymentStore(checkDelayMs = 100, insertDelayMs = 50)
    val tts = CopyOnWriteArrayList<String>()
    val pipeline = pipeline(store, tts)

    val results = listOf(
        async(Dispatchers.Default) { pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow) },
        async(Dispatchers.Default) { pipeline.handle(paytmText, "notification", "net.one97.paytm", baseNow + 200) },
        async(Dispatchers.Default) { pipeline.handle(gpayText, "notification", "com.google.android.apps.nbu.paisa.user", baseNow + 400) }
    ).awaitAll()

    val reasons = results.map { it.reason }
    assertEquals("expected exactly one accepted, got $reasons", 1, reasons.count { it == ParseOutcome.ACCEPTED })
    assertEquals("expected exactly two duplicates, got $reasons", 2, reasons.count { it == ParseOutcome.DUPLICATE })
    assertEquals("expected exactly one stored event", 1, store.eventCount())
    assertEquals("expected exactly one announcement", 1, tts.size)
}
```

It runs three real coroutines on `Dispatchers.Default` (multithreaded), awaits all three
with `awaitAll`, and asserts exactly one `accepted`. The fake store's checks are slow
(100 ms) and its writes slower (50 ms), which is the concurrency shape of a real Room
store. Raw result line:

```
<testcase name="Test B - three concurrent notifications produce exactly one accepted"/>
```

(failures="0" on the file: `tests="6" failures="0" errors="0"`.)

**The control test makes this non-vacuous.** The same three payloads, same store latencies,
three *separate* pipelines (no shared mutex) produce **three** accepted — the race window is
real and the mutex is what closes it:

```
<testcase name="control - without a shared mutex the race window produces three accepted"/>
```

While writing this fake, its first version locked every store call; the thread timeline
then showed the queued checks ending in FIFO order and the first inserter always winning —
a store that serialises its own reads cannot demonstrate a check-and-insert race at all.
That timeline is why the final fake uses lock-free reads and a slow write.

The other four tests in the file:

```
<testcase name="Test A - three apps three wordings one payment collapse sequentially"/>
<testcase name="Test C - same payment six minutes later with a new reference is accepted"/>
<testcase name="Test C2 - repeated reference stays a duplicate even after six minutes"/>
<testcase name="Test D - semantic prune removes stale semantic row but keeps exact row"/>
```

`Test C` uses a new UTR for the second payment because the exact key is **timeless when a
reference exists** (`utr:REF|kind|amount`, pinned by `DedupKeyBuilderTest`: "reference number
dominates merchant and time"). `Test C2` pins that behaviour: the same reference six
minutes later stays `duplicate`.

## 10.4 The three UPI-identity stubs

`cmd notification post` cannot spoof a posting package, so three minimal APKs post real
notifications under the three UPI package identities. Built with SDK tools only
(`aapt2` + `javac` + `d8` + `apksigner`), no Gradle module, no new Maven artifact:

```
$ ./scripts/build-notif-stubs.sh
built stub-com_phonepe_app.apk for com.phonepe.app
built stub-net_one97_paytm.apk for net.one97.paytm
built stub-com_google_android_apps_nbu_paisa_user.apk for com.google.android.apps.nbu.paisa.user
BUILD_STUBS_EXIT=0

$ aapt2 dump badging stub-com_phonepe_app.apk | grep -E "^package|launchable"
package: name='com.phonepe.app' …
launchable-activity: name='stub.notifier.MainActivity' label='PhonePe'
package: name='net.one97.paytm' …     launchable-activity: … label='Paytm'
package: name='com.google.android.apps.nbu.paisa.user' … launchable-activity: … label='Google Pay'

$ adb install -r stub-*.apk
Success
$ adb shell pm list packages | grep -E 'phonepe|paytm|paisa'
package:com.phonepe.app
package:net.one97.paytm
package:com.google.android.apps.nbu.paisa.user
```

(The first install attempt failed with `INSTALL_FAILED_DEPRECATED_SDK_VERSION … found 0`;
`--min-sdk-version 24 --target-sdk-version 34` was added to the `aapt2 link` invocation and
the rebuild installed cleanly.)

Each stub bakes one body text into its dex, all three describing the **same ₹500 credit to
`zomato@ybl`**, with **three different UTRs** so only the semantic tier can collapse them:

```
Received Rs.500 from ZOMATO@ybl. UTR 111111111111                    (PhonePe)
Rs.500 credited to your account from zomato@ybl. Ref 222222222222    (Paytm)
You received ₹500 from Zomato. UPI Ref 333333333333                 (Google Pay)
```

## 10.5 Listener state

```
$ adb shell settings get secure enabled_notification_listeners
com.vivekray898.payalert/com.vivekray898.payalert.services.PaymentNotificationListener:…

$ adb shell dumpsys notification --noredact | grep -A6 'Live notification listeners'
    Live notification listeners (8):
      …
      ComponentInfo{com.vivekray898.payalert/com.vivekray898.payalert.services.PaymentNotificationListener} (user 0): android.service.notification.INotificationListener$Stub$Proxy@632c1c2
```

## 10.6 Three notifications, three apps, one payment

App data was cleared first (`pm clear`), then the listener was re-enabled and the app
launched once. `pm clear` also reset the runtime permissions, so the app re-requested them
on launch (visible in logcat as `GrantPermissionsViewModel … POST_NOTIFICATIONS …
RECEIVE_SMS`); the three stubs had `POST_NOTIFICATIONS` granted via `pm grant`.

```
$ adb logcat -c
$ adb shell am start -n com.phonepe.app/stub.notifier.MainActivity
$ sleep 0.7
$ adb shell am start -n net.one97.paytm/stub.notifier.MainActivity
$ sleep 0.7
$ adb shell am start -n com.google.android.apps.nbu.paisa.user/stub.notifier.MainActivity
$ sleep 4
$ adb logcat -d -s PayAlert:V
10-15 19:13:02.351 D PayAlert: Notification from com.phonepe.app: PhonePe Received Rs.500 from ZOMATO@ybl. UTR 111111111111 Received Rs.500 from ZOMATO@ybl. UTR 111111111111
10-15 19:13:02.386 I PayAlert: TTS utterance: Payment received. 500.00 from ZOMATO@ybl.
10-15 19:13:02.389 I PayAlert: Notification pipeline result: accepted
10-15 19:13:03.161 D PayAlert: Notification from net.one97.paytm: Paytm Rs.500 credited to your account from zomato@ybl. Ref 222222222222 Rs.500 credited to your account from zomato@ybl. Ref 222222222222
10-15 19:13:03.166 I PayAlert: Notification pipeline result: duplicate
10-15 19:13:03.885 D PayAlert: Notification from com.google.android.apps.nbu.paisa.user: Google Pay You received ₹500 from Zomato. UPI Ref 333333333333 You received ₹500 from Zomato. UPI Ref 333333333333
10-15 19:13:03.891 I PayAlert: Notification pipeline result: duplicate
```

Parser decision per notification: **accepted / duplicate / duplicate**. Exactly **one**
`TTS utterance:` line. The body text is doubled because the listener joins `EXTRA_TITLE`,
`EXTRA_TEXT` and `EXTRA_BIG_TEXT`; the parser matches the first occurrence and the outcome
is unaffected (the same doubled text appears in the unit tests' payloads).

The first `am start` batch used `com.phonepe.app/.MainActivity`, which resolved to a
component that does not exist (the manifest declares the full name `stub.notifier.MainActivity`
because `--rename-manifest-package` rewrites the relative reference but not the compiled
class); it posted nothing and produced no PayAlert log lines. The corrected component names
above are what produced this evidence.

## 10.7 Room rows after the three notifications

The database was pulled with its WAL through `run-as` (the device ships no `sqlite3`) and
opened locally:

```
$ adb shell run-as com.vivekray898.payalert cat databases/payalert.db     > /tmp/payalert_notif.db
$ adb shell run-as com.vivekray898.payalert cat databases/payalert.db-wal > /tmp/payalert_notif.db-wal
$ sqlite3 /tmp/payalert_notif.db "SELECT COUNT(*) FROM payment_events;"
1
$ sqlite3 /tmp/payalert_notif.db "SELECT id, kind, amount, currency, merchant, utr, source, source_detail, bank_or_upi FROM payment_events;"
1|credit|500.0|INR|ZOMATO@ybl|111111111111|notification|com.phonepe.app|PhonePe
$ sqlite3 /tmp/payalert_notif.db "SELECT dedup_key, semantic_key FROM dedup_index;"
utr:111111111111|credit|500.00|txn:credit:500.00:INR:-:zomato:5973572
txn:credit:500.00:INR:-:zomato:5973572|txn:credit:500.00:INR:-:zomato:5973572
$ sqlite3 /tmp/payalert_notif.db "SELECT COUNT(DISTINCT semantic_key) FROM dedup_index;"
1
```

**1 payment row, 2 dedup rows (the winner's exact key + one semantic row), 1 distinct
semantic key, 1 TTS utterance.** All three notifications carried *different* exact keys
(three UTRs), so the semantic tier is what collapsed them — that is the 3.1b claim, now
exercised on the notification path with three genuine package identities.

## 10.8 Six-minute regression on the notification path

The stub was rebuilt with a different reference (`UTR=444444444444 ./scripts/build-notif-stubs.sh`),
reinstalled, and posted 8.5 minutes after the first batch:

```
$ adb logcat -c
$ adb shell am start -n com.phonepe.app/stub.notifier.MainActivity
$ sleep 4
$ adb logcat -d -s PayAlert:V
10-15 19:21:33.061 D PayAlert: Notification from com.phonepe.app: PhonePe Received Rs.500 from ZOMATO@ybl. UTR 444444444444 …
10-15 19:21:33.095 I PayAlert: TTS utterance: Payment received. 500.00 from ZOMATO@ybl.
10-15 19:21:33.100 I PayAlert: Notification pipeline result: accepted

$ sqlite3 /tmp/payalert_final.db "SELECT COUNT(*) FROM payment_events;"
2
$ sqlite3 /tmp/payalert_final.db "SELECT id, kind, amount, merchant, utr, source, source_detail FROM payment_events ORDER BY id;"
1|credit|500.0|ZOMATO@ybl|111111111111|notification|com.phonepe.app
2|credit|500.0|ZOMATO@ybl|444444444444|notification|com.phonepe.app
```

Two rows, two utterances. The semantic keys differ only by the 5-minute bucket
(`…:zomato:5973572` vs `…:zomato:5973574`) — the bucket maths matched the real elapsed
time, not a clock trick (the pipeline always uses `System.currentTimeMillis()`).

## 10.9 Seeded prune check — the 10-minute semantic window on the device

Before the regression payment, the app was stopped and two stale rows were seeded into
`dedup_index` at `now − 11 minutes` (pulled → edited locally → pushed back through
`/data/local/tmp` + `run-as cp`, which does not truncate):

```
txn:credit:777.00:INR:-:seededmerchant:99999 | seen_at=1792071468876   (semantic-only row)
utr:999999999999|credit|777.00              | semantic_key=txn:credit:777.00:INR:-:seededmerchant:99999
                                                     seen_at=1792071468876   (exact row carrying a semantic key)
```

The accepted regression payment then ran both prunes. Raw state afterwards:

```
$ sqlite3 /tmp/payalert_final.db "SELECT dedup_key, semantic_key, seen_at FROM dedup_index ORDER BY seen_at;"
utr:999999999999|credit|777.00|txn:credit:777.00:INR:-:seededmerchant:99999|1792071468876
utr:111111111111|credit|500.00|txn:credit:500.00:INR:-:zomato:5973572|1792071782353
txn:credit:500.00:INR:-:zomato:5973572|txn:credit:500.00:INR:-:zomato:5973572|1792071782353
utr:444444444444|credit|500.00|txn:credit:500.00:INR:-:zomato:5973574|1792072293062
txn:credit:500.00:INR:-:zomato:5973574|txn:credit:500.00:INR:-:zomato:5973574|1792072293062

stale semantic row present?|0     <-- pruned (dedup_key = semantic_key, 11 min old)
seeded exact row present?  |1     <-- SURVIVES (dedup_key != semantic_key) — the fixed predicate
batch-1 semantic row present?|1   <-- 8.5 min old at prune time, inside the 10-minute window
```

Under the old predicate the seeded **exact** row would also have been deleted (its
`semantic_key` is not null); with the fix it survives, while its semantic-only twin is
pruned. This is the on-device half of the row 3.6 evidence; unit Test D pins the same
predicate on the JVM.

## 10.10 What this section does NOT cover

- **Audibility.** The TTS evidence is the engine-accepted utterance line plus the platform
  synthesis; nothing was listened to.
- **The reference APK's exact dedup constants.** The 5-minute bucket and 10-minute prune are
  PayAlert's own constants; the reference exposes no readable equivalent beyond the
  behaviour rebuilt here.
- **SMS path.** Not re-run in this pass; §9.4.5 still stands for it on its own.

---

# 11. UI/UX rebrand

Scope of this pass: the visual layer only — `ui/` plus `res/`. No detection, parsing, dedup,
storage, sync or TTS code was touched. The palette is derived from the launcher icon and the
defects listed in the brief were fixed screen by screen.

## 11.1 Build

```
$ cd payalert && ./gradlew clean :app:assembleDebug
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:processDebugJavaRes
> Task :app:mergeDebugJavaResource
> Task :app:dexBuilderDebug
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 7s
38 actionable tasks: 26 executed, 12 from cache
```

APK produced: `app/build/outputs/apk/debug/app-debug.apk`, 12,767,737 bytes.

## 11.2 Unit tests

```
$ cd payalert && ./gradlew :app:testDebugUnitTest --rerun-tasks
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 7s
26 actionable tasks: 26 executed
```

Counted from this run's result XMLs (`--rerun-tasks`, so nothing came from cache):

```
TOTAL tests=95 failures/errors=0 skipped=0 suites=12
  com.vivekray898.payalert.analytics.AnalyticsCalculatorTest  tests=7
  com.vivekray898.payalert.cashbook.CashbookReportCalculatorTest  tests=4
  com.vivekray898.payalert.export.TransactionExporterTest  tests=5
  com.vivekray898.payalert.health.ReviewPromptPolicyTest  tests=6
  com.vivekray898.payalert.health.ServiceHealthEvaluatorTest  tests=8
  com.vivekray898.payalert.parsers.DedupKeyBuilderTest  tests=20
  com.vivekray898.payalert.parsers.ParserConfigTest  tests=6
  com.vivekray898.payalert.parsers.ParserRegistryTest  tests=19
  com.vivekray898.payalert.pipeline.PaymentPipelineTest  tests=6
  com.vivekray898.payalert.settings.AnnouncementFormatterTest  tests=4
  com.vivekray898.payalert.upi.QrPayloadTest  tests=4
  com.vivekray898.payalert.upi.UpiLinkTest  tests=6
```

95 tests, 0 failures, 0 errors, 0 skipped — unchanged from the previous pass, which is the
expected result for a change confined to `ui/` and `res/`.

## 11.3 Screenshot commands

Device: `Pixel_8_Pro` AVD, Android 14 (API 34), 1344×2992 @ 480 dpi. The icons and the
six screens were driven with `adb shell input tap` at coordinates read back from
`uiautomator dump` — never guessed:

```
# bottom-nav item bounds, read from the accessibility tree
[73,2824][176,2872]     Home
[333,2824][462,2872]    History
[584,2824][758,2872]    Cashbook
[857,2824][1033,2872]   Upcoming
[1146,2824][1292,2872]  Settings

# one screen, end to end (this is the loop /tmp/payalert/capture.sh runs)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.vivekray898.payalert
adb shell am start -n com.vivekray898.payalert/.ui.MainActivity   # repairs the monitor,
sleep 5                                                          #   raising the notice
adb shell input keyevent KEYCODE_HOME
adb shell am start -n com.vivekray898.payalert/.ui.MainActivity   # nothing to repair now
sleep 3
adb shell input tap 397 2848                                      # the History tab
sleep 3
adb exec-out screencap -p > screenshots/after/history.png
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml > /tmp/payalert/ui_day_history.xml

# day pass
adb shell cmd uimode night no
# night pass
adb shell cmd uimode night yes
```

The two `am start` calls matter: the first startup repairs the monitor and raises the
transient *"Detection repaired on launch"* notice, the second finds nothing to repair so the
notice clears and the screen is photographed clean. Analytics is an overlay rather than a
tab, so it is opened by tapping the chart action in the History app bar; that action's
coordinates were themselves read from the History dump (`[936,211][1008,283]` →
tap `972 247`).

Data in the frames is real, not mocked: it came from twelve bank SMS messages delivered with
`adb emu sms send`, picked up by the SMS receiver and by the Messages app's notification, which
also proves the cross-detector dedup still works on this build —

```
I PayAlert: TTS utterance: Payment received. 3,500.00 from rahul@okhdfcbank.
I PayAlert: Notification pipeline result: duplicate      <-- the second sighting of it
I PayAlert: TTS utterance: Payment sent. 499.00 to swiggy@ybl.
I PayAlert: Notification pipeline result: duplicate
```

which is why Home's `Stored payments` reads `8` while twelve messages were delivered
(two of the twelve were identical repeats of a message that was already stored, and the
Messages-app copy of every message is suppressed as a duplicate of the SMS-receiver copy).

## 11.4 Files

```
$ cd payalert && ls -l screenshots/after screenshots/after/night
screenshots/after/analytics.png               228089
screenshots/after/cashbook.png                138395
screenshots/after/history.png                 267082
screenshots/after/home.png                    228785
screenshots/after/settings.png                232592
screenshots/after/upcoming.png                111505
screenshots/after/night/analytics.png         225861
screenshots/after/night/cashbook.png          140355
screenshots/after/night/history.png           267446
screenshots/after/night/home.png              225794
screenshots/after/night/settings.png          225455
screenshots/after/night/upcoming.png          111429
```

Every file is non-zero and 1344×2992. Day frames measure a mean luminance of 223–230, night
frames 32–38, which is how the night column is shown to be a real dark-scheme capture rather
than a repeat of the light one.

| Frame | What it proves |
|---|---|
| `after/home.png` | Teal `In` / orange `Out` chips, a `headlineSmall` navy `Net`, six icon-led detection rows coloured by state, a filled `Transactions` + outlined `Payment QR`, and recent payments with a leading direction avatar and the merchant rendered exactly as stored (`jiorecharge@ybl`, `ACME PVT LTD`) |
| `after/history.png` | `In`/`Out`/`Items` stat chips, a search field with a leading glyph and trailing clear action, `DIRECTION`/`RANGE`/`ACCOUNT` chip groups on separate labelled rows, the app bar carrying `Analytics` / `Export history` / `Clear filters`, and rows reading `via SMS · HDFC Bank` rather than `notification` |
| `after/analytics.png` | The four range chips on one line in a scroll row, the summary card, spending-by-category bars, a populated `Top payees`, the single-day header `Today`, and the tonal right-aligned `Share summary (CSV)` that is no longer full width |
| `after/cashbook.png` | `Balance` over `₹0.00` in `displaySmall`, `In`/`Out`/`Entries` chips, filled `+ Add entry` beside outlined `Report`, `More actions` in the app bar, and an icon-led empty state with `+ Add your first entry` |
| `after/upcoming.png` | `To pay` / `To receive` chips, a single filled `+ Add reminder`, an icon-led empty state, and no `Re-arm alarms` on the surface — the a11y dump of this frame contains `Add reminder` only |
| `after/settings.png` | One consistent card header style, the three sound-mode chips on a single row, permission values teal when granted and orange when not, and secondary actions rendered as outlined buttons |
| the six `after/night/*.png` | The same six screens under `cmd uimode night yes`: dark page background `rgb(16,19,28)`, dark card surface `rgb(23,27,36)`, brightened navy primary `rgb(111,104,168)`, dark-teal nav indicator `rgb(3,71,61)` |

Supporting documents:

- `screenshots/PALETTE.md` — the extraction command, its output, the four hex values, the
  semantic mapping and the night variants.
- `screenshots/COMPARISON.md` — per-screen defect → change table with the frames embedded.
- `FEATURE_MATRIX.md` §7 (UI/UX, six screens + bottom navigation) and §8 (Theme, palette
  source and the dynamic-colour decision).

## 11.5 Changes made in this pass

| File | Change |
|---|---|
| `ui/theme/Theme.kt` | `Brand` constants reset to the measured icon values (`#122550` / `#F65026` / `#058E7A` / `#FE8D13`); light and dark schemes rebuilt from them; dynamic colour left off |
| `res/values/colors.xml`, `res/values-night/colors.xml` | Platform colours (status bar, window background) realigned to the icon palette; night set hand-tuned |
| `res/drawable/ic_*.xml` | 19 Material vector icons added (5 navigation + 14 UI, the 14th being `ic_volume_up` for the Voice section); the earlier stroke-only scratch icons removed |
| `ui/PayAlertApp.kt` | `NavigationBar` items now carry an icon plus a single label, with a teal-derived indicator; the detection notice moved to the bottom of the content area and the content area reserves room for it, so it can no longer cover a screen's app bar |
| `ui/nav/Routes.kt` | The two-letter abbreviation field removed; the transactions tab is labelled `History` |
| `ui/components/Common.kt` | `StatChip` added; `SearchField` gained a leading glyph and a trailing clear button; `EmptyState` gained a drawn icon, a drawn action and a tint; `ValueRow` gained a value colour; the three shared status colours added |
| `ui/SourceLabel.kt` | New: maps the raw detector string and sender id to `via SMS · HDFC Bank` / `via PhonePe` |
| `ui/home/HomeScreen.kt` | Stat chips, icon-led detection rows with semantic colours, ranked actions, merchant rendered as stored |
| `ui/transactions/TransactionsScreen.kt` | Rewritten around a top app bar (`Analytics` / `Export history` / `Clear filters`), grouped chip rows, stat chips and a direction avatar per row |
| `ui/analytics/AnalyticsScreen.kt` | Scrollable range chips, stat chips, explicit empty states, `Today` header, tonal right-aligned export |
| `ui/cashbook/CashbookScreen.kt` | Prominent centred balance, chip summary, icon-bearing ranked buttons, app-bar overflow, icon-led empty state |
| `ui/passbook/PassbookScreen.kt` | Stat chips, `Re-arm alarms` moved to the overflow, icon-led rows with an outlined `Mark paid`, icon-led empty state |
| `ui/settings/SettingsScreen.kt` | Sound chips on one scroll row, state-coloured permission values, secondary actions demoted to outlined |

## 11.6 Not verified in this pass

- **Anything audible.** No sound or speech was listened to; the TTS log lines above are engine
  acceptances, not audibility checks.
- **A physical device.** All evidence is from the `Pixel_8_Pro` AVD. Font metrics, display
  cutouts and gesture-nav insets were not exercised on real hardware.
- **Dynamic-colour devices with a non-default wallpaper.** Dynamic colour is off by code
  inspection, and the AVD has a default wallpaper, so "the brand palette survives a themed
  device" is argued from the code path rather than photographed on a themed device.
- **RTL layouts.** `android:supportsRtl="true"` is still declared, but no screen was captured
  with an RTL locale.
- **The Cashbook / Upcoming / Settings populated states.** These three screens were empty on
  the seeded device, so their captured frames show the empty and default states; the populated
  reminder and cashbook rows are covered by the runtime evidence already recorded in §9.4
  rather than by a new screenshot.
