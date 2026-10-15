package com.vivekray898.payalert.pipeline

import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.parsers.TestConfig
import com.vivekray898.payalert.settings.SettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pipeline-level dedup tests. They run on the JVM against [FakePaymentStore], so the
 * dedup critical section — parse, check, insert — is exercised exactly as production
 * runs it, without a Room database (which needs an Android runtime).
 *
 * The three payloads are the notification bodies used by the on-device stub test in
 * BUILD_AND_VERIFICATION.md section 10: one payment, three apps, three different UTRs,
 * so only the semantic tier can collapse them.
 */
class PaymentPipelineTest {

    private val parser = ParserRegistry(TestConfig.shipped)

    private val phonePeText = "Received Rs.500 from ZOMATO@ybl. UTR 111111111111"
    private val paytmText = "Rs.500 credited to your account from zomato@ybl. Ref 222222222222"
    private val gpayText = "You received \u20b9500 from Zomato. UPI Ref 333333333333"

    private val baseNow = 1_760_000_000_000L // fixed epoch; all sequential events stay in one 5-min bucket

    private fun pipeline(
        store: PaymentStore,
        announcements: MutableList<String> = CopyOnWriteArrayList()
    ) = PaymentPipeline(
        store = store,
        parser = parser,
        settings = { SettingsSnapshot() },
        tts = { announcements.add(it) }
    )

    // ------------------------------------------------------------------
    // Test A — sequential cross-app semantic collapse
    // ------------------------------------------------------------------

    @Test
    fun `Test A - three apps three wordings one payment collapse sequentially`() = runBlocking {
        val store = FakePaymentStore()
        val tts = CopyOnWriteArrayList<String>()
        val pipeline = pipeline(store, tts)

        val r1 = pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow)
        val r2 = pipeline.handle(paytmText, "notification", "net.one97.paytm", baseNow + 200)
        val r3 = pipeline.handle(gpayText, "notification", "com.google.android.apps.nbu.paisa.user", baseNow + 400)

