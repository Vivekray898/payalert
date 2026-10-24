package com.vivekray898.payalert.team

import com.vivekray898.payalert.role.DeviceRoleSnapshot
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.role.LocalDeviceRoleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeamRoleWiringTest {

    private val code = "123456"

    @Test
    fun `joining as an employee switches this device to employee mode`() = runTest {
        val repo = FakeRepository()
        val role = RecordingRoleState()
        val pairings = mutableListOf<TeamSnapshot?>()
        val controller = TeamController(repo, backgroundScope, "Till", role, { snapshot, _ ->
            pairings += snapshot
        })
        runCurrent()

        controller.join(code)
        runCurrent()

        assertEquals(LocalDeviceRole.EMPLOYEE, role.currentRole)
        assertEquals("owner-device", role.pairedOwnerId)
        assertEquals("Shop phone", role.pairedOwnerName)
        // The relay is notified on every confirmed pairing read, including the one after joining.
        assertTrue(pairings.isNotEmpty())
        assertNotNull(pairings.last()?.owner)
        assertTrue(repo.joins.contains(code))
    }

    @Test
    fun `removing the connection returns this device to owner mode without signing out`() = runTest {
        val repo = FakeRepository()
        val role = RecordingRoleState()
        val controller = TeamController(repo, backgroundScope, "Till", role)
        runCurrent()
        controller.join(code)
        runCurrent()
        val owner = requireNotNull(controller.state.value.team?.owner)

        controller.remove(owner)
        runCurrent()

        assertEquals(LocalDeviceRole.OWNER, role.currentRole)
        assertNull(role.pairedOwnerId)
        assertNull(controller.state.value.team?.owner)
        assertNotNull(repo.account.value)
    }

    @Test
    fun `an employee device cannot create a pairing code`() = runTest {
        val repo = FakeRepository()
        repo.snapshot = repo.snapshot.copy(owner = TeamConnection("pair", "owner-device", "Shop phone"))
        val role = RecordingRoleState()
        val controller = TeamController(repo, backgroundScope, "Till", role)
        runCurrent()

        controller.createInvite()
        runCurrent()

        assertEquals(0, repo.invites)
        assertNotNull(controller.state.value.error)
    }

    @Test
    fun `an owner with employees cannot also become an employee`() = runTest {
        val repo = FakeRepository()
        repo.snapshot = repo.snapshot.copy(
            employees = listOf(TeamConnection("pair", "employee-device", "Till"))
        )
        val role = RecordingRoleState()
        val controller = TeamController(repo, backgroundScope, "Counter", role)
        runCurrent()

        controller.join(code)
        runCurrent()

        assertTrue(repo.joins.isEmpty())
        assertNotNull(controller.state.value.error)
        assertEquals(LocalDeviceRole.OWNER, role.currentRole)
    }

    @Test
    fun `an unreadable pairing leaves the stored role untouched`() = runTest {
        val repo = FakeRepository().apply { failLoad = true }
        val role = RecordingRoleState()
        val controller = TeamController(repo, backgroundScope, "Till", role)
        runCurrent()

        assertTrue(role.writes.isEmpty())
        assertEquals(LocalDeviceRole.OWNER, role.currentRole)
    }

    private class RecordingRoleState : LocalDeviceRoleState {
        val writes = mutableListOf<LocalDeviceRole>()
        private val state = MutableStateFlow(DeviceRoleSnapshot())
        override val snapshot: StateFlow<DeviceRoleSnapshot> = state.asStateFlow()
        override val currentRole: LocalDeviceRole get() = state.value.role
        override val pairedOwnerId: String? get() = state.value.ownerId
        override val pairedOwnerName: String? get() = state.value.ownerName

        override fun setRole(role: LocalDeviceRole, ownerId: String?, ownerName: String?) {
            writes += role
            state.value = DeviceRoleSnapshot(role, ownerId, ownerName)
        }

        override fun clearPairing() {
            state.value = DeviceRoleSnapshot()
        }
    }

    private class FakeRepository(override val configured: Boolean = true) : TeamRepository {
        override val account = MutableStateFlow<TeamAccount?>(TeamAccount("user", "Owner"))
        override val authError = MutableStateFlow<String?>(null)
        var snapshot = TeamSnapshot(TeamDevice("device", "Counter"))
        val joins = mutableListOf<String>()
        var invites = 0
        var failLoad = false

        override suspend fun signInUrl(): String = "https://example.test/sign-in"
        override suspend fun completeSignIn(callbackUrl: String) = Unit
        override suspend fun signOut() { account.value = null }
        override suspend fun loadTeam(deviceName: String): TeamSnapshot {
            if (failLoad) throw TeamClientException("Unable to reach the team service.")
            return snapshot.copy(device = snapshot.device.copy(name = deviceName))
        }

        override suspend fun createInvite(): TeamInvite {
            invites++
            return TeamInvite("123456", "2026-10-16T10:00:00+00:00")
        }

        override suspend fun cancelInvite() = Unit

        override suspend fun joinTeam(code: String) {
            joins += code
            snapshot = snapshot.copy(owner = TeamConnection("pair", "owner-device", "Shop phone"))
        }

        override suspend fun removeConnection(pairingId: String) {
            snapshot = snapshot.copy(
                owner = snapshot.owner?.takeUnless { it.pairingId == pairingId },
                employees = snapshot.employees.filterNot { it.pairingId == pairingId }
            )
        }
    }
}
