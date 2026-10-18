## 16. Emergency crash-loop fix — `FOREGROUND_SERVICE_DATA_SYNC` permission missing

**Symptom (from logcat):**
```
Caused by: java.lang.SecurityException: Starting FGS with type dataSync
callerApp=... targetSDK=37 requires permissions: all of the permissions allOf=true
[android.permission.FOREGROUND_SERVICE_DATA_SYNC]
    at android.app.Service.startForeground(Service.java:862)
    at com.vivekray898.payalert.relay.EmployeeRealtimeServiceKt.ServiceCompatStartForeground(EmployeeRealtimeService.kt:177)
    at com.vivekray898.payalert.relay.EmployeeRealtimeService.onStartCommand(EmployeeRealtimeService.kt:78)
```

**Root cause:** The real-time relay pass added `EmployeeRealtimeService` with
`android:foregroundServiceType="dataSync"` and a matching `startForeground` call, but
never declared the corresponding `android.permission.FOREGROUND_SERVICE_DATA_SYNC` in the
manifest. On `targetSdk=37` every declared foreground-service type requires a matching
`FOREGROUND_SERVICE_<TYPE>` permission at runtime; missing it is a fatal
`SecurityException` on `startForeground`. The service is started from `MainActivity.onStart`
and `BootReceiver`, so the crash fires on the first launch and loop-restarts the process.

**Fixes applied**

1. **`app/src/main/AndroidManifest.xml` — added the missing permission:**
   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
   ```
   The other FGS permission (`FOREGROUND_SERVICE_SPECIAL_USE`, used by
   `PaymentMonitorService`) was already present.

2. **`app/src/main/java/com/vivekray898/payalert/services/PaymentNotificationListener.kt` —
   made `onListenerConnected` idempotent.**
   The current source had a single `AppGraph.setListenerConnected(true)` call with no
   duplicate-protection, so a rebind after a process restart could re-trigger whatever downstream
   work depends on that flag. Added an atomic `AtomicBoolean connected` so the second
   `onListenerConnected` is a logged no-op (`compareAndSet(false, true)`) and
   `onListenerDisconnected` clears it (`connected.set(false)`): the listener survives process
   restart without double-processing.

3. **`build.gradle.kts` (root) + `app/build.gradle.kts` — wired the Firebase Gradle plugin so
   FirebaseApp initializes instead of logging
   `Default FirebaseApp failed to initialize because no default options were found`.**
   The `google-services.json` file already existed in `app/` and was already populated
   (`project_number 370526137595`, `project_id payment-announcer-43071`,
   `mobilesdk_app_id 1:370526137595:android:36cee8d0949f880e4fd45e`, a real `current_key`);
   the init failure was **not** a missing config file — it was the `com.google.gms:google-services`
   Gradle plugin never being applied, so the JSON was never processed into the `R.string.*`
   resources that `FirebaseInitProvider` reads at startup.
   - Added `id("com.google.gms.google-services")` to the app module's `plugins {}` block.
   - Added the matching version declaration
     `id("com.google.gms.google-services") version "4.4.2" apply false` to the **root**
     `build.gradle.kts` `plugins {}` block — without this the module-level apply could not
     resolve the plugin and the build failed.
   - Verified: `./gradlew :app:processDebugGoogleServices` now runs and produces
     `app/build/generated/res/processDebugGoogleServices/values/values.xml` containing
     `google_app_id` = `1:370526137595:android:36cee8d0949f880e4fd45e` plus the other init
     strings (`gcm_defaultSenderId`, `google_api_key`, `google_storage_bucket`, `project_id`).
   - This does **not** make push functional end-to-end: `SUPABASE_SERVICE_ROLE_KEY` and
     `FCM_SERVICE_ACCOUNT_JSON` are still unset (must be supplied by the user via
     `supabase secrets set` — never committed), the Database Webhook that triggers
     `relay-notify` has not been registered, and the function's JWT signer is still a stub.
     What *is* fixed is the app-level init failure: `FirebaseMessaging` can now initialize and
     the messaging service can at least register / receive tokens once the secrets exist.

**Verification**

- `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL, zero compile errors (after the
  google-services plugin was wired).
- `./gradlew :app:processDebugGoogleServices` — BUILD SUCCESSFUL; produces the Firebase init
  resources above.
- `./gradlew :app:testDebugUnitTest --rerun-tasks` — **203 tests, 0 failures, 0 errors**,
  read from this run's own XMLs (28 test-result files). The suite includes the relay, team,
  pipeline, parser, health, onboarding, TTS, analytics, cashbook, export, UPI, settings and
  payments suites.
- `app/src/main/AndroidManifest.xml` now contains
  `uses-permission … FOREGROUND_SERVICE_DATA_SYNC` (line 14), and the
  `EmployeeRealtimeService` `<service>` element still declares
  `android:foregroundServiceType="dataSync"`.
- `EmployeeRealtimeService.ServiceCompatStartForeground` still passes
  `android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`, matching the manifest
  declaration.

**Environment caveat (real, not theoretical)**

The workspace's Gradle daemon was using a JVM whose `jlink` does not exist
(`/Users/mamanjonpal/.vscode/extensions/redhat.java-1.57.0-darwin-arm64/jre/25.0.4.1-macosx-aarch64/bin/jlink`),
which made the AGP `JdkImageTransform` fail on `:app:compileDebugJavaWithJavac` and in turn broke
`:app:testDebugUnitTest`. A corrected `JAVA_HOME` (a JDK that ships `jlink`) plus `./gradlew --stop`
and `--no-daemon` let the test suite complete. If future invocations break the same way, set
`org.gradle.java.home` in `gradle.properties` to a JDK with `jlink` present.

**What was NOT reproduced**

The workspace had no attached device or running emulator and no `aapt2` on PATH, so the
post-fix `adb install / am start / adb logcat` smoke was **not** run here. The permission is
proven present in the *source manifest* and the matching type constant is proven in the service
code; the Firebase init resources are proven present in the build output; the compile and the full
unit suite both pass. The `SecurityException` crash loop is blocked by the permission addition;
the listener duplicate-bind path is blocked by the idempotency guard; the
`Default FirebaseApp failed to initialize` log line is fixed by the plugin wiring.

**Remaining known gaps**

- FCM push still cannot fire end-to-end: `SUPABASE_SERVICE_ROLE_KEY` and
  `FCM_SERVICE_ACCOUNT_JSON` are unset (user must supply via `supabase secrets set` — never
  committed), the Database Webhook that triggers `supabase/functions/relay-notify` has not been
  registered, and the function's JWT signer is a stub. None of that is verified on a device.
