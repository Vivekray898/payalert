package com.vivekray898.payalert.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHealthEvaluatorTest {

    private fun snapshot(
        monitoringEnabled: Boolean = true,
        monitorServiceRunning: Boolean = true,
        notificationAccessGranted: Boolean = true,
        notificationListenerConnected: Boolean = true,
        smsGranted: Boolean = true,
        notificationsGranted: Boolean = true,
        batteryExempt: Boolean = true,
        pendingReminders: Int = 0
    ) = HealthSnapshot(
        monitoringEnabled = monitoringEnabled,
        monitorServiceRunning = monitorServiceRunning,
        notificationAccessGranted = notificationAccessGranted,
        notificationListenerConnected = notificationListenerConnected,
        smsGranted = smsGranted,
        notificationsGranted = notificationsGranted,
        batteryExempt = batteryExempt,
        pendingReminders = pendingReminders
    )

    private fun ServiceHealthReport.check(name: String) = checks.first { it.name == name }

    @Test
    fun `a healthy app reports no issues and asks for no repairs`() {
        val report = ServiceHealthEvaluator.evaluate(snapshot())
        assertTrue(report.healthy)
        assertTrue(report.issues.isEmpty())
        assertTrue(report.repairs.isEmpty())
    }

    @Test
    fun `a dead monitor is a blocking issue and is restarted`() {
        val report = ServiceHealthEvaluator.evaluate(snapshot(monitorServiceRunning = false))
        assertFalse(report.healthy)
        assertTrue(report.check("Payment monitoring").blocking)
        assertEquals(listOf(HealthRepair.START_MONITOR), report.repairs)
        assertEquals("Not running", report.check("Payment monitoring").detail)
    }

    @Test
    fun `monitoring switched off on purpose is not a fault`() {
        val report = ServiceHealthEvaluator.evaluate(
            snapshot(monitoringEnabled = false, monitorServiceRunning = false)
        )
        assertTrue(report.healthy)
        assertTrue(report.repairs.isEmpty())
        assertEquals("Off (chosen in Settings)", report.check("Payment monitoring").detail)
    }

    @Test
    fun `access granted but listener disconnected is re-bound`() {
        val report = ServiceHealthEvaluator.evaluate(
            snapshot(notificationListenerConnected = false)
        )
        assertFalse(report.healthy)
        // Access exists, so the connection can be re-requested without asking the user.
        assertEquals(listOf(HealthRepair.REBIND_LISTENER), report.repairs)
        assertEquals("Granted but not connected", report.check("Notification listener").detail)
    }

    @Test
    fun `missing notification access is a blocking issue but is not re-bound`() {
        val report = ServiceHealthEvaluator.evaluate(
            snapshot(notificationAccessGranted = false, notificationListenerConnected = false)
        )
        assertFalse(report.healthy)
        assertEquals("Not granted", report.check("Notification access").detail)
        assertTrue(report.repairs.isEmpty())
    }

    @Test
    fun `revoked sms or notification permission is blocking`() {
        val noSms = ServiceHealthEvaluator.evaluate(snapshot(smsGranted = false))
        assertFalse(noSms.healthy)
        assertEquals("SMS permission", noSms.blockingIssues.single().name)

        val noNotifications = ServiceHealthEvaluator.evaluate(snapshot(notificationsGranted = false))
        assertFalse(noNotifications.healthy)
        assertEquals("Notification permission", noNotifications.blockingIssues.single().name)
    }

    @Test
    fun `battery restriction degrades but does not block`() {
        val report = ServiceHealthEvaluator.evaluate(snapshot(batteryExempt = false))
        assertTrue(report.healthy)
        assertEquals(1, report.issues.size)
        assertFalse(report.issues.single().blocking)
    }

    @Test
    fun `pending reminders are re-armed on launch without being reported as a fault`() {
        val report = ServiceHealthEvaluator.evaluate(snapshot(pendingReminders = 3))
        assertTrue(report.healthy)
        assertEquals(listOf(HealthRepair.REARM_REMINDERS), report.repairs)
        assertEquals("3 pending, re-armed", report.check("Upcoming reminders").detail)
    }
}
