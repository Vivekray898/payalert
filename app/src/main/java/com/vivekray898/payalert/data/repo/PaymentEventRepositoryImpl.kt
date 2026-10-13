package com.vivekray898.payalert.data.repo

import com.vivekray898.payalert.data.dao.PaymentEventDao
import com.vivekray898.payalert.data.model.PaymentEvent
import com.vivekray898.payalert.di.internal.module.PaymentEventRepository
import java.util.List

class PaymentEventRepositoryImpl(
    private val dao: PaymentEventDao,
    private val supabase: SupabaseSyncClient,
    private val dedup: DedupIndex
) : PaymentEventRepository {

    override fun insert(event: PaymentEvent) {
        // dao is suspend; wrap in coroutine if caller isn't
        // In practice the ViewModel calls via viewModelScope.launch {}
        // Here we just delegate; the real dao is suspend.
        // For now, a no-op placeholder so the module compiles.
    }

    override fun getUnsynced(): List<PaymentEvent> = dao.getUnsynced()

    override fun markSynced(eventId: String) = dao.markSynced(eventId)

    override fun syncPending() {
        val pending = getUnsynced()
        pending.forEach { supabase.upsertPaymentEvent(it) }
    }
}