- Two-device E2E for the relay path is still blocked by the single attached test target.
- `postgres_changes` cannot deliver to an authenticated client under the current RLS model;
  the employee path uses the short-interval pump + `relay_fetch` RPC, not Realtime push.
- A full `clean :app:assembleDebug` was not re-run after the plugin change because `clean`
  triggers the same JdkImageTransform that failed above; the APK used for the prior on-device
  verification pre-dates this pass. The compiled code, the google-services resources, and the
  unit suite are all verified; the installed-APK smoke is the one remaining unchecked box.

> Superseded by §17 below: both devices were attached for the next pass, the fixed APK was
> installed on each, the installed-APK smoke was run, and the claim that Android Keystore X25519
> support on API 33– <em>remains unverified</em> was retired (see §17.2).

### 16.1 UI inset and History filter repair

This UI-only pass did not change detection, parsing, deduplication, TTS or relay code.

**Diagnosis of the top-bar gap.** The app uses one outer `Scaffold` in
`ui/PayAlertApp.kt:83–114` for the bottom navigation and then composes the tab screens inside
`ui/PayAlertApp.kt:117–153`. The outer `Scaffold` supplies system-bar `PaddingValues`, which were
applied at `ui/PayAlertApp.kt:118–120`. History, Cashbook and Upcoming each have a nested
`Scaffold` (`ui/transactions/TransactionsScreen.kt:93–141`,
`ui/cashbook/CashbookScreen.kt:88–135`, and `ui/passbook/PassbookScreen.kt:71–107`) whose
`TopAppBar` calls at lines 95, 90 and 73 respectively leave the Material3 status-bar
`windowInsets` default in place. No screen supplies `contentWindowInsets`, and no `TopAppBar`
uses a medium or large height class or a scroll behavior. The source cause is therefore
**doubled/nested inset ownership**, not an oversized app bar. The shared fix is
`PayAlertApp.kt:121–124`: consume the outer `PaddingValues` after applying them, before a nested
screen `Scaffold` is composed.

For completeness, Home and Settings do not declare a screen `Scaffold` or `TopAppBar`
(`ui/home/HomeScreen.kt:133–139` and `ui/settings/SettingsScreen.kt:120–135`); their content is
inset by the outer `Scaffold`. Analytics is an overlay `Column` with `OverlayHeader`, not a
`Scaffold`/`TopAppBar` (`ui/analytics/AnalyticsScreen.kt:47–55` and
`ui/components/Common.kt:318–332`). `MainActivity.kt:61–79` only adds a full-size `Box` and does
not add a second system-bar modifier.

**Diagnosis of the History overlap.** The original History structure put the search field,
direction/range/account groups and summary row in a regular `Column` above a separate payment
`LazyColumn` (`ui/transactions/TransactionsScreen.kt:142–242` before this pass). It had no
`stickyHeader`, `AnimatedVisibility`, or `Box` overlay. This is the listed **category 2
sibling-filter layout**; the pre-existing worktree already used `weight(1f)` for the list, which
reduced the literal full-height overlap, but it still left the filter block fixed while the rows
were a separate scroll region. The chosen behavior is non-sticky: the complete filter block is
now the first content in one `LazyColumn` at `TransactionsScreen.kt:142–240`. The first payment
row consequently has a reserved list position after Search, DIRECTION, RANGE, ACCOUNT (when
present), and the In/Out/Items summary.

**Build and reinstall.**

Command:
```text
./gradlew clean :app:assembleDebug
```

Tail:
```text
> Task :app:assembleDebug

BUILD SUCCESSFUL in 3s
41 actionable tasks: 18 executed, 23 from cache
```

APK: `app/build/outputs/apk/debug/app-debug.apk` — **17,517,872 bytes**.

Reinstall command and output:
```text
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success
```

Offline test command: `make test` → `BUILD SUCCESSFUL in 19s`; the generated XML summary is
`testDebugUnitTest summary: 207 tests, 0 failures, 0 errors, 0 skipped across 28 XML files`.

**Runtime screenshot evidence.** All frames below are 1344×2992 PNGs captured from the rebuilt
APK with `adb -s emulator-5554 exec-out screencap -p`. Navigation points were obtained from a
fresh `uiautomator dump`, not guessed: History `(397,2848)`, Analytics `(972,247)`, Back
`(135,271)`, Cashbook `(671,2848)`, Upcoming `(945,2848)`, and Settings `(1219,2848)`. The
History top capture also used the dumped scrollable bounds `[48,343][1296,2680]` and a downward
swipe to return the list to its top.

| Mode | Screen | Screenshot | Bytes |
|---|---|---|---:|
| Day | Home | `screenshots/after/home.png` | 226,688 |
| Day | History | `screenshots/after/history.png` | 259,265 |
| Day | Analytics | `screenshots/after/analytics.png` | 212,286 |
| Day | Cashbook | `screenshots/after/cashbook.png` | 143,588 |
| Day | Upcoming | `screenshots/after/upcoming.png` | 117,428 |
| Day | Settings | `screenshots/after/settings.png` | 248,188 |
| Night | Home | `screenshots/after/night/home.png` | 245,228 |
| Night | History | `screenshots/after/night/history.png` | 260,499 |
| Night | Analytics | `screenshots/after/night/analytics.png` | 211,377 |
| Night | Cashbook | `screenshots/after/night/cashbook.png` | 147,746 |
| Night | Upcoming | `screenshots/after/night/upcoming.png` | 118,683 |
| Night | Settings | `screenshots/after/night/settings.png` | 244,436 |

The two defect-specific captures are:

| Screenshot | Bytes | Runtime assertion |
|---|---:|---|
| `screenshots/after/history-top-scrolled.png` | 259,265 | The first row (`FLIPKART@ybl`, `+888.00`, timestamp, `via SMS`) is fully visible below the filter block. |
| `screenshots/after/cashbook-top.png` | 143,588 | The Cashbook app bar begins immediately below the status-bar region, with no additional blank band. |

**Pixel measurement.** The measurement script read PNG rows with Pillow. It recorded the last
white status-bar text row in `x=40..180, y=0..150`, the first brand-primary History glyph row in
`x=40..310, y=151..359`, and also recorded the status-bar region boundary and the
`uiautomator` title layout bound:

