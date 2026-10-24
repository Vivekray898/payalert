# PayAlert

[![Android 24+](https://img.shields.io/badge/Android-24%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Supabase](https://img.shields.io/badge/Optional%20relay-Supabase-3ECF8E?logo=supabase&logoColor=white)](https://supabase.com/)

PayAlert is a **local-first Android payment signal app** for counters, shops, family businesses, and
teams. It turns supported payment notifications and bank SMS messages into clear spoken alerts,
local history, cashbook entries, reminders, and optional encrypted owner-to-employee delivery.

> PayAlert is a signal layer, not a payment processor. Android exposes notifications and SMS messages,
> not a universal settlement API. Always verify important payments with the bank or payment provider.

## Features

| Area | What PayAlert provides |
|---|---|
| Detection | Supported payment notifications and bank SMS messages after the required Android permissions are granted. |
| Announcements | Text-to-Speech, chime, vibration, notification-volume routing, and configurable templates. |
| Duplicate protection | Persistent exact and semantic deduplication for repeated SMS/notification copies. |
| Local records | Room-backed payment history, search, filters, details, analytics, CSV/JSON export, and backup. |
| Cashbook | Manual money-in and money-out entries with categories, notes, reports, editing, and export. |
| Reminders | One-time and recurring upcoming-payment reminders. |
| Payment QR | Create and share a UPI payment link or QR code. |
| Team devices | Pair an owner device with employee devices using a six-digit, single-use code. |
| Encrypted relay | Forward eligible owner-side credits using device-generated keys and encrypted envelopes. |
| Offline-first core | Detection, history, cashbook, reminders, QR, export, backup, and announcements work without an account. |
| Diagnostics | Inspect permissions, service health, parser results, relay state, and recovery actions. |

## Architecture

```mermaid
flowchart LR
    A[Bank SMS] --> P[Parser + source admission]
    B[Payment notification] --> P
    P --> F{Settled payment?}
    F -- no --> X[Ignore / diagnostics]
    F -- yes --> D[Exact + semantic dedup]
    D -- duplicate --> Y[Record duplicate outcome]
    D -- new event --> R[(Room local database)]
    R --> T[TTS + chime + vibration]
    R --> H[History / cashbook / analytics]
    R -. owner only .-> E[Encrypted relay]
    E --> C[Supabase RPC + optional FCM wake-up]
    C --> W[Employee decrypts locally]
    W --> T2[TTS announcement]
```

The local path is the source of truth for each device. The remote path is optional: Supabase stores
routing metadata and opaque encrypted envelopes, while the employee device fetches and decrypts its
own copy locally. FCM carries only a message ID used to wake the employee app; it does not carry the
payment payload.

## Use the app

### Install

Download an APK from the project's [GitHub Releases](https://github.com/vivekray898/payalert/releases)
page, install it on an Android 24+ device, and open PayAlert. A release APK is not required for local
development; developers can build the debug APK using the instructions below.

### First launch

1. Complete onboarding.
2. Enable **Notification access** so PayAlert can inspect supported payment notifications.
3. Grant **SMS permission** if bank SMS detection is needed.
4. Allow PayAlert notifications.
5. Disable battery optimization for PayAlert if the device aggressively stops background services.
6. Open **Settings → Diagnostics** and run the pipeline check.
7. Verify a small test payment against the bank or payment provider before relying on the alert.

### Owner and employee devices

- **Owner:** detects and announces supported local payments, stores local history, and can forward
  eligible credits to paired employees.
- **Employee:** after pairing, intentionally stops local SMS/payment-notification detection and instead
  fetches, decrypts, stores, and announces owner-forwarded payments.
- Pairing shares device/team metadata and relay routing state. It does not merge local payment history
  or cashbook data.

### Pair two devices

#### On the owner device

1. Open **Settings → Team & devices**.
2. Sign in with Google and choose **I'm the owner**.
3. Tap **Create pairing code**.
4. Share the six-digit code privately. It is single-use and expires after 10 minutes.

#### On the employee device

1. Open **Settings → Team & devices**.
2. Sign in with Google and choose **I'm an employee**.
3. Enter all six digits and tap **Pair this device**.
4. If the code is rejected, expired, or already used, ask the owner to create a fresh code.

After pairing, the owner can refresh the device list, and either device can leave or remove the
connection. FCM and the Supabase webhook are required for the fastest remote wake-up path; the app
also has polling and WorkManager recovery paths.

## Build from source

### Requirements

| Tool | Required version or setup |
|---|---|
| Java | JDK 25; the repository pins this in `.java-version`. |
| Android SDK | Platform `android-37.0`, build-tools `36.0.0`, and platform-tools. |
| Gradle | Use the checked-in wrapper; it pins Gradle `9.6.0`. |
| Android device | Android API 24 or newer for manual use; an emulator/device is required for instrumentation tests. |
| Optional tools | `make`, Docker, Supabase CLI, and PostgreSQL for the corresponding workflows. |

The Android build uses Java 17 bytecode targets, but the Gradle/Android toolchain requires JDK 25 in
this repository. Do not add a machine-specific `org.gradle.java.home` path to tracked files.

### Clone and configure the SDK

```bash
git clone https://github.com/vivekray898/payalert.git
cd payalert

export JAVA_HOME="/path/to/jdk-25"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Install the required SDK packages with `sdkmanager` if they are not already installed:

```bash
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
yes | sdkmanager --licenses
```

Create `local.properties` with the local SDK path if Android Studio or Gradle does not create it:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

`local.properties` is ignored and must not be committed.

### Build and install

The first build downloads the locked project dependencies. The repository's `Makefile` provides the
canonical commands:

```bash
# First-time setup: resolve dependencies, run JVM tests, build debug APKs.
make setup

# Subsequent offline checks.
make test
make build

# Install and launch the debug APK on a connected device.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.vivekray898.payalert/.ui.MainActivity
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Equivalent Gradle commands are:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleDebugAndroidTest
```

### Optional Supabase configuration

Core local features do not need Supabase, Firebase, Google sign-in, or any credentials. Configure the
optional team and encrypted relay only when you need owner-to-employee delivery.

The Android app accepts these **public** build values:

- `SUPABASE_URL`: your HTTPS Supabase project URL.
- `SUPABASE_PUBLISHABLE_KEY`: an `sb_publishable_` key or legacy `anon` JWT.

Never put a `service_role`, `sb_secret_`, FCM service-account JSON, OAuth secret, or session token in
the Android build. The publishable key is embedded in the APK by design; server-only secrets belong
only in the Supabase secret store.

#### Option 1: ignored `secrets.properties`

Create `secrets.properties` in the repository root. It is ignored by Git and loaded automatically by
Gradle:

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key_here
```

#### Option 2: environment variables or Gradle properties

```bash
export SUPABASE_URL="https://your-project.supabase.co"
export SUPABASE_PUBLISHABLE_KEY="sb_publishable_your_key_here"
./gradlew --no-daemon :app:assembleDebug
```

For a one-off build, pass project properties instead:

```bash
./gradlew --no-daemon :app:assembleDebug \
  -PSUPABASE_URL="https://your-project.supabase.co" \
  -PSUPABASE_PUBLISHABLE_KEY="sb_publishable_your_key_here"
```

`NEXT_PUBLIC_SUPABASE_URL` and `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY` are accepted as compatibility
aliases. `.env.example` documents the same values, but Gradle does not automatically parse dotenv
files.

### Supabase database and authentication

The canonical forward migrations are in `supabase/migrations/`. With the Supabase CLI installed:

```bash
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push --linked
```

Enable Google sign-in in Supabase Auth and allow this redirect URL:

```text
com.vivekray898.payalert://auth/callback
```

The app opens the external browser for sign-in and handles the callback automatically. Add the
Firebase configuration file for this Android application as `app/google-services.json` before building
with FCM enabled.

### Deploy the relay Edge Function

The optional relay uses the `relay-notify` Edge Function and a Supabase Database Webhook. Set these
**server-only** secrets; do not write them to this repository or the APK:

```bash
supabase secrets set \
  PAYALERT_SUPABASE_SECRET_KEY="sb_secret_value" \
  FCM_SERVICE_ACCOUNT_JSON='{"type":"service_account", ...}' \
  FIREBASE_PROJECT_ID="your-firebase-project-id" \
  RELAY_WEBHOOK_SECRET="choose-a-random-shared-secret"

supabase functions deploy relay-notify --no-verify-jwt
```

In the Supabase Dashboard, create a Database Webhook with:

- **Schema/table:** `relay_private.envelopes`
- **Event:** `INSERT`
- **Type:** Supabase Edge Function
- **Function:** `relay-notify`
- **Header:** `x-relay-webhook-secret: <the same value as RELAY_WEBHOOK_SECRET>`

The function expects the Database Webhook `type: "INSERT"` field, not the Realtime `eventType` field.
It sends a high-priority data-only FCM wake-up containing only a message ID; the employee fetches the
encrypted envelope through the authenticated RPC. See [`supabase/README.md`](supabase/README.md) for
the full relay setup and security model.

## Checks and tests

Use the single Makefile entry points when possible:

```bash
make test          # offline JVM unit tests after make setup
make build         # offline debug APK build
make lint          # Android lint
make test-device   # connected Android instrumentation test
```

`make test-device` requires a running Android emulator or a connected device. The PostgreSQL test
suite must use a disposable empty database, never a production Supabase database:

```bash
make test-db TEST_DATABASE_URL="postgresql://user:password@localhost:5432/payalert_test"
```

The Edge Function tests are independent of the Android build:

```bash
node --experimental-strip-types --check supabase/functions/relay-notify/index.ts
node --test supabase/functions/relay-notify/tests/*.test.mjs
```

The project keeps Android dependency locks in `app/gradle.lockfile`. Do not install project
dependencies globally.

## Docker build

The repository includes a reproducible Android build image. The Dockerfile installs JDK 25, the
required Android SDK packages, and the project dependencies. Android's Linux build tools target
x86-64, so build it with the matching platform:

```bash
docker build --platform linux/amd64 -t payalert-build .
docker run --rm -p 8080:8080 payalert-build
```

The container serves the generated APK directory at [http://localhost:8080](http://localhost:8080).
That is an APK download preview, not a browser version of the Android app. To provide public build
configuration, pass only the public values as build arguments:

```bash
docker build --platform linux/amd64 \
  --build-arg SUPABASE_URL="https://your-project.supabase.co" \
  --build-arg SUPABASE_PUBLISHABLE_KEY="sb_publishable_your_key_here" \
  -t payalert-build .
```

## Repository layout

```text
app/src/main/java/com/vivekray898/payalert/
├── relay/       encrypted owner-to-employee relay and delivery tracing
├── team/        pairing, repository, controller, and transport
├── tts/         Text-to-Speech and announcement behavior
└── ui/          Compose screens, including Team & devices

app/src/test/    JVM unit tests
app/src/androidTest/  device and Compose tests
supabase/
├── migrations/      database migrations
├── functions/       Edge Functions and tests
├── tests/            disposable PostgreSQL tests
└── README.md         relay deployment details
```

## Privacy and security

- Core detection and records are local-first and do not require an account.
- Device-generated relay keys stay on the devices. The relay uses an X25519-derived shared secret and
  AES-256-GCM encrypted envelopes.
- Supabase stores routing metadata and opaque ciphertext; the employee decrypts locally.
- FCM receives a message ID only. It never receives the payment text or encrypted envelope.
- Do not commit `secrets.properties`, `.env` files, FCM service-account JSON, private keys, or tokens.
- If a credential is exposed, revoke it at the provider immediately and replace it outside Git history.

## Known limitations

Android payment detection depends on the wording and behavior of each bank, UPI app, notification
provider, and device manufacturer. In particular:

- A delayed bank SMS or mirrored notification can resemble a new payment when it lacks a stable UTR/RRN.
- Pending, processing, retry, reversal, refund, and final-settlement messages are not universal formats.
- Background execution, notification-listener reconnects, TTS engines, and battery policies vary by OEM.
- Remote delivery requires the optional Supabase and FCM configuration. Without it, local features still
  work, but team delivery is unavailable.

When an alert looks wrong, inspect **History** and **Settings → Diagnostics**, compare the UTR/RRN and
bank statement, and do not use a spoken alert alone as proof of settlement.

## Troubleshooting

### Gradle cannot find Java

Verify that JDK 25 is selected and that `JAVA_HOME` points to the JDK root, not to a `bin` directory:

```bash
java -version
./gradlew --version
```

### Gradle cannot find the Android SDK

Create or correct the ignored `local.properties` file:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Also verify that `platforms;android-37.0` and `build-tools;36.0.0` are installed.

### Team pairing is unavailable

Check that the Android build has `SUPABASE_URL` and a valid publishable/anonymous key, Google sign-in
is enabled in Supabase Auth, the callback URL is configured, and the database migrations have been
applied. Server-only relay secrets are not Android build configuration.

### FCM delivery is delayed

Check the employee device's notification permission and battery settings, the device token, the
Database Webhook header, Edge Function logs, and the `relay-notify` deployment. The employee polling
and WorkManager paths are recovery mechanisms, not a replacement for a correctly configured FCM wake-up.

## License

No license is currently declared in this repository. Treat the source as all-rights-reserved unless
the project owner adds a license file.
