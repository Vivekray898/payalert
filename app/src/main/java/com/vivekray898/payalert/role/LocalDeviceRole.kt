package com.vivekray898.payalert.role

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The role of this installation for local payment detection. */
enum class LocalDeviceRole {
    OWNER,
    EMPLOYEE;

    companion object {
        /** Missing or invalid persisted state is intentionally safe for local detection. */
        fun fromPersisted(value: String?): LocalDeviceRole =
            entries.firstOrNull { it.name == value } ?: OWNER
    }
}

/**
 * Synchronous role boundary shared by Android entry points and the payment pipeline.
 *
 * Team integration calls [setRole] only after a pairing read has been confirmed, and
 * [clearPairing] after a confirmed leave/removal. Both writes commit before returning, so the next
 * receiver callback or pipeline invocation observes the new role immediately: an employee device
 * stops announcing its own SMS and notifications on the very next event rather than eventually.
 */
/** Observable role and owner identity, so a screen can show which mode is active. */
interface DeviceRoleState {
    val snapshot: StateFlow<DeviceRoleSnapshot>
}

data class DeviceRoleSnapshot(
    val role: LocalDeviceRole = LocalDeviceRole.OWNER,
    val ownerId: String? = null,
    val ownerName: String? = null
) {
    /** Stable lowercase value accepted by the shared role badge. */
    val badgeValue: String get() = role.name.lowercase(java.util.Locale.ROOT)
}

interface LocalDeviceRoleState : DeviceRoleState {
    val currentRole: LocalDeviceRole

    /** The device this installation is paired to as an employee; null while owner or unpaired. */
    val pairedOwnerId: String?

    val pairedOwnerName: String?

    val allowsLocalDetection: Boolean
        get() = currentRole == LocalDeviceRole.OWNER

    fun setRole(role: LocalDeviceRole, ownerId: String? = null, ownerName: String? = null)

    /** Removes pairing state; a device with no pairing is an owner by default. */
    fun clearPairing()
}

/**
 * SharedPreferences-backed role state. It stores no pairing credentials, key material, or payment
 * data — only the role and the owner's device id and name, which the user already sees in the team
 * screen.
 */
class PersistedLocalDeviceRoleState(context: Context) : LocalDeviceRoleState {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(readSnapshot())
    override val snapshot: StateFlow<DeviceRoleSnapshot> = _snapshot.asStateFlow()

    /**
     * Held in a field because SharedPreferences keeps only a weak reference to its listeners; an
     * inline lambda could be collected and the badge would then stop updating.
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ROLE || key == KEY_OWNER_ID || key == KEY_OWNER_NAME) {
            _snapshot.value = readSnapshot()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val currentRole: LocalDeviceRole
        get() = LocalDeviceRole.fromPersisted(preferences.getString(KEY_ROLE, null))

    override val pairedOwnerId: String?
        get() = preferences.getString(KEY_OWNER_ID, null)?.takeIf { it.isNotBlank() }

    override val pairedOwnerName: String?
        get() = preferences.getString(KEY_OWNER_NAME, null)?.takeIf { it.isNotBlank() }

    override fun setRole(role: LocalDeviceRole, ownerId: String?, ownerName: String?) {
        val editor = preferences.edit().putString(KEY_ROLE, role.name)
        if (ownerId.isNullOrBlank()) editor.remove(KEY_OWNER_ID) else editor.putString(KEY_OWNER_ID, ownerId)
        if (ownerName.isNullOrBlank()) {
            editor.remove(KEY_OWNER_NAME)
        } else {
            editor.putString(KEY_OWNER_NAME, ownerName)
        }
        check(editor.commit()) { "Could not persist local device role" }
        _snapshot.value = readSnapshot()
    }

    override fun clearPairing() {
        check(
            preferences.edit()
                .remove(KEY_ROLE)
                .remove(KEY_OWNER_ID)
                .remove(KEY_OWNER_NAME)
                .commit()
        ) { "Could not clear local device pairing role" }
        _snapshot.value = readSnapshot()
    }

    private fun readSnapshot() = DeviceRoleSnapshot(
        role = currentRole,
        ownerId = pairedOwnerId,
        ownerName = pairedOwnerName
    )

    companion object {
        const val PREFS_NAME = "payalert_device_role"
        const val KEY_ROLE = "local_device_role"
        const val KEY_OWNER_ID = "paired_owner_device_id"
        const val KEY_OWNER_NAME = "paired_owner_device_name"
    }
}
