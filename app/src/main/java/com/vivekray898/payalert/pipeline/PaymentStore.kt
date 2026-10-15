package com.vivekray898.payalert.pipeline

import com.vivekray898.payalert.data.DedupEntry
import com.vivekray898.payalert.data.PayAlertDatabase
import com.vivekray898.payalert.data.PaymentEvent

/**
 * The seven storage operations the payment pipeline performs. Declared as an interface
 * so the dedup critical section (check-and-insert) can be unit-tested on the JVM
 * without a Room database, which needs an Android runtime.
 */
interface PaymentStore {
    suspend fun containsExact(key: String): Boolean
    suspend fun containsSemantic(key: String): Boolean
    suspend fun insertDedup(exactKey: String, semanticKey: String?, seenAt: Long)
    suspend fun insertSemantic(key: String, seenAt: Long)
    suspend fun insertEvent(event: PaymentEvent)
    suspend fun pruneExactBefore(cutoff: Long)
    suspend fun pruneSemanticBefore(cutoff: Long)
}

/** Production implementation delegating to the Room DAOs. */
class RoomPaymentStore(private val database: PayAlertDatabase) : PaymentStore {

    private val dedup get() = database.dedupDao()

    override suspend fun containsExact(key: String): Boolean = dedup.contains(key)

    override suspend fun containsSemantic(key: String): Boolean = dedup.containsSemantic(key)

    override suspend fun insertDedup(exactKey: String, semanticKey: String?, seenAt: Long) {
        dedup.insert(DedupEntry(exactKey, seenAt, semanticKey))
    }

    override suspend fun insertSemantic(key: String, seenAt: Long) {
        dedup.insertSemantic(key, seenAt)
    }

    override suspend fun insertEvent(event: PaymentEvent) {
        database.paymentEventDao().insert(event)
    }

    override suspend fun pruneExactBefore(cutoff: Long) {
        dedup.pruneBefore(cutoff)
    }

    override suspend fun pruneSemanticBefore(cutoff: Long) {
        dedup.pruneSemanticBefore(cutoff)
    }
}
