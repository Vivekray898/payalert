package com.vivekray898.payalert.pipeline

import com.vivekray898.payalert.data.DedupEntry
import com.vivekray898.payalert.data.PayAlertDatabase
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.parsers.DedupKeyBuilder
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.settings.AnnouncementFormatter
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.tts.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single pipeline every detector funnels into:
 *
 *   text -> parse -> filter -> dedup -> persist to Room -> announce via TTS
 *
 * Both the SMS receiver and the notification listener call [handle], which is why a
 * payment seen twice collapses to one event and one announcement.
 */
class PaymentPipeline(
    private val database: PayAlertDatabase,
    private val parser: ParserRegistry,
    private val settings: AppSettings,
    private val tts: TtsEngine,
    /** Notified after a payment is stored, so home-screen widgets can refresh. */
    private val onStored: () -> Unit = {}
) {
    private val _lastOutcome = MutableStateFlow<ParseOutcome?>(null)
    val lastOutcome: StateFlow<ParseOutcome?> = _lastOutcome.asStateFlow()

    suspend fun handle(
        text: String,
        source: String,
        sourceDetail: String?,
        now: Long = System.currentTimeMillis()
    ): ParseOutcome {
        val snapshot = settings.state.value

        if (!snapshot.monitoringEnabled) {
            return record(outcome(ParseOutcome.NOT_MONITORED, source, sourceDetail, now))
        }

        val sourceEnabled = when (source) {
            "sms" -> snapshot.detectSms
            "notification" -> snapshot.detectNotifications
            else -> true
        }
        if (!sourceEnabled) {
            return record(outcome(ParseOutcome.SOURCE_DISABLED, source, sourceDetail, now))
        }

        val parsed = parser.parse(text, sourceDetail)
            ?: return record(outcome(ParseOutcome.NO_MATCH, source, sourceDetail, now))

        if (parsed.amount < snapshot.minimumAmount) {
            return record(
                outcome(ParseOutcome.BELOW_MINIMUM, source, sourceDetail, now).copy(
                    amount = parsed.amount, kind = parsed.kind, merchant = parsed.merchant
                )
            )
        }

        val kindAllowed =
            (parsed.kind == "credit" && snapshot.announceCredits) ||
                (parsed.kind == "debit" && snapshot.announceDebits)
        if (!kindAllowed) {
            return record(
                outcome(ParseOutcome.KIND_DISABLED, source, sourceDetail, now).copy(
                    amount = parsed.amount, kind = parsed.kind, merchant = parsed.merchant
                )
            )
        }

        val dedupKey = DedupKeyBuilder.build(
            kind = parsed.kind,
            amount = parsed.amount,
            utr = parsed.utr,
            merchant = parsed.merchant,
            receivedAt = now,
            windowMinutes = snapshot.dedupWindowMinutes
        )

        if (database.dedupDao().contains(dedupKey)) {
            return record(
                outcome(ParseOutcome.DUPLICATE, source, sourceDetail, now).copy(
                    amount = parsed.amount,
                    kind = parsed.kind,
                    merchant = parsed.merchant,
                    dedupKey = dedupKey
                )
            )
        }

        val event = PaymentEvent(
            kind = parsed.kind,
            amount = parsed.amount,
            currency = parsed.currency,
            bankOrUpi = parsed.bankOrUpi,
            merchant = parsed.merchant,
            utr = parsed.utr,
            account = parsed.account,
            balance = parsed.balance,
            category = parsed.category,
            source = source,
            sourceDetail = sourceDetail,
            rawText = parsed.rawText,
            dedupKey = dedupKey,
            receivedAt = now
        )

        database.dedupDao().insert(DedupEntry(dedupKey, now))
        database.paymentEventDao().insert(event)
        database.dedupDao().pruneBefore(now - PRUNE_AFTER_MILLIS)
        onStored()

        val announcement = AnnouncementFormatter.format(
            template = snapshot.templateFor(parsed.kind),
            amount = parsed.amount,
            merchant = parsed.merchant,
            bankOrUpi = parsed.bankOrUpi,
            kind = parsed.kind
        )
        tts.announce(announcement)

        return record(
            outcome(ParseOutcome.ACCEPTED, source, sourceDetail, now).copy(
                accepted = true,
                amount = parsed.amount,
                kind = parsed.kind,
                merchant = parsed.merchant,
                dedupKey = dedupKey,
                parserName = parsed.parserName,
                announcement = announcement,
                event = event
            )
        )
    }

    private fun outcome(
        reason: String,
        source: String,
        sourceDetail: String?,
        now: Long
    ) = ParseOutcome(
        reason = reason,
        accepted = false,
        at = now,
        source = source,
        sourceDetail = sourceDetail
    )

    private fun record(outcome: ParseOutcome): ParseOutcome {
        _lastOutcome.value = outcome
        return outcome
    }

    private companion object {
        const val PRUNE_AFTER_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
