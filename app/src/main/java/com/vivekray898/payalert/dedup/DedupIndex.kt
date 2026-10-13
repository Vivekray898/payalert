package com.vivekray898.payalert.dedup

/**
 * DedupIndex tracks which dedupKeys have already been seen, to suppress
 * duplicate payment events across the SMS and notification listener paths.
 *
 * Key format: "sms:<sms-id>" for SMS events, or
 *           "notif:<pkg>:<post-timestamp-ms>:<normalized-title-hash>" for notifications.
 *
 * Implementations:
 *   - DedupMemoryImpl: in-memory only (reset on process death).
 *   - DedupRoomImpl: Room-backed persistent across restarts (uses the shared
 *     PaymentDatabase; schema includes a dedup_index table).
 */
interface DedupIndex {

    /** Return true if this dedupKey has already been processed. */
    fun isSeen(key: String): Boolean

    /** Mark this dedupKey as seen. idempotent. */
    fun markSeen(key: String)

    /** Clear all entries (e.g. on app reset). */
    fun clear(): Unit
}