# PayAlert

Local-first Android payment announcements, history, cashbook and reminders, built with Kotlin,
Jetpack Compose and Room. **Settings → Team & devices** optionally connects owner and employee
phones using Supabase and Google sign-in. No account is required for local features.

## Build and run

Requirements: a **full JDK 25** (including `jlink`), Android SDK command-line tools, Make, and an
Android 24+ device or emulator. The existing daemon major is pinned in
`gradle/gradle-daemon-jvm.properties` and `.java-version`; Gradle 9.6.0 is provided by the wrapper.
Dependency selections are recorded in `app/gradle.lockfile`. No global dependency installs are needed.

From this folder, set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK, then:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
sdkmanager --licenses
make setup                         # first run: downloads dependencies, tests, builds app + UI tests
make test                          # offline JVM tests; no cloud or emulator needed
make build                         # offline debug build after setup
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.vivekray898.payalert/.ui.MainActivity
```

Use Android Studio's SDK Manager if `sdkmanager` is not on your PATH. Do not commit
`local.properties`, machine-specific JDK paths, or secrets. Gradle and SDK caches must be populated
before offline commands can work; clean-machine setup needs network access.

## Enable optional team pairing

1. Create a Supabase project. In its SQL editor, apply **`supabase/schema.sql` first**, then
   **`supabase/team_pairing.sql`**. Both are non-destructive and may be reapplied. If you ever
   reapply the base schema, reapply the team migration afterward to restore restricted device
   privileges. Pairing writes go through five authorization-checked RPCs, not direct table writes.
2. Enable the **Google** provider in Supabase Authentication. Configure its web OAuth client ID
   and secret in the Supabase dashboard only. In Google Cloud, allow your project's
   `https://<project-ref>.supabase.co/auth/v1/callback` redirect URI.
3. In Supabase Authentication → URL Configuration → Redirect URLs, allow exactly
   **`com.vivekray898.payalert://auth/callback`**. Do not enable implicit-flow token callbacks.
   Android opens the system browser and uses PKCE to exchange the returned one-time code.
4. Set the two **public** client values before building. This workspace can keep them in the
   ignored root `secrets.properties` file, so normal builds do not need exports. The file is already
   configured locally in this checkout; do not commit it. For another checkout, copy the following
   template and replace the values:

   ```sh
    cat > secrets.properties <<'EOF'
    SUPABASE_URL=https://your-project.supabase.co
    SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_public_key
    EOF
    make build
    adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Gradle `-P` properties take precedence, followed by environment variables, then
   `secrets.properties`. `.env.example` documents the variables; Gradle does **not** load dotenv
   files. Never embed
   `service_role`, `sb_secret_`, Google OAuth secrets, or personal access tokens. Builds with no
   public configuration show a setup explanation and make no team requests.

### Owner and employee flow

- On both phones, open **Settings → Manage team & devices** and continue with Google. Different
  accounts are supported. Name devices so people can recognize them.
- The owner chooses **I'm the owner → Create pairing code** and shares it privately.
- The employee chooses **I'm an employee**, pastes the full 12-character code, and taps
  **Pair this device**. Codes use A–F and 0–9, expire after 10 minutes, and work once.
- On the owner's phone, tap **Refresh devices** to read back the employee phone. An owner device
  can have several employee devices. An employee can have only one owner and cannot also invite
  employees. Cancel an outstanding owner invite before joining another owner.
- Owners can **Remove** an employee; employees can **Leave team**. Both ask for confirmation.
  Signing out clears sign-in only, **not** cloud connections. Disconnect before signing out.

Pairing shares profile/device metadata and membership only. **Payment uploads, cross-device payment
history, payment forwarding, remote announcements, realtime updates, and push delivery are not part
of this feature.** Existing payment/cashbook cloud tables remain unused. Device lists refresh on
opening the screen or tapping Refresh, not continuously. Pairing never changes local payment data.

Codes are stored as server-side hashes, consumed transactionally, and limited to 10 join attempts
per account per 10-minute window. Tokens and pending PKCE verifiers use Keystore AES/GCM storage
excluded from backup and device transfer. Installation IDs are scoped to account/project and
survive sign-out. Uninstalling clears them: disconnect old devices before uninstalling.

## Checks

```sh
make test                          # canonical offline JVM suite
make test-device                   # Android emulator/device connected via adb
make lint                          # current legacy lint failures are listed below
```

The instrumented Compose tests use isolated in-memory repositories, not real Google accounts or
cloud data. They exercise code creation/copy/cancel, device-list refresh, removal confirmation,
employee validation/join/leave, setup and failed-load recovery. JVM tests cover controller
transitions, PKCE, exact RPC requests, refresh rotation, safe errors, and redirect refusal.

For database authorization and pairing behavior, create a **disposable empty PostgreSQL 13+ database**
with pgcrypto available and use a privileged test role:

```sh
export TEST_DATABASE_URL='postgresql://localhost/payalert_pairing_test'
make test-db
```

This applies an Auth shim and both migrations twice, then verifies two-account pairing/readback,
expiry, code reuse, rate limits, role restrictions, direct-access denial, owner removal and employee
leave. Fixtures roll back; schema setup remains. **Never run the shim against a real Supabase
project**: it replaces `auth.uid()`. For an already migrated disposable Supabase database, use the
non-shim command documented at the top of `supabase/tests/team_pairing.sql`.

Reports: `app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/reports/androidTests/connected/debug/index.html`.
To intentionally refresh dependency locks after a dependency change, run `make locks` and review
`app/gradle.lockfile` before committing.

### Current verification limits

Build/JVM tests, emulator Compose tests and local PostgreSQL tests are verified. A complete live
Google OAuth → Supabase/PostgREST → two physical phones flow still needs project configuration and
testing. The Docker recipe has not been executed in this workspace (Docker is unavailable).

`make lint` currently reports three pre-existing errors: a non-observable default locale in
`ui/analytics/AnalyticsScreen.kt` and two missing optional-telephony hardware declarations for SMS
permissions. These are outside the pairing feature; no lint errors are suppressed by this change.

## Rebuild on clean Linux / Docker

The Dockerfile installs a JDK, Android SDK/tools, build dependencies and project dependencies.
Google's Linux Android build tools require amd64; on ARM hosts use emulation explicitly:

```sh
docker build --platform linux/amd64 -t payalert .
docker run --rm --network none payalert make test
docker run --rm -p 8080:8080 payalert
```

The last command serves the built debug APK at
`http://localhost:8080/app-debug.apk` for downloading/installing on Android; it is **not a browser
version of the app**. The image build needs network access; the baked-in JVM test command is offline.
For a configured APK, pass the public settings with `--build-arg SUPABASE_URL=...` and
`--build-arg SUPABASE_PUBLISHABLE_KEY=...`. Private files are excluded from the Docker build context.

## Deploy

Deploy the optional database scripts through the Supabase SQL editor in the order above. Never
send a migration to production merely to run a test. Debug-device installation uses `adb install -r`
as above. For production Android distribution:

```sh
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign this artifact with your own release keystore using Android Studio or `apksigner`, then
distribute the signed APK through your chosen channel. Release signing credentials are deliberately
not stored/configured in the repository. The keystore, password and private OAuth credentials must
never enter git, Docker build args, examples or verification reports.
