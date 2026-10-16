package com.vivekray898.payalert.team

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeamControllerTest {
    private class FakeRepository(override val configured: Boolean = true) : TeamRepository {
        override val account = MutableStateFlow<TeamAccount?>(TeamAccount("owner-user", "Owner"))
        override val authError = MutableStateFlow<String?>(null)
        var snapshot = TeamSnapshot(TeamDevice("device", "Counter"))
        var loads = mutableListOf<String>()
        var joins = mutableListOf<String>()
        var removed = mutableListOf<String>()
        var invites = 0
        var cancelCount = 0
        var signIns = 0
        var failLoad = false
        var failMutation = false
        var loadGate: CompletableDeferred<Unit>? = null
        override suspend fun signInUrl(): String { signIns++; return "https://example.test/sign-in" }
        override suspend fun completeSignIn(callbackUrl: String) = Unit
        override suspend fun signOut() { account.value = null }
        override suspend fun loadTeam(deviceName: String): TeamSnapshot {
            loads.add(deviceName)
            loadGate?.await()
            if (failLoad) throw TeamClientException("Unable to reach the team service.")
            return snapshot.copy(device = snapshot.device.copy(name = deviceName))
        }
        override suspend fun createInvite(): TeamInvite {
            invites++
            return TeamInvite("A1B2C3D4E5F6", "2026-10-16T10:00:00+00:00")
        }
        override suspend fun cancelInvite() { cancelCount++ }
        override suspend fun joinTeam(code: String) {
            joins.add(code)
            if (failMutation) throw TeamClientException("Invalid or expired pairing code.")
            snapshot = snapshot.copy(owner = TeamConnection("pair", "owner-device", "Shop phone"))
        }
        override suspend fun removeConnection(pairingId: String) {
            removed.add(pairingId)
            snapshot = snapshot.copy(owner = null, employees = snapshot.employees.filterNot { it.pairingId == pairingId })
        }
    }

    @Test fun `signed in device registers and reads saved connections`() = runTest {
        val repo = FakeRepository()
        val connection = TeamConnection("pair", "employee", "Till phone")
        repo.snapshot = repo.snapshot.copy(employees = listOf(connection))
        val controller = TeamController(repo, backgroundScope, "Shop phone")
        runCurrent()
        assertEquals(listOf("Shop phone"), repo.loads)
        assertEquals(listOf(connection), controller.state.value.team?.employees)
        assertFalse(controller.state.value.busy)
    }

    @Test fun `unconfigured build performs no network action`() = runTest {
        val repo = FakeRepository(configured = false)
        repo.account.value = null
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.signIn { fail("Browser must not open") }
        runCurrent()
        assertEquals(0, repo.signIns)
        assertTrue(repo.loads.isEmpty())
    }

    @Test fun `sign in opens browser and then loads on account arrival`() = runTest {
        val repo = FakeRepository().apply { account.value = null }
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        var url: String? = null
        controller.signIn { url = it }
        runCurrent()
        assertEquals("https://example.test/sign-in", url)
        repo.account.value = TeamAccount("employee-user", "Employee")
        runCurrent()
        assertNotNull(controller.state.value.team)
    }

    @Test fun `invalid input stays local and valid join normalizes before readback`() = runTest {
        val repo = FakeRepository()
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.join("123")
        assertTrue(repo.joins.isEmpty())
        assertNotNull(controller.state.value.error)
        controller.join("  a1b2c3d4e5f6  ")
        runCurrent()
        assertEquals(listOf("A1B2C3D4E5F6"), repo.joins)
        assertEquals("Shop phone", controller.state.value.team?.owner?.deviceName)
        assertEquals(2, repo.loads.size)
        assertFalse(controller.state.value.needsRefresh)
    }

    @Test fun `failed mutation blocks retry until refreshed without claiming success`() = runTest {
        val repo = FakeRepository().apply { failMutation = true }
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.join("A1B2C3D4E5F6")
        runCurrent()
        assertTrue(controller.state.value.needsRefresh)
        assertNull(controller.state.value.message)
        assertEquals("Invalid or expired pairing code.", controller.state.value.error)
        controller.join("A1B2C3D4E5F6")
        assertEquals(1, repo.joins.size)
        controller.refresh()
        runCurrent()
        assertFalse(controller.state.value.needsRefresh)
    }

    @Test fun `successful mutation with failed readback stays unconfirmed`() = runTest {
        val repo = FakeRepository()
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        repo.failLoad = true
        controller.join("A1B2C3D4E5F6")
        runCurrent()
        assertTrue(controller.state.value.needsRefresh)
        assertNull(controller.state.value.team?.owner)
        assertNull(controller.state.value.message)
        repo.failLoad = false
        controller.refresh()
        runCurrent()
        assertEquals("Shop phone", controller.state.value.team?.owner?.deviceName)
    }

    @Test fun `owner removes employee and reads back new list`() = runTest {
        val repo = FakeRepository()
        val employee = TeamConnection("pair", "employee", "Till phone")
        repo.snapshot = repo.snapshot.copy(employees = listOf(employee))
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.remove(employee)
        runCurrent()
        assertEquals(listOf("pair"), repo.removed)
        assertTrue(controller.state.value.team!!.employees.isEmpty())
        assertEquals("Device connection removed.", controller.state.value.message)
    }

    @Test fun `employee leaves owner and reads back standalone device`() = runTest {
        val repo = FakeRepository()
        val owner = TeamConnection("pair", "owner", "Owner phone")
        repo.snapshot = repo.snapshot.copy(owner = owner)
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.remove(owner)
        runCurrent()
        assertNull(controller.state.value.team?.owner)
        assertFalse(controller.state.value.needsRefresh)
    }

    @Test fun `refresh clears consumed code when new membership appears`() = runTest {
        val repo = FakeRepository()
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.createInvite()
        runCurrent()
        assertNotNull(controller.state.value.invite)
        repo.snapshot = repo.snapshot.copy(employees = listOf(TeamConnection("pair", "employee", "Till")))
        controller.refresh()
        runCurrent()
        assertNull(controller.state.value.invite)
        assertEquals(1, controller.state.value.team?.employees?.size)
    }

    @Test fun `cancel invalidates displayed invite after server success`() = runTest {
        val repo = FakeRepository()
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.createInvite()
        runCurrent()
        controller.cancelInvite()
        runCurrent()
        assertEquals(1, repo.cancelCount)
        assertNull(controller.state.value.invite)
    }

    @Test fun `rename validates and saves preference only after server readback`() = runTest {
        val repo = FakeRepository()
        var saved: String? = null
        val controller = TeamController(repo, backgroundScope, "Phone") { saved = it }
        runCurrent()
        controller.renameDevice("   ")
        assertNull(saved)
        assertEquals(1, repo.loads.size)
        controller.renameDevice("  Till 2  ")
        runCurrent()
        assertEquals("Till 2", saved)
        assertEquals("Till 2", controller.state.value.team?.device?.name)
        repo.failLoad = true
        controller.renameDevice("Unsaved name")
        runCurrent()
        assertEquals("Till 2", saved)
    }

    @Test fun `busy state prevents duplicate requests`() = runTest {
        val repo = FakeRepository().apply { loadGate = CompletableDeferred() }
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        assertTrue(controller.state.value.busy)
        controller.refresh()
        controller.createInvite()
        runCurrent()
        assertEquals(1, repo.loads.size)
        assertEquals(0, repo.invites)
        repo.loadGate!!.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.busy)
    }

    @Test fun `sign out erases displayed team and code without removing connection`() = runTest {
        val repo = FakeRepository()
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.createInvite()
        runCurrent()
        controller.signOut()
        runCurrent()
        assertNull(controller.state.value.account)
        assertNull(controller.state.value.team)
        assertNull(controller.state.value.invite)
        assertTrue(repo.removed.isEmpty())
    }

    @Test fun `account switch clears other accounts data during new load`() = runTest {
        val repo = FakeRepository()
        val controller = TeamController(repo, backgroundScope, "Phone")
        runCurrent()
        controller.createInvite()
        runCurrent()
        repo.loadGate = CompletableDeferred()
        repo.account.value = TeamAccount("another-user", "Another person")
        runCurrent()
        assertNull(controller.state.value.invite)
        assertNull(controller.state.value.team)
        assertEquals("another-user", controller.state.value.account?.userId)
    }

    @Test fun `invite expiry supports Supabase fractional offset timestamps`() {
        assertEquals(1792144800123L - 123L,
            TeamInvite("A1B2C3D4E5F6", "2026-10-16T10:00:00.123456+00:00").expiresAtMillis)
        assertEquals(0L, TeamInvite("A1B2C3D4E5F6", "invalid").expiresAtMillis)
        assertFalse(PairingCode.isValid("ABCDEFGHIJKL"))
    }
}