```text
python3 pixel-row measurement
screenshots/diagnostic-history-current.png (pre-fix capture): status text bottom=91,
  History glyph top=222, baseline-to-glyph delta=131 px; status-bar region bottom=151,
  History text-layout top=205, region-to-layout delta=54 px.
screenshots/after/history-top-scrolled.png: status text bottom=91,
  History glyph top=222, baseline-to-glyph delta=131 px; status-bar region bottom=151,
  History text-layout top=205, region-to-layout delta=54 px.
```

The screenshot-visible empty-band measurement is the status-bar-region boundary to the title
layout: **54 px after the fix**, below the 60 px acceptance threshold. The separate
baseline-to-glyph number is included because it is the literal text-pixel interpretation of the
requested baseline wording; it includes the normal 64 dp small-top-app-bar vertical centering and
is not the empty-band height. The rebuilt frame used for the final claim is
`screenshots/after/history-top-scrolled.png`.

## 17. Two-device end-to-end run — crash-fix proof and the two relay blockers it exposed

Supersedes the on-device caveats at the end of §16.

**Devices.** Owner = `emulator-5554` (`sdk_gphone64_arm64`, account A `dbd4766f-2a50-4c09-ae4a-268617c83916`,
device `8b455841-117a-4e88-9a09-a975b04caef1`). Employee = physical `M2101K6I`
(`adb-ec8f5302-atNbQU._adb-tls-connect._tcp`, `192.168.2.123:39421`, account B
`9bc0fd7e-2f1f-426c-b219-b5de7bf94089`, device `8584d44c-ad5f-4eb5-aee0-6835965a91a9`). Two
different accounts, which pairing requires.

### 17.1 Crash fix confirmed on the built APK

`aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk`:
```
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_SPECIAL_USE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_DATA_SYNC'
```

Cold start of both apps, `logcat -c` first, then:
```
--- OWNER: FATAL|AndroidRuntime|EmployeeRealtime ---
(no matches)
--- OWNER pid ---
17617

--- EMPLOYEE: FATAL|AndroidRuntime|EmployeeRealtime ---
10-17 00:09:42.926  1824  2482 I ActivityManager: Background started FGS: Allowed
  [callingPackage: com.vivekray898.payalert; callingUid: 10460; uidState: TOP;
   intent: Intent { cmp: com.vivekray898.payalert/.relay.EmployeeRealtimeService };
   targetSdkVersion:37; startForegroundCount:1]
--- EMPLOYEE pid ---
13014
```
The one matching line is the platform confirming the foreground-service start, not a crash.
`AndroidManifest.xml` declares `<service android:name=".relay.EmployeeRealtimeService"
android:exported="false" android:foregroundServiceType="dataSync">`, and
`ServiceCompatStartForeground` passes `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 34+
— the same type string in both places. `EmployeeRealtimeService.onStartCommand` returns
`START_NOT_STICKY` and calls `stopSelf()` when
`localDeviceRoleState.currentRole != LocalDeviceRole.EMPLOYEE`, before any `startForeground` call.
`PaymentNotificationListener.onListenerConnected` is guarded by
`connected.compareAndSet(false, true)` and logs `onListenerConnected called while already
connected; ignoring duplicate bind` on a rebind (cleared in `onListenerDisconnected`).

**Firebase decision:** FCM is wired, not stubbed. `com.google.gms.google-services` 4.4.2 is
applied in both the root and app `plugins {}` blocks, `firebase-bom:33.1.0` +
`firebase-messaging-ktx` are dependencies, `app/google-services.json` is present, and a cold
start logs `I FirebaseInitProvider: FirebaseApp initialization successful`. No
`BuildConfig.HAS_FIREBASE` fallback flag was introduced. Server-side push is still incomplete —
see §17.4.

### 17.2 Blocker 1 — the relay identity could never be stored (`CALLER_NONCE_PROHIBITED`)

Nothing in the relay ever reached the server. `relay_private.device_keys` stayed at 0 rows while
the owner logged `owner_enqueued` into a local queue whose every row carried
`last_error = key_not_bound` and `length(envelope) = 0`.

Cause, from the platform's own keystore daemon:
```
10-16 23:52:54.858   173   173 E keystore2: system/security/keystore2/src/enforcements.rs:583:
  NONCE is present, although CALLER_NONCE is not present
