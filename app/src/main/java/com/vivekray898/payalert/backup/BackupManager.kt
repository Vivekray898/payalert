package com.vivekray898.payalert.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.vivekray898.payalert.data.PayAlertDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportResult(
    val events: Int,
    val cashbook: Int,
    val upcoming: Int,
    /** Records the file contained that were already present and therefore skipped. */
    val skipped: Int = 0
) {
    val total: Int get() = events + cashbook + upcoming
}

/**
 * Writes and reads a backup of the local ledger through a user-chosen document URI.
 * Works entirely offline; no account or network is involved.
 */
class BackupManager(private val context: Context) {

    private val database: PayAlertDatabase get() = PayAlertDatabase.get(context)

    suspend fun export(target: Uri): Int = withContext(Dispatchers.IO) {
        val payload = BackupPayload(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion(),
            events = database.paymentEventDao().all(),
            cashbook = database.cashbookDao().all(),
            upcoming = database.upcomingPaymentDao().all()
        )
        val json = BackupCodec.encode(payload)
        context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw BackupFormatException("Could not open the chosen file for writing")
        payload.events.size + payload.cashbook.size + payload.upcoming.size
    }

    suspend fun import(source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(source)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw BackupFormatException("Could not open the chosen file for reading")

        val payload = BackupCodec.decode(json)

        // One transaction, so a restore can never be left half-applied. Every insert ignores
        // rows that already exist (Room reports those as -1), and the result therefore counts
        // what actually landed in the database rather than the size of the file.
        database.withTransaction {
            var insertedEvents = 0
            payload.events.forEach {
                if (database.paymentEventDao().insert(it) != -1L) insertedEvents++
            }

            var insertedCashbook = 0
            payload.cashbook.forEach {
                if (database.cashbookDao().insertIgnore(it) != -1L) insertedCashbook++
            }

            var insertedUpcoming = 0
            payload.upcoming.forEach {
                if (database.upcomingPaymentDao().insertIgnore(it) != -1L) insertedUpcoming++
            }

            val inserted = insertedEvents + insertedCashbook + insertedUpcoming
            val inFile = payload.events.size + payload.cashbook.size + payload.upcoming.size

            ImportResult(
                events = insertedEvents,
                cashbook = insertedCashbook,
                upcoming = insertedUpcoming,
                skipped = inFile - inserted
            )
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (error: Exception) {
        "unknown"
    }
}
