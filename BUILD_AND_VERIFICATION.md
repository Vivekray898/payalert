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
