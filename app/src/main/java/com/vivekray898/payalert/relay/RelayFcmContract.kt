package com.vivekray898.payalert.relay

/**
 * Stable keys shared by the FCM service and the server-side relay notifier.
 *
 * FCM is only a wake-up signal. The encrypted relay envelope is never placed in
 * this payload; the employee fetches it through the authenticated relay RPC.
 */
object RelayFcmContract {
    const val TYPE_KEY = "type"
    const val MESSAGE_ID_KEY = "message_id"
    const val RELAY_MESSAGE_TYPE = "relay_message"
    const val PAIRING_CHANGED_TYPE = "pairing_changed"

    fun relayMessageId(data: Map<String, String>): String? =
        data.takeIf { it[TYPE_KEY] == RELAY_MESSAGE_TYPE }
            ?.get(MESSAGE_ID_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
