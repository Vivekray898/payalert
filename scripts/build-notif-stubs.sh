#!/usr/bin/env bash
#
# Builds three minimal APKs whose package identities are the three major UPI apps,
# used by the notification-path dedup verification (BUILD_AND_VERIFICATION.md section 10).
#
# `adb shell cmd notification post` cannot spoof a posting package (its usage is
# `post [flags] TAG TEXT`), so the only way to exercise PayAlert's listener with three
# genuine package identities is three tiny apps that each post one real notification.
#
# Build tools only: aapt2 + javac + d8 + apksigner from the installed SDK.
# No Gradle, no Maven artifacts, no dependencies beyond android.jar.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK_DIR/build-tools/36.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-37.0/android.jar"
DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
OUT="$PROJECT_ROOT/app/build/notif-stubs"

[[ -f "$ANDROID_JAR" ]] || { echo "missing $ANDROID_JAR" >&2; exit 1; }
[[ -f "$DEBUG_KEYSTORE" ]] || { echo "missing $DEBUG_KEYSTORE" >&2; exit 1; }

# pkg | channel-name | notification title | notification body
# UTR can be overridden for the regression run: UTR=444444444444 ./build-notif-stubs.sh
STUBS=(
  "com.phonepe.app|PhonePe|PhonePe|Received Rs.500 from ZOMATO@ybl. UTR ${UTR:-111111111111}"
  "net.one97.paytm|Paytm|Paytm|Rs.500 credited to your account from zomato@ybl. Ref 222222222222"
  "com.google.android.apps.nbu.paisa.user|Google Pay|Google Pay|You received ₹500 from Zomato. UPI Ref 333333333333"
)

mkdir -p "$OUT"
cd "$OUT"

for entry in "${STUBS[@]}"; do
  IFS='|' read -r PKG CHANNEL TITLE BODY <<<"$entry"
  # Filesystem-safe work dir per stub (the package contains dots).
  SLUG="${PKG//./_}"
  WORK="$OUT/$SLUG"
  rm -rf "$WORK"
  mkdir -p "$WORK/res" "$WORK/classes"

  # ------------------------------------------------------------------ manifest
  cat > "$WORK/AndroidManifest.xml" <<MANIFEST
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="stub.notifier">
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application android:label="$TITLE">
        <activity
            android:name="stub.notifier.MainActivity"
            android:exported="true"
            android:label="$TITLE">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
MANIFEST

  # ------------------------------------------------------------------ activity
  cat > "$WORK/MainActivity.java" <<JAVA
package stub.notifier;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;

/**
 * Posts exactly one big-text notification (the text PayAlert's listener reads) and
 * finishes. Text is baked in at build time so nothing about the post depends on
 * runtime state.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "${CHANNEL}";
        NotificationChannel channel =
                new NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(channel);
        Notification n = new Notification.Builder(this, channelId)
                .setContentTitle("${TITLE}")
                .setContentText("${BODY}")
                .setStyle(new Notification.BigTextStyle().bigText("${BODY}"))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .build();
        nm.notify(1, n);
        finish();
    }
}
JAVA

  # ------------------------------------------------------------------ build
  "$BT/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$WORK/AndroidManifest.xml" \
    --rename-manifest-package "$PKG" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    -o "$WORK/base.apk" >/dev/null

  javac -source 17 -target 17 -classpath "$ANDROID_JAR" -d "$WORK/classes" "$WORK/MainActivity.java" 2>/dev/null

  "$BT/d8" --lib "$ANDROID_JAR" --output "$WORK" $(find "$WORK/classes" -name '*.class')

  cp "$WORK/base.apk" "$WORK/unsigned.apk"
  (cd "$WORK" && zip -q -j unsigned.apk classes.dex)

  "$BT/apksigner" sign \
    --ks "$DEBUG_KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT/stub-$SLUG.apk" \
    "$WORK/unsigned.apk"

  echo "built stub-$SLUG.apk for $PKG"
done

echo "---"
ls -la "$OUT"/stub-*.apk