        assertEquals(ParseOutcome.ACCEPTED, r1.reason)
        assertEquals(ParseOutcome.DUPLICATE, r2.reason)
        assertEquals(ParseOutcome.DUPLICATE, r3.reason)
        assertEquals(1, store.eventCount())
        assertEquals(1, tts.size)
        // All three parsed to one semantic key — assert it directly, not just the outcome.
        val semanticKeys = store.rows().mapNotNull { it.semanticKey }.toSet()
        assertEquals(1, semanticKeys.size)
        assertTrue("semantic key should be txn-shaped", semanticKeys.single().startsWith("txn:credit:500.00:INR:-:zomato:"))
        // The exact keys differ (three UTRs), proving the semantic tier did the collapsing:
        // only the first payment's exact key was stored, plus one semantic row.
        assertEquals(1, store.rows().count { it.dedupKey.startsWith("utr:") })
        assertEquals(1, store.rows().count { it.semanticKey != null && it.dedupKey == it.semanticKey })
    }

    // ------------------------------------------------------------------
    // Test B — concurrent mutex proof (the test that fails if the Mutex is removed)
    // ------------------------------------------------------------------

    @Test
    fun `Test B - three concurrent notifications produce exactly one accepted`() = runBlocking {
        // Identical store latency to the control test below (100 ms checks, 50 ms
        // writes). The only difference from the control is the shared pipeline mutex:
        // the control triple-stores on this store, the mutex must not.
        val store = FakePaymentStore(checkDelayMs = 100, insertDelayMs = 50)
        val tts = CopyOnWriteArrayList<String>()
        val pipeline = pipeline(store, tts)

        val results = listOf(
            async(Dispatchers.Default) { pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow) },
            async(Dispatchers.Default) { pipeline.handle(paytmText, "notification", "net.one97.paytm", baseNow + 200) },
            async(Dispatchers.Default) { pipeline.handle(gpayText, "notification", "com.google.android.apps.nbu.paisa.user", baseNow + 400) }
        ).awaitAll()

        val reasons = results.map { it.reason }
        assertEquals("expected exactly one accepted, got $reasons", 1, reasons.count { it == ParseOutcome.ACCEPTED })
        assertEquals("expected exactly two duplicates, got $reasons", 2, reasons.count { it == ParseOutcome.DUPLICATE })
        assertEquals("expected exactly one stored event", 1, store.eventCount())
        assertEquals("expected exactly one announcement", 1, tts.size)
    }

    /**
     * Control for Test B: three *separate* pipelines (each with its own mutex) over the
     * same store must all win the race when the store's checks are slow. This proves the
     *100 ms check delay genuinely opens a race window — i.e. Test B is not passing
     * vacuously because the coroutines happened to serialise.
     */
    @Test
    fun `control - without a shared mutex the race window produces three accepted`() = runBlocking {
        val store = FakePaymentStore(checkDelayMs = 100, insertDelayMs = 50)

        val results = listOf(
            async(Dispatchers.Default) { pipeline(store).handle(phonePeText, "notification", "com.phonepe.app", baseNow) },
            async(Dispatchers.Default) { pipeline(store).handle(paytmText, "notification", "net.one97.paytm", baseNow + 200) },
            async(Dispatchers.Default) { pipeline(store).handle(gpayText, "notification", "com.google.android.apps.nbu.paisa.user", baseNow + 400) }
        ).awaitAll()

        assertEquals(
            "the race window should let all three pipelines pass the check before any insert; " +
                "got ${results.map { it.reason }}\nthreads=${Runtime.getRuntime().availableProcessors()} " +
                "timeline=${store.timeline.take(24)}",
            3,
            results.count { it.reason == ParseOutcome.ACCEPTED }
        )
    }

    // ------------------------------------------------------------------
    // Test C — six-minute regression: a genuine second payment is accepted
    // ------------------------------------------------------------------

    @Test
    fun `Test C - same payment six minutes later with a new reference is accepted`() = runBlocking {
        val store = FakePaymentStore()
        val tts = CopyOnWriteArrayList<String>()
        val pipeline = pipeline(store, tts)

        // A real second payment carries a different reference. (The exact key is
        // timeless when a UTR is present — "reference number dominates merchant and
        // time" is pinned by DedupKeyBuilderTest — so six minutes matter only for
        // the bucketed tiers.)
        val first = pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow)
        val second = pipeline.handle(paytmText, "notification", "net.one97.paytm", baseNow + 6 * 60_000)

        assertEquals(ParseOutcome.ACCEPTED, first.reason)
        assertEquals(ParseOutcome.ACCEPTED, second.reason)
        assertEquals(2, store.eventCount())
        assertEquals(2, tts.size)
        // The semantic keys differ only by the 5-minute bucket.
        val buckets = store.rows().mapNotNull { it.semanticKey }.map { it.substringAfterLast(':') }.toSet()
        assertEquals("six minutes must fall in two different 5-minute buckets", 2, buckets.size)
    }

    @Test
    fun `Test C2 - repeated reference stays a duplicate even after six minutes`() = runBlocking {
        val store = FakePaymentStore()
        val pipeline = pipeline(store)

        val first = pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow)
        val repeat = pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow + 6 * 60_000)

        assertEquals(ParseOutcome.ACCEPTED, first.reason)
        // Same UTR = same payment: the reference dominates the time window by design.
        assertEquals(ParseOutcome.DUPLICATE, repeat.reason)
        assertEquals(1, store.eventCount())
    }

    // ------------------------------------------------------------------
    // Test D — prune cutoffs: semantic rows die at 10 minutes, exact rows live 3 days
    // ------------------------------------------------------------------

    @Test
    fun `Test D - semantic prune removes stale semantic row but keeps exact row`() = runBlocking {
        val store = FakePaymentStore()
        val pipeline = pipeline(store)

        val staleAt = baseNow - 11 * 60_000
        val staleSemanticKey = "txn:credit:999.00:INR:-:stalemerchant:999999"
        // Semantic-only row (insertSemantic shape: dedup_key == semantic_key).
        store.seedRow(dedupKey = staleSemanticKey, seenAt = staleAt, semanticKey = staleSemanticKey)
        // Accepted-payment exact row shape: exact dedup_key carrying its semantic key.
        // The old `semantic_key IS NOT NULL` predicate deleted this row at 10 minutes.
        store.seedRow(dedupKey = "utr:999999999999|credit|999.00", seenAt = staleAt, semanticKey = staleSemanticKey)

        val outcome = pipeline.handle(phonePeText, "notification", "com.phonepe.app", baseNow)

        assertEquals(ParseOutcome.ACCEPTED, outcome.reason)
        val keys = store.rows().map { it.dedupKey }
        assertTrue("stale semantic row must be pruned: $keys", staleSemanticKey !in keys)
        assertTrue(
            "exact row younger than 3 days must survive the 10-minute semantic prune: $keys",
            "utr:999999999999|credit|999.00" in keys
        )
        // The pipeline must pass the documented cutoffs to the store.
        assertEquals(baseNow - 10 * 60_000, store.semanticPruneCutoffs.last())
        assertEquals(baseNow - 3L * 24 * 60 * 60 * 1000, store.exactPruneCutoffs.last())
    }

    // ------------------------------------------------------------------
    // Fake store — implements the same semantics as the Room SQL, including the
    // fixed `dedup_key = semantic_key` prune predicate from DedupDao.
    // ------------------------------------------------------------------

    /**
     * Models the real store's concurrency shape: reads are lock-free and fast to
     * interleave, writes are comparatively slow (Room write = disk I/O). An internal
     * lock around the *checks* would serialise the race away — a store that queues
     * every read behind every other read can never demonstrate a check-and-insert
     * race, which is precisely what was observed while writing this fake: the
     * queued checks ended in FIFO order and the first inserter always won. The
     * write latency [insertDelayMs] is what opens the window in production.
     */
    private class FakePaymentStore(
        private val checkDelayMs: Long = 0,
        private val insertDelayMs: Long = 0
    ) : PaymentStore {

        data class Row(val dedupKey: String, val seenAt: Long, val semanticKey: String?)

        private val rowsBy = java.util.concurrent.ConcurrentHashMap<String, Row>()
        private val events = CopyOnWriteArrayList<PaymentEvent>()

        /** Timestamped thread log, asserted on in the control test's failure message. */
        val timeline = CopyOnWriteArrayList<String>()
        private val t0 = System.nanoTime()

        private fun stamp(label: String) {
            timeline.add("+${(System.nanoTime() - t0) / 1_000}us ${Thread.currentThread().name.takeLast(6)} $label")
        }

        val exactPruneCutoffs = CopyOnWriteArrayList<Long>()
        val semanticPruneCutoffs = CopyOnWriteArrayList<Long>()

        fun rows(): List<Row> = rowsBy.values.toList()
        fun eventCount(): Int = events.size
        fun seedRow(dedupKey: String, seenAt: Long, semanticKey: String?) {
            rowsBy[dedupKey] = Row(dedupKey, seenAt, semanticKey)
        }

        override suspend fun containsExact(key: String): Boolean {
            stamp("check-exact begin (delay=$checkDelayMs)")
            if (checkDelayMs > 0) delay(checkDelayMs)
            val hit = rowsBy.values.any { it.dedupKey == key }
            stamp("check-exact end hit=$hit")
            return hit
        }

        override suspend fun containsSemantic(key: String): Boolean {
            stamp("check-semantic begin")
            if (checkDelayMs > 0) delay(checkDelayMs)
            val hit = rowsBy.values.any { it.semanticKey == key }
            stamp("check-semantic end hit=$hit")
            return hit
        }

        override suspend fun insertDedup(exactKey: String, semanticKey: String?, seenAt: Long) {
            // The slow write: models Room's disk I/O, the gap a check-and-insert race
            // needs in order to double-store.
            if (insertDelayMs > 0) delay(insertDelayMs)
            rowsBy.putIfAbsent(exactKey, Row(exactKey, seenAt, semanticKey))
            stamp("insert $exactKey")
        }

        override suspend fun insertSemantic(key: String, seenAt: Long) {
            if (insertDelayMs > 0) delay(insertDelayMs)
            rowsBy.putIfAbsent(key, Row(key, seenAt, key))
        }

        override suspend fun insertEvent(event: PaymentEvent) {
            events.add(event)
        }

        override suspend fun pruneExactBefore(cutoff: Long) {
            exactPruneCutoffs.add(cutoff)
            rowsBy.values.removeIf { it.seenAt < cutoff }
        }

        override suspend fun pruneSemanticBefore(cutoff: Long) {
            semanticPruneCutoffs.add(cutoff)
            // Mirrors the fixed SQL: DELETE WHERE dedup_key = semantic_key AND seen_at < cutoff.
            rowsBy.values.removeIf { it.semanticKey != null && it.dedupKey == it.semanticKey && it.seenAt < cutoff }
        }
    }
}
