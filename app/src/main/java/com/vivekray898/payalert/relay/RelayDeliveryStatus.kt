package com.vivekray898.payalert.relay

/** String values persisted in relay queue status columns. */
object RelayDeliveryStatus {
    const val PENDING = "pending"
    const val IN_FLIGHT = "in_flight"
    const val DELIVERED = "delivered"
    const val PROCESSED = "processed"
    const val FAILED = "failed"
    const val EXPIRED = "expired"
}
