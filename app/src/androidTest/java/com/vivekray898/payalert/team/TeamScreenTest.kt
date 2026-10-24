package com.vivekray898.payalert.team

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.vivekray898.payalert.ui.team.TeamContent
import com.vivekray898.payalert.ui.theme.PayAlertTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose controls with an isolated repository. Never contacts a cloud project. */
class TeamScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class FakeTeam(override val configured: Boolean = true) : TeamRepository {
        override val account = MutableStateFlow<TeamAccount?>(TeamAccount("test-user", "Test shop account"))
        override val authError = MutableStateFlow<String?>(null)
        var snapshot = TeamSnapshot(TeamDevice("test-device", "Shop phone"))
        var failLoad = false
        var rejectJoins = false
        var joins = 0
        override suspend fun signInUrl() = "https://example.test"
        override suspend fun completeSignIn(callbackUrl: String) = Unit
        override suspend fun signOut() { account.value = null }
        override suspend fun loadTeam(deviceName: String): TeamSnapshot {
            if (failLoad) throw TeamClientException("Unable to reach the team service. Check your connection and try again.")
            return snapshot.copy(device = snapshot.device.copy(name = deviceName))
        }
        override suspend fun createInvite() = TeamInvite("123456", "2099-01-01T12:00:00Z")
        override suspend fun cancelInvite() = Unit
        override suspend fun joinTeam(code: String) {
            joins++
            if (rejectJoins) throw TeamJoinRejectedException(
                "This pairing code is invalid, expired, or already used. Ask the owner for a new code."
            )
            snapshot = snapshot.copy(owner = TeamConnection("test-pair", "test-owner", "Owner tablet"))
        }
        override suspend fun removeConnection(pairingId: String) {
            snapshot = snapshot.copy(owner = null, employees = emptyList())
        }
    }

    private fun show(repository: FakeTeam) {
        lateinit var controller: TeamController
        compose.runOnUiThread { controller = TeamController(repository, scope, "Shop phone") }
        compose.setContent { PayAlertTheme { TeamContent({}, controller) } }
    }

    @After fun close() { scope.cancel() }

    @Test fun unconfiguredBuildExplainsSetupWithoutRequiringAccount() {
        show(FakeTeam(false).apply { account.value = null })
        compose.onNodeWithText("Team setup required").assertExists()
        compose.onNodeWithText("Your data stays local").assertExists()
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
    }

    @Test fun ownerCanInviteReadBackDevicesAndConfirmRemoval() {
        val repository = FakeTeam()
        show(repository)
        click("Create pairing code")
        compose.onNodeWithText("123456").assertExists()
        click("Copy pairing code")
        compose.onNodeWithText("Code copied").assertExists()
        click("Cancel pairing code")
        compose.runOnIdle {
            repository.snapshot = repository.snapshot.copy(
                employees = listOf(TeamConnection("test-pair", "test-employee", "Till phone")))
        }
        click("Refresh devices")
        click("Remove Till phone")
        compose.onNodeWithText("Remove employee device?").assertExists()
        clickDialog("Cancel")
        click("Remove Till phone")
        clickDialog("Remove device")
        compose.onNodeWithText("Device connection removed.").assertExists()
        compose.runOnIdle { assertTrue(repository.snapshot.employees.isEmpty()) }
    }

    @Test fun employeeAutoPairsAfterSixDigitsAndCanRetryRejectedCode() {
        val repository = FakeTeam()
        show(repository)
        click("I'm an employee")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("12")
        compose.runOnIdle { assertEquals(0, repository.joins) }
        compose.onNodeWithText("Pair this device").assertDoesNotExist()

        repository.rejectJoins = true
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("000123")
        compose.onNodeWithText("This pairing code is invalid, expired, or already used. Ask the owner for a new code.").assertExists()
        compose.runOnIdle { assertEquals(1, repository.joins) }

        repository.rejectJoins = false
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("000124")
        compose.onNodeWithText("Connected owner").assertExists()
        compose.runOnIdle { assertEquals(2, repository.joins) }
        click("Leave team")
        compose.onNodeWithText("Leave this team?").assertExists()
        clickDialog("Cancel")
        click("Leave team")
        clickDialog("Leave team")
        compose.onNodeWithText("Device connection removed.").assertExists()
        compose.runOnIdle { assertNull(repository.snapshot.owner) }
    }

    @Test fun loadFailureOffersWorkingRefresh() {
        val repository = FakeTeam().apply { failLoad = true }
        show(repository)
        compose.onNodeWithText("Action needed").assertExists()
        compose.runOnIdle { repository.failLoad = false }
        click("Refresh devices")
        compose.onNodeWithText("This device").assertExists()
    }

    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun clickDialog(text: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()
    }
}
