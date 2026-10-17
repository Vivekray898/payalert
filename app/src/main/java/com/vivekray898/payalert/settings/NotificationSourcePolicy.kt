package com.vivekray898.payalert.settings

import com.vivekray898.payalert.parsers.ParserConfig
import java.util.Locale

/** Pure source-selection rules shared by Settings and the notification listener. */
object NotificationSourcePolicy {
    /** Packages that mirror an incoming SMS and must not become a second detector input. */
    val smsMirrorPackages: Set<String> = setOf(
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.microsoft.android.smsorganizer",
        "com.truecaller"
    )

    /** All supported payment-app packages, including wallet rules not repeated in the package list. */
    fun supportedPaymentPackages(config: ParserConfig): List<String> =
        (config.notificationPackages + config.wallets.flatMap { it.packages })
            .map(::normalize)
            .filter { it.isNotBlank() && it !in smsMirrorPackages }
            .distinct()
            .sorted()

    /** Null means the user has not restricted notifications and all supported apps are allowed. */
    fun allowsNotificationPackage(
        packageName: String?,
        allowlist: Set<String>?
    ): Boolean {
        val normalized = normalize(packageName.orEmpty())
        return normalized.isNotBlank() && (allowlist == null || normalized in allowlist)
    }

    fun normalize(packageName: String): String = packageName.trim().lowercase(Locale.US)
}