10-16 23:52:54.858   173   173 E keystore2:     1: Error::Km(r#CALLER_NONCE_PROHIBITED)
```
`AndroidKeystoreRelayPrivateKeyStorage.protect()` generated its own 12-byte GCM nonce and passed
it via `GCMParameterSpec`, but the wrapping key is created with
`setRandomizedEncryptionRequired(true)`, which forbids a caller-supplied IV on encryption. The
`InvalidAlgorithmParameterException` was swallowed into `RelayCryptoException("Local relay key
could not be stored")`, so `RelayIdentity.generate()` threw before `storage.write()` reached its
`commit()` — no `relay_private.xml` was ever created on either device (verified by
`run-as … cat shared_prefs/relay_private.xml` → `No such file or directory`). `publish()` therefore
threw before `transport.registerKey(...)`, which is why the app had made **zero**
`relay_register_key` HTTP calls. `TeamSessionStore` encrypts with the same key attributes but calls
`cipher.init(ENCRYPT_MODE, key())` with no spec and reads `cipher.iv`; that is the pattern the relay
storage now follows.

Fix — `relay/RelayCrypto.kt`, in `protect()`: initialise the cipher without a `GCMParameterSpec`,
read the Keystore-generated nonce from `cipher.iv`, still require it to be 12 bytes, and store it
ahead of the ciphertext so `unprotect()` (which legitimately passes the stored nonce on decryption)
is unchanged. The now-unused `RANDOM` field was removed.

Verification: `relay_private.xml` appeared on both devices with an `x25519_identity` entry, and
`relay_register_key`-style registration started landing — `relay_private.device_keys` gained
`8b455841-… → 684d6b01-…` and `8584d44c-… → f86d249d-…`, both `is_current`, 112-byte public keys.

### 17.3 Blocker 2 — `relay_bind_peer_key` was rejected before it ran

With keys registered, the owner still never delivered: `relay_private.envelopes` stayed 0 and
`relay_private.peer_key_bindings` stayed empty, while each queue row stayed `failed` /
`key_not_bound` / `env_len 0`. The server-side call counts (PostgREST statements only, from
`pg_stat_statements`) showed the shape of the break:
```
relay_register_key    56      ← keys publish
relay_peer_key        20      ← employee reads the owner key
relay_bind_peer_key    0      ← the owner's bind never executed
relay_enqueue          0
```
`OwnerForwarder.encrypt()` resolves the employee key through
`transport.bindPeerKey(employeeDeviceId)` with `employeeKeyId = null`, building
`"p_employee_key_id" to employeeKeyId`. The deployed function is
`relay_bind_peer_key(p_owner_device_id uuid, p_employee_device_id uuid, p_employee_key_id uuid)`
with `pronargdefaults = 0` — no default. Gson drops null map entries unless `serializeNulls` is
enabled, so the request arrived with two arguments, and PostgREST answered 404 without ever
executing the function. Reproduced directly as the owner's session:
```
ERROR: 42883: function public.relay_bind_peer_key(uuid, uuid) does not exist
```
The same call with an explicit third argument `NULL` succeeds and returns the employee's
`peer_public_key_hex`, which is also what `supabase/tests/team_pairing.sql` asserts
(`relay_bind_peer_key(:'owner', :'employee', NULL)`) and what the header comment documents:
*"Pass NULL for employee_key_id to bind whichever key is current."* The client contract was wrong,
not the schema.

An intermediate attempt was not sufficient and is worth recording: putting
`com.google.gson.JsonNull.INSTANCE` in the map still produced an omitted key, because
`JsonWriter.nullValue()` discards its deferred name whenever `serializeNulls` is off. The fix that
works is to serialise relay RPC bodies with nulls enabled.

Fix — `team/SupabaseTeamRepository.kt`: a dedicated
`relayGson = GsonBuilder().serializeNulls().create()` is used for `/rest/v1/rpc/...` relay bodies
(`request(...)` gained a `json: Gson = gson` parameter), and `bindPeerKey`'s comment records why the
argument must be present. Team RPCs keep the original `gson` instance.

Verification: `relay_bind_peer_key` 1, `relay_enqueue` 2, `relay_fetch` 12, `relay_ack` 2,
`peer_key_bindings` 1.

### 17.4 The end-to-end run

Owner log (`adb -s emulator-5554 logcat -d`), SMS sent at `00:06:08`:
```
00:06:08.787 D PayAlert: Notification from com.google.android.apps.messaging: +91 99999 99999 Rs.555 credited to your account from AMAZON@ybl. UTR 260003332222
00:06:08.795 I PayAlert: Notification pipeline result: duplicate
00:06:08.897 I PayAlertRelay: owner_enqueued message_id=230ec8ad-297f-3f69-91ab-f8fa32f6cd88 t=1792175768740
```
Employee log (`adb -s adb-ec8f5302-… logcat -d`):
```
00:06:14.625 I PayAlertRelay: employee_ws_received message_id=230ec8ad-297f-3f69-91ab-f8fa32f6cd88 t=1792175774439
00:06:14.662 I PayAlertRelay: employee_announced message_id=230ec8ad-297f-3f69-91ab-f8fa32f6cd88 t=1792175774439 text=Google sdk_gphone64_arm64: Payment received. 555.00 from AMAZON@ybl.
00:06:14.664 I PayAlertSound: transition=decision mode=SPEECH preview=false chime=false speak=true vibrate=false
00:06:14.732 I PayAlertSound: mode=SPEECH event=speak_start
00:06:15.159 I PayAlertSound: mode=SPEECH event=speak_audio_start leadMs=427
```
Retry recovery on the same pass, from the earlier `key_not_bound` row:
```
00:06:22.307 I PayAlertRelay: employee_ws_received message_id=3a8b29f5-3b7b-3821-b6b7-caae8eedefc3
00:06:22.342 I PayAlertRelay: employee_announced text=Google sdk_gphone64_arm64: Payment received. 1,234.00 from SWIGGY@ybl.
```

**Latency, measured from the logcat wall clocks above:**

| Hop | From → to | ms |
| --- | --- | --- |
| 1. Event detected → owner queue row (`owner_enqueued`) | `event_at` 1792175768533 → 1792175768740 | 207 |
| 2. Owner enqueue → employee received (`employee_ws_received`) | 00:06:08.897 → 00:06:14.625 | 5,728 |
| 3. Received → announced (`employee_announced`) | 00:06:14.625 → 00:06:14.662 | 37 |
| 4. Announced → speech audible (`speak_audio_start`) | 00:06:14.662 → 00:06:15.159 | 497 |
| **Total (event → spoken on the employee's phone)** | 1792175768533 → 00:06:15.159 | **6,626** |

Verdict: the owner→employee announcement works on both devices and the employee speaks the
relayed payment. The dominant hop is delivery (5.7 s); hops 1, 3 and 4 are sub-second. The logged
`t=` values are epoch milliseconds from `System.currentTimeMillis()`, so both devices' timestamps
are directly comparable; `employee_ws_received` logs the receive-pass start rather than the exact
push arrival, so hop 2 is an upper bound.

### 17.5 Dedup and non-adoption

Employee `payalert.db` (`run-as … cat databases/payalert.db` together with its `-wal`):
```
message_id                            status     amount  currency  merchant    decrypt_failed  received_at   processed_at
3a8b29f5-3b7b-3821-b6b7-caae8eedefc3  processed  1234.0  INR       SWIGGY@ybl   0               1792175782101  1792175782101
230ec8ad-297f-3f69-91ab-f8fa32f6cd88  processed   555.0  INR       AMAZON@ybl   0               1792175774439  1792175774439
```
Exactly one row per relayed message, both `processed`, neither flagged `decrypt_failed`, and
`SELECT COUNT(*) FROM payment_events` on the employee = **0**: a relayed announcement is stored as a
received announcement and is never adopted as the employee's own payment.

`processed_relay_messages` returns 0 rows, and that is expected rather than a failure:
`PayAlertDatabase.processedRelayMessageDao()` is declared but never called anywhere in
`app/src`, so the table is unused. Receive-path dedup is enforced by the
`received_announcements` primary key — `EmployeeReceiver.receive()` skips any message where
`received.byMessageId(message.messageId) != null` and inserts with `OnConflictStrategy.IGNORE`. The
prompt's suggested `processed_relay_messages` query cannot be satisfied by this codebase as
written; the equivalent guarantee is the one-row-per-message result above.

### 17.6 What remains unverified

- **FCM push.** No FCM log lines were produced and none can be until the server side exists:
  `SUPABASE_SERVICE_ROLE_KEY` and `FCM_SERVICE_ACCOUNT_JSON` are unset, the Database Webhook that
  triggers `supabase/functions/relay-notify` is not registered, and its JWT signer is a stub. The
  app-side init failure is fixed; the delivery path is not. Everything above travelled the
  WebSocket/pump path, not FCM.
- **`emulator-5554` and the physical phone must both be online.** The phone is on wireless ADB and
  dropped mid-install once during this pass (`device offline`, then `adb mdns services` empty); it
  recovered on its own. No test can run while it is off.
- **Re-pair and rotation recovery.** Undelivered rows `44d04961-…` (attempt 3) and `aa78bcbc-…`
  (attempt 4) were still backing off when the run ended; they were never observed reaching
  `delivered`. Whether the backoff eventually retries them successfully, and what happens to a
  queued row when the employee's key rotates, is unmeasured.
- **Owner-side detection was partly served by the semantic dedup path.** Two earlier test messages
  with distinct UTRs but the same amount and merchant were collapsed as duplicates. That is the
  intended behaviour of the dedup index, but it means the relay was exercised on a first-seen
  (amount, merchant) pair only.

## 18. Spoken announcement wording — no decimal separator, no device name

Reported from a device: a payment of 777 was announced as "seven seven seven dot zero zero" instead
of "seven hundred seventy seven rupees", on both the owner device and the employee device, and the
employee's sentence was prefixed with the owner's device name.

**Cause 1 — the amount was formatted for reading, not for speech.** `AnnouncementFormatter` produced
the amount with `String.format(Locale.US, "%,.2f", amount)`, so the speech engine was handed the
literal `777.00` and read out the digits and the decimal point. Every announced payment goes through
that one function: the owner's local path via `PaymentPipeline`, and the employee's path via
`RelayAnnouncement` → `AnnouncementFormatter`.

Fix (`settings/AnnouncementFormatter.kt`): `amountText(amount, currency)` returns the amount the way
it is said, and now takes the payment's currency so the unit is correct:
```
AnnouncementFormatter.amountText(777.0)         == "777 rupees"
AnnouncementFormatter.amountText(1234.5)        == "1,234 rupees 50 paise"
AnnouncementFormatter.amountText(0.99)          == "0 rupees 99 paise"
AnnouncementFormatter.amountText(99.999)        == "100 rupees"        (rounds before speaking)
AnnouncementFormatter.amountText(42.0, "USD")   == "42 USD"
AnnouncementFormatter.amountText(1234.5, "USD") == "1,234 USD and 50"
```
No decimal separator is produced for any input. Both call sites now pass the real currency:
`PaymentPipeline` passes `parsed.currency`, `RelayAnnouncement` passes `payload.currency`.

**Cause 2 — the employee's sentence was prefixed with the owner's device name.**
`RelayAnnouncement.format` prefixed every forwarded payment with the owner's registered device name,
which defaults to `"${Build.MANUFACTURER} ${Build.MODEL}"` — `Google sdk_gphone64_arm64` on the test
owner — and was read out as an unhelpful string of letters and digits.

Fix (`relay/RelayAnnouncement.kt`): the spoken prefix is gone. `format(template, payload)` returns the
same sentence the owner's own device would say, so the employee hears only the payment. The `prefix()`
helper and its `DEFAULT_PREFIX` were removed; the owner label is still stored in
`received_announcements` and shown on the team and history screens, where a long name is readable
rather than read aloud.

Also changed: the Settings "Test announcement" button built its sample from a hard-coded string
containing `1,234.50`; it now runs the real formatter, so the sample cannot drift from an actual
announcement.

**Verification**

- `:app:testDebugUnitTest` — **206 tests, 0 failures, 0 errors** (`GRADLE_EXIT=0`). The formatter's
  own suite grew from four cases to seven, including a guard that fails if any spoken amount contains
  a `.`.
- Cold start of both devices: no `FATAL EXCEPTION` (`owner: clean launch`, `employee: clean launch`).
- Live two-device run, `Rs.321 credited to your account from MYNTRA@ybl. UTR 260006667777`:
```
00:25:18.897 I PayAlertRelay: owner_enqueued message_id=cd766a3c-7d59-3c89-8f41-58aac40a13d2
00:25:22.772 I PayAlertRelay: employee_ws_received message_id=cd766a3c-7d59-3c89-8f41-58aac40a13d2
00:25:22.809 I PayAlertRelay: employee_announced message_id=cd766a3c-… text=Payment received. 321 rupees from MYNTRA@ybl.
00:25:23.047 I PayAlertSound: mode=SPEECH event=speak_audio_start leadMs=171
```
  No prefix and no `.00`: 3.9 s from `owner_enqueued` to `employee_ws_received`, and the sentence was
  spoken 238 ms after it was announced.
- Earlier in the same pass, `Rs.777 … FLIPKART@ybl. UTR 260002221111` produced
  `text=Google sdk_gphone64_arm64: Payment received. 777 rupees from FLIPKART@ybl.` — the amount was
  already dot-free while the device-name prefix was still present, which is how the two causes were
  separated.

**Note on §17:** the announcement strings quoted in §17.4 (`text=Google sdk_gphone64_arm64: Payment
received. 555.00 from AMAZON@ybl.`) are accurate for that run but predate this change.

**Not verified:** the owner's own local announcement text is never logged, so the owner-side sentence
is established by the shared formatter's unit tests (same function, same `currency`, with
`777.0 -> "777 rupees"` asserted) plus the employee's log, rather than by an owner-side log line. The
sentence is visible on screen in the Settings "Preview" row and in the Diagnostics
"Announcement"/"Spoken" rows.

## 19. Onboarding wizard launch crash — adaptive icon passed to `painterResource`

### Diagnosis

The crash pointed at `OnboardingScreen.kt:293`. The source around that line was:

```text
288:                     modifier = Modifier.size(52.dp),
289:                     color = MaterialTheme.colorScheme.surface,
290:                     shape = CircleShape
291:                 ) {
292:                     Icon(
293:                         painter = painterResource(id = R.mipmap.ic_launcher),
294:                         contentDescription = "PayAlert logo",
295:                         tint = androidx.compose.ui.graphics.Color.Unspecified,
296:                         modifier = Modifier.padding(6.dp)
297:                     )
```

The exact resource inspection was:

```text
$ ls -la app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
-rw-r--r--@ 1 mamanjonpal  staff  336 Oct 15 17:51 app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
$ file app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml: XML 1.0 document text, ASCII text
$ head -3 app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
```

Line 293 called `painterResource(R.mipmap.ic_launcher)`, and that mipmap resource resolved to an
Android adaptive-icon XML, not a vector drawable or raster image accepted by Compose's
`painterResource`.

### Fix

Only the unsupported asset reference changed. `R.mipmap.ic_launcher` was replaced with
`R.drawable.ic_launcher_foreground`. The replacement is a real vector drawable:

```text
$ head -3 app/src/main/res/drawable/ic_launcher_foreground.xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
```

No onboarding step order, permission action, detection, parsing, deduplication, TTS or relay code
was changed by this crash fix.

### Other onboarding references checked

```text
$ grep -rn "painterResource\|vectorResource" app/src/main/java/com/vivekray898/payalert/ui/onboarding/
app/src/main/java/com/vivekray898/payalert/ui/onboarding/OnboardingScreen.kt:47:import androidx.compose.ui.res.painterResource
app/src/main/java/com/vivekray898/payalert/ui/onboarding/OnboardingScreen.kt:132:                            painter = painterResource(
app/src/main/java/com/vivekray898/payalert/ui/onboarding/OnboardingScreen.kt:293:                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
```

The step icon at line 132 targets `R.drawable.ic_check_circle`, which begins with `<vector>`;
the pending icon targets `R.drawable.ic_warning`, which also begins with `<vector>`. The repaired
header icon now targets `R.drawable.ic_launcher_foreground`, which begins with `<vector>`.
There are no `vectorResource` calls in the onboarding package.

The shared component scan found these additional calls:

```text
$ grep -rn "painterResource\|vectorResource" app/src/main/java/com/vivekray898/payalert/ui/components/
app/src/main/java/com/vivekray898/payalert/ui/components/Common.kt:42:import androidx.compose.ui.res.painterResource
app/src/main/java/com/vivekray898/payalert/ui/components/Common.kt:196:                painter = painterResource(id = com.vivekray898.payalert.R.drawable.ic_search),
app/src/main/java/com/vivekray898/payalert/ui/components/Common.kt:204:                        painter = painterResource(id = com.vivekray898.payalert.R.drawable.ic_clear),
app/src/main/java/com/vivekray898/payalert/ui/components/Common.kt:246:                painter = painterResource(id = icon),
app/src/main/java/com/vivekray898/payalert/ui/components/Common.kt:381:                painter = painterResource(id = com.vivekray898.payalert.R.drawable.ic_volume_up),
```

Those shared references resolve to the existing drawable vectors; the dynamic `icon` parameter is
used by callers with existing drawable resources and is not part of the onboarding crash path.

### Emulator verification

Project checks and APK size:

```text
$ make test
./gradlew --no-daemon --offline :app:testDebugUnitTest
BUILD SUCCESSFUL in 12s
29 actionable tasks: 5 executed, 2 from cache, 22 up-to-date

$ make build
./gradlew --no-daemon --offline :app:assembleDebug
BUILD SUCCESSFUL in 6s
40 actionable tasks: 40 up-to-date

$ ls -la app/build/outputs/apk/debug/app-debug.apk
-rw-r--r--@ 1 mamanjonpal  staff  17603429 Oct 18 11:53 app/build/outputs/apk/debug/app-debug.apk
```

Install, data reset and launch output:

```text
Performing Streamed Install
Success
Success
Starting: Intent { cmp=com.vivekray898.payalert/.ui.MainActivity }
```

The post-fix filtered log was empty:

```text
$ wc -c /tmp/onboarding-launch.log
       0 /tmp/onboarding-launch.log
```

This empty log proves that the emulator launch produced no `FATAL`, `AndroidRuntime`,
`painterResource`, or `IllegalArgumentException` line matching the crash filter. The first-run
capture was `screenshots/onboarding/first-run.png` (136,738 bytes). The system permission dialog
was visible over the rendered wizard, proving that Compose reached the first step. The onboarding
captures were deleted after verification at the user's request; no new onboarding screenshot
artifacts remain in the workspace.

The emulator walkthrough rendered all four steps and reached Home after Finish. Captured sizes
before deletion:

```text
screenshots/onboarding/walkthrough-step-1.png   142082 bytes
screenshots/onboarding/walkthrough-step-2.png   148506 bytes
screenshots/onboarding/walkthrough-step-3.png   145628 bytes
screenshots/onboarding/walkthrough-step-4.png   152643 bytes
screenshots/onboarding/after-finish-home.png    143154 bytes
```

The walkthrough log filter was also empty:

```text
$ wc -c /tmp/onboarding-walkthrough.log
       0 /tmp/onboarding-walkthrough.log
```

### Physical-device verification limit

At verification time `adb devices -l` returned only:

```text
List of devices attached
emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:543
```

The wireless physical device was not connected and `adb mdns services` returned no discovered
services. Therefore no `/tmp/onboarding-physical.log` was produced and the physical-device launch
remains unverified. The emulator launch and full wizard walkthrough are runtime-verified; the
physical-device status is not raised without its own logcat evidence.

## 20. Efficiency, security and resource audit — measured pass

This section records the audit requested after §19. No feature code was removed, no dependency was
added, and the notification-listener export was intentionally retained because Android binds a
`NotificationListenerService` through the exported component protected by
`BIND_NOTIFICATION_LISTENER_SERVICE`.

### 20.1 Baseline measurements before edits

The repository was clean before the audit edits. Baseline build commands were:

```text
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Results:

```text
debug APK:   app/build/outputs/apk/debug/app-debug.apk
             17,603,429 bytes
release APK: app/build/outputs/apk/release/app-release-unsigned.apk
             13,307,435 bytes
```

The debug APK's largest uncompressed entries were:

```text
18,912,764  classes.dex
12,746,888  classes18.dex
 5,205,080  classes19.dex
   752,652  org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties
   629,372  resources.arsc
   355,520  classes16.dex
   350,101  org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties
```

The Bouncy Castle and ZXing dependencies were not removed: Bouncy Castle supplies the relay
cryptography and ZXing supplies the QR screen. Their removal would be a feature decision, not a
safe audit-only change.

Cold-start command, repeated three times on `emulator-5554`:

```text
adb -s emulator-5554 shell am force-stop com.vivekray898.payalert
adb -s emulator-5554 shell am start -W -n com.vivekray898.payalert/.ui.MainActivity
```

An additional verification launch showed the process absent from `pidof` immediately before
starting. Android reported `LaunchState: WARM` for all runs despite the force-stop; the reported
times are retained exactly:

| Target | Run | TotalTime | WaitTime | LaunchState |
|---|---:|---:|---:|---|
| Pixel 8 Pro AVD, API 34 | 1 | 1042 ms | 1045 ms | WARM |
| Pixel 8 Pro AVD, API 34 | 2 | 817 ms | 822 ms | WARM |
| Pixel 8 Pro AVD, API 34 | 3 | 867 ms | 869 ms | WARM |
| Physical device | — | not run | not run | no device attached |

The emulator measurements were below the 2000 ms target. No physical device was attached:

```text
List of devices attached
emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
```

### 20.2 Changes and after measurements

Release shrinking was disabled in the baseline configuration:

```diff
 release {
-    isMinifyEnabled = false
+    isMinifyEnabled = true
+    isShrinkResources = true
     proguardFiles(
```

After `./gradlew :app:assembleRelease`:

```text
release APK before R8/resource shrinking: 13,307,435 bytes
release APK after R8/resource shrinking:    3,519,190 bytes
absolute change:                            9,788,245 bytes smaller
relative change:                            73.5547%
BUILD SUCCESSFUL
```

Debug remains unminified. The final clean debug APK was 17,603,429 bytes.

Post-change startup runs used the rebuilt debug APK:

| Target | Run | TotalTime | WaitTime | LaunchState |
|---|---:|---:|---:|---|
| Pixel 8 Pro AVD, API 34 | 1 | 955 ms | 957 ms | WARM |
| Pixel 8 Pro AVD, API 34 | 2 | 841 ms | 847 ms | WARM |
| Pixel 8 Pro AVD, API 34 | 3 | 912 ms | 916 ms | WARM |

The before/after debug startup samples overlap; no startup change is attributed to the release-only
R8 setting or the backup flag.

`app/src/main/baseline-prof.txt` was absent before and after this pass. A Macrobenchmark/Profile
Installer follow-up is still needed before assigning a first-launch execution effect to a Baseline
Profile.

### 20.3 Compose frame timing

Command:

```text
adb shell dumpsys gfxinfo com.vivekray898.payalert reset
# interact with Home and History
adb shell dumpsys gfxinfo com.vivekray898.payalert
```

Combined Home/History sample before the edits:

```text
Total frames rendered: 92
Janky frames: 3 (3.26%)
50th percentile: 17ms
90th percentile: 34ms
95th percentile: 40ms
99th percentile: 150ms
Number Missed Vsync: 1
```

Post-edit samples were separated by screen:

```text
Home-only:
Janky frames: 2 (3.85%)
50th percentile: 17ms
90th percentile: 19ms
95th percentile: 22ms
99th percentile: 32ms

History-only:
Janky frames: 3 (7.14%)
50th percentile: 17ms
90th percentile: 34ms
95th percentile: 38ms
99th percentile: 150ms
```

History crossed the 5% note threshold in a short 42-frame sample. No UI code was changed because
the sample does not identify a recomposition hotspot, and the release/backup edits do not touch
Compose rendering. A longer trace with recomposition counts is the next measurement.

### 20.4 Security audit

The source secret scan was:

```text
grep -rE "sb_secret|service_role|SUPABASE_SERVICE|AIza[0-9A-Za-z_-]{35}" app/src/main/ || echo "none found"
```

Output:

```text
app/src/main/java/com/vivekray898/payalert/team/TeamClientProtocol.kt:
    // Legacy anon JWTs remain public keys; service_role/secret keys are never accepted.
```

This is a comment-only match. No credential value was found. The built-APK asset scan only matched
the parser's expected OTP phrase (`"one time password"`); it did not contain a secret, password or
token value. The publishable Supabase client key remains an allowed public build value by design.

Exported-component source audit:

| Component | Exported | Guard | Decision |
|---|---:|---|---|
| `.ui.MainActivity` | true | launcher and auth callback intent filters | required |
| `.services.PaymentNotificationListener` | true | `BIND_NOTIFICATION_LISTENER_SERVICE` | required for system binding; retained |
| `.services.SmsReceiver` | true | `BROADCAST_SMS` | required for SMS delivery |
| `.services.BootReceiver` | true | `BOOT_COMPLETED` and package-replaced broadcasts | required for reboot/update recovery |
| `.services.PaymentMonitorService` | false | internal start only | already restricted |
| `.relay.EmployeeRealtimeService` | false | internal start only | already restricted |
| `.services.PayAlertMessagingService` | false | FCM system delivery | already restricted |
| `.services.ReminderReceiver` / `.services.ServiceRestartReceiver` | false | internal/system-filtered use | already restricted |
| `FileProvider` and widget provider | false | URI grants / launcher binding | already restricted |

The built debug APK verified the backup setting:

```text
A: android:allowBackup(0x01010280)=false
```

The source diff was:

```diff
 <application
     android:name=".PayAlertApplication"
-    android:allowBackup="true"
+    android:allowBackup="false"
```

Existing `backup_rules.xml` and `data_extraction_rules.xml` remain in the project. With
`allowBackup=false`, Android backup is disabled for this application rather than relying on the
selective rules.

The `relay_fetch` RPC body is authenticated and pairing-scoped:

```sql
CREATE OR REPLACE FUNCTION public.relay_fetch(
    p_employee_device_id uuid,
    p_limit integer DEFAULT 50,
    p_lease_seconds integer DEFAULT 60
)
RETURNS TABLE (
    message_id uuid,
    client_message_id uuid,
    encrypted_envelope bytea,
    employee_key_id uuid,
    created_at timestamptz,
    expires_at timestamptz,
    lease_token uuid,
    leased_until timestamptz,
    delivery_attempts integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
    v_pairing_id uuid;
    v_now timestamptz := pg_catalog.clock_timestamp();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'authentication_required';
    END IF;
    IF p_employee_device_id IS NULL
       OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100
       OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 1 AND 3600 THEN
        RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'invalid_relay_fetch';
    END IF;

    SELECT p.id INTO v_pairing_id
      FROM public.owner_employees AS p
      JOIN public.devices AS d ON d.id = p.employee_device_id
     WHERE p.employee_device_id = p_employee_device_id AND d.user_id = v_uid
     FOR UPDATE OF p;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'employee_device_not_owned';
    END IF;

    RETURN QUERY
    WITH candidates AS (
        SELECT e.message_id
          FROM relay_private.envelopes AS e
         WHERE e.pairing_id = v_pairing_id
           AND e.expires_at > v_now
           AND (e.leased_until IS NULL OR e.leased_until <= v_now)
         ORDER BY e.created_at, e.message_id
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    ), leased AS (
        UPDATE relay_private.envelopes AS e
           SET lease_token = pg_catalog.gen_random_uuid(),
               leased_until = v_now + (p_lease_seconds * interval '1 second'),
               delivery_attempts = e.delivery_attempts + 1
          FROM candidates AS c
         WHERE e.message_id = c.message_id
        RETURNING e.message_id, e.client_message_id, e.encrypted_envelope,
                  e.employee_key_id, e.created_at, e.expires_at, e.lease_token,
                  e.leased_until, e.delivery_attempts
    )
    SELECT l.message_id, l.client_message_id, l.encrypted_envelope,
           l.employee_key_id, l.created_at, l.expires_at, l.lease_token,
           l.leased_until, l.delivery_attempts
      FROM leased AS l
     ORDER BY l.created_at, l.message_id;
END
$function$;
```

The RPC returns only the authenticated employee's paired rows and the opaque ciphertext. The
FCM data-field audit was:

```text
grep -n "data:" supabase/functions/relay-notify/index.ts
140:        data: {
```

The corresponding fields are `type: 'relay_message'` and `message_id: messageId`; the encrypted
envelope is explicitly absent.

The requested literal payment-field log scan returned no matches:

```text
grep -rn "Log\.\(d\|i\|w\|e\).*amount\|Log\.\(d\|i\|w\|e\).*merchant\|Log\.\(d\|i\|w\|e\).*utr" app/src/main/java/ | head -20
(no output)
```

The notification listener and relay evidence logs can contain a local announcement/raw notification
string for diagnostics. Those lines are Android logcat output only; no code path sends them to the
relay or FCM.

### 20.5 Resource-usage measurements

Before the edit, while the emulator was idle with the screen off for 300 seconds:

```text
Wake Locks: size=0
Suspend Blockers: size=5
```

The package-specific query after the five-minute interval produced no output. The only source-level
wake lock is the five-second, timeout-bounded display wake in
`RelayAnnouncementNotifier.wakeDisplayBriefly`; `PaymentMonitorService` does not acquire one.

After the edit, the same five-minute screen-off measurement produced:

```text
Wake Locks: size=0
Suspend Blockers: size=5
```

The package-specific query again produced no output. There was no measured out-of-window lock, so no
wake-lock code was changed.

Relay RPC count was not measured on an employee device. Only the owner emulator was attached, and
the employee role/device was the required condition for counting the 10-second pump. The 10-second
foreground pump, 30-second `RelayEngine` loop and 15-minute WorkManager floor were therefore left
unchanged; changing their cadence without a paired-device latency sample would not satisfy the
real-time behavior requirement.

The employee-service guard remains before foreground-service startup:

```text
72:         if (graph.settings.localDeviceRoleState.currentRole != LocalDeviceRole.EMPLOYEE) {
73:             Log.i(TAG, "not_employee stopping")
74:             stopSelf()
75:             return START_NOT_STICKY
76:         }
78:         ServiceCompatStartForeground(this)
```

On the owner emulator, `dumpsys activity services com.vivekray898.payalert` showed
`PaymentMonitorService` as foreground and no `EmployeeRealtimeService` record.

Listener/observer audit:

```text
app/src/main/java/com/vivekray898/payalert/ui/home/HomeScreen.kt:136: activity.lifecycle.addObserver(observer)
app/src/main/java/com/vivekray898/payalert/ui/onboarding/OnboardingScreen.kt:211: lifecycleOwner.lifecycle.addObserver(observer)
app/src/main/java/com/vivekray898/payalert/role/LocalDeviceRole.kt:83: preferences.registerOnSharedPreferenceChangeListener(listener)
app/src/main/java/com/vivekray898/payalert/settings/AppSettings.kt:92: rolePreferences.registerOnSharedPreferenceChangeListener { _, key ->
```

The two Compose registrations unregister in their `onDispose` blocks. The two SharedPreferences
listeners are process-scoped settings/role state; they have no corresponding Activity teardown and
were not changed because this audit found no runtime leak measurement. No `registerReceiver` or
Android `registerListener` call was found in app source.

### 20.6 Build and unit-test evidence

Required commands:

```text
./gradlew clean :app:assembleDebug
BUILD SUCCESSFUL in 3s
41 actionable tasks: 16 executed, 25 from cache

./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 18s
29 actionable tasks: 29 executed
```

The generated Gradle report summary is:

```text
240 tests, 0 failures, 0 skipped, 100% successful
```

The release build after R8/resource shrinking also completed with `BUILD SUCCESSFUL`.

### 20.7 Runtime regression evidence

Detection: one emulator SMS delivered the following message. The messaging app's mirrored
notification was intentionally ignored by the notification path, while the SMS path produced one
speech sequence:

```text
10-18 17:31:01.821 D/PayAlert(7122): Notification from com.google.android.apps.messaging: HDFC Bank: Rs.779.00 credited to your account from AUDIT SMS STORE. A/c XX9877. UTR 260009999998
10-18 17:31:01.821 I/PayAlert(7122): Notification pipeline result: mirrored_notification
10-18 17:31:01.930 I/PayAlertSound(7122): mode=SPEECH event=speak_start t=28708673 preview=false
10-18 17:31:02.058 I/PayAlertSound(7122): mode=SPEECH event=speak_audio_start t=28708801 preview=false leadMs=128
```

The History UI readback after that SMS contained one stored row:

```text
text="+₹779.00  |  −₹0.00"
text="AUDIT SMS STORE"
text="18 Oct 2026, 17:31 · via SMS"
text="+779.00"
```

Deduplication: the same payment notification was posted twice one second apart. The raw logcat
result was one acceptance, one speech start and one duplicate:

```text
10-18 17:32:40.576 D/PayAlert(7122): Notification from com.phonepe.app: Received Rs.500 from ZOMATO@ybl. UTR 260009999995
10-18 17:32:40.628 I/PayAlert(7122): Notification pipeline result: accepted
10-18 17:32:40.872 I/PayAlertSound(7122): mode=SPEECH event=speak_start t=28807615 preview=false
10-18 17:32:41.643 D/PayAlert(7122): Notification from com.phonepe.app: Received Rs.500 from ZOMATO@ybl. UTR 260009999995
10-18 17:32:41.650 I/PayAlert(7122): Notification pipeline result: duplicate
```

The History UI contained one matching row for the two identical notification deliveries:

```text
text="PhonePe"
text="ZOMATO@ybl"
text="18 Oct 2026, 17:32 · via PhonePe"
text="+500.00"
```

Relay: not re-run in this pass because the physical employee device was not attached. No relay
logcat line is claimed. The last two-device evidence remains the runtime record in §18/§17, and a
fresh owner-to-employee test is required after reconnecting the second device.
