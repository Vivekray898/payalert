package com.vivekray898.payalert.settings

import com.vivekray898.payalert.parsers.TestConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourcePolicyTest {

    @Test
    fun `unset allowlist keeps all supported notification packages enabled`() {
        assertTrue(
            NotificationSourcePolicy.allowsNotificationPackage(
                "COM.PHONEPE.APP",
                null
            )
        )
    }

    @Test
    fun `configured allowlist accepts only selected package`() {
        val allowlist = setOf("com.phonepe.app")

        assertTrue(NotificationSourcePolicy.allowsNotificationPackage("com.phonepe.app", allowlist))
        assertFalse(NotificationSourcePolicy.allowsNotificationPackage("net.one97.paytm", allowlist))
        assertFalse(NotificationSourcePolicy.allowsNotificationPackage(null, allowlist))
    }

    @Test
    fun `supported payment catalog excludes sms mirrors and includes wallet packages`() {
        val packages = NotificationSourcePolicy.supportedPaymentPackages(TestConfig.shipped)

        assertTrue("com.phonepe.app" in packages)
        assertTrue("com.npci.bhim" in packages)
        assertTrue("com.fampay.in" in packages)
        assertFalse("com.google.android.apps.messaging" in packages)
        assertFalse("com.truecaller" in packages)
    }
}
