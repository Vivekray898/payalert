package com.vivekray898.payalert.ui.passbook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.data.UpcomingPayment
import com.vivekray898.payalert.reminders.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Upcoming payments ("passbook"): the user's list of bills to pay and income expected,
 * each optionally repeating monthly and each backed by an alarm reminder.
 */
class PassbookViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)
    private val dao = graph.database.upcomingPaymentDao()

    val entries: StateFlow<List<UpcomingPayment>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        id: Long?,
        label: String,
        amount: Double,
        kind: String,
        category: String?,
        note: String?,
        dueAt: Long,
        repeatMonthly: Boolean
    ) {
        val cleanLabel = label.trim().ifBlank { return }
        if (amount <= 0.0) return
        viewModelScope.launch {
            val record = if (id == null) {
                UpcomingPayment(
                    label = cleanLabel,
                    amount = amount,
                    kind = kind,
                    category = category?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    dueAt = dueAt,
                    repeatMonthly = repeatMonthly,
                    createdAt = System.currentTimeMillis()
                )
            } else {
                val existing = dao.byId(id) ?: return@launch
                existing.copy(
                    label = cleanLabel,
                    amount = amount,
                    kind = kind,
                    category = category?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    dueAt = dueAt,
                    repeatMonthly = repeatMonthly
                )
            }
            val storedId = dao.insert(record)
            val stored = dao.byId(if (record.id == 0L) storedId else record.id) ?: return@launch
            ReminderScheduler.schedule(getApplication(), stored)
        }
    }

    /** Marks an entry paid; a monthly entry rolls forward to the next month instead. */
    fun markPaid(entry: UpcomingPayment) {
        viewModelScope.launch {
            ReminderScheduler.cancel(getApplication(), entry.id)
            if (entry.repeatMonthly) {
                dao.update(
                    entry.copy(
                        dueAt = ReminderScheduler.nextMonthlyDue(entry.dueAt),
                        isPaid = false
                    )
                )
                dao.byId(entry.id)?.let { ReminderScheduler.schedule(getApplication(), it) }
            } else {
                dao.markPaid(entry.id)
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            ReminderScheduler.cancel(getApplication(), id)
            dao.deleteById(id)
        }
    }

    /** Re-arms alarms for every pending entry; used after reboot and from Settings. */
    fun rescheduleAll() {
        viewModelScope.launch {
            val pending = dao.pending()
            ReminderScheduler.scheduleAll(getApplication(), pending)
        }
    }

    companion object {
        /** Default due time offered by the editor: tomorrow at 09:00. */
        fun defaultDueAt(now: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
    }
}
