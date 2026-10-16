package com.vivekray898.payalert.relay

import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.team.TeamSnapshot

/** One paired device endpoint as the relay needs to address it. */
data class RelayRoute(
    val pairingId: String,
    val deviceId: String,
    val label: String
)

/**
 * Everything the relay needs about the current pairing, derived from the team snapshot.
 *
 * Only an owner ever forwards and only an employee ever receives. A device with no pairing has an
 * empty employee list and no owner, so both directions are inert and every local-only feature is
 * unaffected.
 */
data class RelayPairingContext(
    val role: LocalDeviceRole,
    val localDeviceId: String,
    val localLabel: String,
    val owner: RelayRoute?,
    val employees: List<RelayRoute>
) {
    val canForward: Boolean get() = role == LocalDeviceRole.OWNER && employees.isNotEmpty()
    val canReceive: Boolean get() = role == LocalDeviceRole.EMPLOYEE && owner != null

    companion object {
        fun from(snapshot: TeamSnapshot, role: LocalDeviceRole): RelayPairingContext =
            RelayPairingContext(
                role = role,
                localDeviceId = snapshot.device.id,
                localLabel = snapshot.device.name,
                owner = snapshot.owner?.let {
                    RelayRoute(it.pairingId, it.deviceId, it.deviceName)
                },
                employees = snapshot.employees.map {
                    RelayRoute(it.pairingId, it.deviceId, it.deviceName)
                }
            )
    }
}
