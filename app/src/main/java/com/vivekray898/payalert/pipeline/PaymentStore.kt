package com.vivekray898.payalert.pipeline

import com.vivekray898.payalert.data.DedupEntry
import com.vivekray898.payalert.data.PayAlertDatabase
import com.vivekray898.payalert.data.PaymentEvent

/**
 * The eight storage operations the payment pipeline performs. Declared as an interface
 * so the dedup critical section (check-and-insert) can be unit-tested on the JVM
 * without a Room database, which needs an Android runtime.
 */
interface PaymentStore {
    data class SemanticCandidate(
        val dedupKey: String,
        val semanticKey: String?,
        val seenAt: Long,
        val source: String?
    )

    suspend fun containsExact(key: String): Boolean
    suspend fun semanticCandidates(prefix: String, cutoff: Long): List<SemanticCandidate>
    suspend fun insertDedup(exactKey: String, semanticKey: String?, seenAt: Long, source: String)
    suspend fun insertSemantic(key: String, seenAt: Long, source: String)
    suspend fun insertEvent(event: PaymentEvent)
    suspend fun pruneExactBefore(cutoff: Long)
    suspend fun pruneSemanticBefore(cutoff: Long)
}

/** Production implementation delegating to the Room DAOs. */
class RoomPaymentStore(private val database: PayAlertDatabase) : PaymentStore {

    private val dedup get() = database.dedupDao()

    override suspend fun containsExact(key: String): Boolean = dedup.contains(key)

    override suspend fun semanticCandidates(
        prefix: String,
        cutoff: Long
    ): List<PaymentStore.SemanticCandidate> = dedup.semanticCandidates(prefix, cutoff).map {
        PaymentStore.SemanticCandidate(it.dedupKey, it.semanticKey, it.seenAt, it.source)
    }

    override suspend fun insertDedup(exactKey: String, semanticKey: String?, seenAt: Long, source: String) {
        dedup.insert(DedupEntry(exactKey, seenAt, semanticKey, source))
    }

    override suspend fun insertSemantic(key: String, seenAt: Long, source: String) {
        dedup.insertSemantic(key, seenAt, source)
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
