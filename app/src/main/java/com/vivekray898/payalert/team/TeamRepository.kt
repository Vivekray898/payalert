package com.vivekray898.payalert.team

import kotlinx.coroutines.flow.StateFlow

data class TeamAccount(val userId: String, val displayName: String)
data class TeamDevice(val id: String, val name: String)
data class TeamConnection(val pairingId: String, val deviceId: String, val deviceName: String)
data class TeamSnapshot(
    val device: TeamDevice,
    val employees: List<TeamConnection> = emptyList(),
    val owner: TeamConnection? = null
)
data class TeamInvite(val code: String, val expiresAt: String) {
    val expiresAtMillis: Long
        get() = runCatching {
            val normalized = expiresAt.replace(Regex("\\.\\d+(?=Z|[+-])"), "")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT)
                .apply { isLenient = false }.parse(normalized)?.time ?: 0L
        }.getOrDefault(0L)
}

/**
 * Pairing metadata only. This API never uploads or downloads payment data; the encrypted relay
 * that forwards credits is a separate interface, [com.vivekray898.payalert.relay.RelayTransport],
 * so a caller of this interface cannot move an envelope by accident.
 */
interface TeamRepository {
    val configured: Boolean
    val account: StateFlow<TeamAccount?>
    val authError: StateFlow<String?>
    suspend fun signInUrl(): String
    /** Reports a safe error through authError; never logs the callback or tokens. */
    suspend fun completeSignIn(callbackUrl: String)
    suspend fun signOut()
    suspend fun loadTeam(deviceName: String): TeamSnapshot
    suspend fun createInvite(): TeamInvite
    suspend fun cancelInvite()
    suspend fun joinTeam(code: String)
    suspend fun removeConnection(pairingId: String)
}

object PairingCode {
    fun normalize(value: String): String = value.trim().uppercase(java.util.Locale.ROOT)
    fun isValid(value: String): Boolean = normalize(value).matches(Regex("[A-F0-9]{12}"))
}
