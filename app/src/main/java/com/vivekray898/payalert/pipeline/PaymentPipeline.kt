package com.vivekray898.payalert.pipeline

import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.parsers.DedupKeyBuilder
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.settings.AnnouncementFormatter
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.settings.SettingsSnapshot
import com.vivekray898.payalert.tts.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single pipeline every detector funnels into:
 *
 *   text -> parse -> filter -> dedup -> persist to Room -> announce via TTS
 *
 * Both the SMS receiver and the notification listener call [handle], which is why a
 * payment seen twice collapses to one event and one announcement.
 *
 * The dedup check-and-insert critical section is serialized with a [Mutex] so that
 * three UPI apps posting the same payment asynchronously cannot race past each other.
 * TTS announcement stays outside the lock: speech must never block detection.
 */
class PaymentPipeline(
    private val store: PaymentStore,
    private val parser: ParserRegistry,
    /** Snapshot supplier so the pipeline stays unit-testable without an Android Context. */
    private val settings: () -> SettingsSnapshot,
    /** Announcement callback; kept as a lambda so tests can count utterances. */
    private val tts: (String) -> Unit,
    /** Notified after a payment is stored, so home-screen widgets can refresh. */
    private val onStored: () -> Unit = {}
) {
    private val _lastOutcome = MutableStateFlow<ParseOutcome?>(null)
    val lastOutcome: StateFlow<ParseOutcome?> = _lastOutcome.asStateFlow()

    private val dedupMutex = Mutex()

    suspend fun handle(
        text: String,
        source: String,
        sourceDetail: String?,
        now: Long = System.currentTimeMillis()
    ): ParseOutcome {
        val snapshot = settings()

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

        // Critical section: check-and-insert must be atomic so concurrent notifications
        // for the same payment cannot both observe "not present" and both store.
        return dedupMutex.withLock {
            acceptOrDuplicate(parsed, source, sourceDetail, now)
        }
    }

    private suspend fun acceptOrDuplicate(
        parsed: com.vivekray898.payalert.parsers.ParsedEvent,
        source: String,
        sourceDetail: String?,
        now: Long
    ): ParseOutcome {
        val snapshot = settings()

        val exact = DedupKeyBuilder.exactKey(
            kind = parsed.kind,
            amount = parsed.amount,
            utr = parsed.utr,
            merchant = parsed.merchant,
            receivedAt = now,
            windowMinutes = snapshot.dedupWindowMinutes
        )
        val semantic = DedupKeyBuilder.semanticKey(
            kind = parsed.kind,
            amount = parsed.amount,
            currency = parsed.currency,
            merchant = parsed.merchant,
            account = parsed.account,
            receivedAt = now
        )

        if (store.containsExact(exact) || store.containsSemantic(semantic)) {
            return record(
                outcome(ParseOutcome.DUPLICATE, source, sourceDetail, now).copy(
                    amount = parsed.amount,
                    kind = parsed.kind,
                    merchant = parsed.merchant,
                    dedupKey = exact
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
            dedupKey = exact,
            receivedAt = now
        )

        // Write both keys on every accepted event. INSERT OR IGNORE keeps the
        // primary-key guard as the source of truth for exact keys.
        store.insertDedup(exact, semantic, now)
        store.insertSemantic(semantic, now)
        store.insertEvent(event)
        store.pruneExactBefore(now - PRUNE_AFTER_MILLIS)
        store.pruneSemanticBefore(now - SEMANTIC_PRUNE_AFTER_MILLIS)
        onStored()

        val announcement = AnnouncementFormatter.format(
            template = snapshot.templateFor(parsed.kind),
            amount = parsed.amount,
            merchant = parsed.merchant,
            bankOrUpi = parsed.bankOrUpi,
            kind = parsed.kind
        )
        // Announcement stays outside the lock: speech must never block detection.
        tts(announcement)

        return record(
            outcome(ParseOutcome.ACCEPTED, source, sourceDetail, now).copy(
                accepted = true,
                amount = parsed.amount,
                kind = parsed.kind,
                merchant = parsed.merchant,
                dedupKey = exact,
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
        const val SEMANTIC_PRUNE_AFTER_MILLIS = 10L * 60 * 1000
    }
}