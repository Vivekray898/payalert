package com.vivekray898.payalert.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.vivekray898.payalert.data.CashbookEntry
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.data.UpcomingPayment

/** Everything a backup file contains. */
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val events: List<PaymentEvent> = emptyList(),
    val cashbook: List<CashbookEntry> = emptyList(),
    val upcoming: List<UpcomingPayment> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Serialises a [BackupPayload] to JSON and back.
 *
 * Kept separate from any database or file access so a round-trip can be unit-tested, and so a
 * future format version can be rejected instead of corrupting the local ledger.
 */
object BackupCodec {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(payload: BackupPayload): String = gson.toJson(payload)

    /** Throws [BackupFormatException] when the text is not a readable PayAlert backup. */
    fun decode(json: String): BackupPayload {
        val payload = try {
            gson.fromJson(json, BackupPayload::class.java)
        } catch (error: Exception) {
            throw BackupFormatException("Not a valid backup file", error)
        } ?: throw BackupFormatException("Backup file is empty")

        if (payload.version > BackupPayload.CURRENT_VERSION) {
            throw BackupFormatException(
                "Backup was written by a newer version (${payload.version}); " +
                    "this build understands up to ${BackupPayload.CURRENT_VERSION}"
            )
        }
        return payload
    }
}

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
