package com.vivekray898.payalert.ui.review

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.health.ReviewOutcome
import com.vivekray898.payalert.health.ReviewPromptPolicy
import com.vivekray898.payalert.health.ReviewPromptStore

/**
 * Shows the one-off rating prompt once the app has announced enough payments to have
 * earned an opinion. The decision itself lives in [ReviewPromptPolicy]; this only draws
 * it and records the outcome.
 */
@Composable
fun ReviewPromptHost(store: ReviewPromptStore) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val paymentsSeen by remember { graph.database.paymentEventDao().observeCount() }
        .collectAsState(initial = 0)

    var state by remember { mutableStateOf(store.load()) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(paymentsSeen, state) {
        if (!visible && ReviewPromptPolicy.shouldPrompt(state, paymentsSeen, System.currentTimeMillis())) {
            visible = true
        }
    }

    if (!visible) return

    fun record(outcome: ReviewOutcome) {
        state = ReviewPromptPolicy.apply(state, outcome, System.currentTimeMillis())
        store.save(state)
        visible = false
    }

    AlertDialog(
        onDismissRequest = { record(ReviewOutcome.LATER) },
        title = { Text("Enjoying PayAlert?") },
        text = {
            Text(
                "PayAlert has announced $paymentsSeen payments so far. A rating helps other " +
                    "people find an app that reads their payments aloud."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                record(ReviewOutcome.RATED)
                openStoreListing(context)
            }) { Text("Rate on Play") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { record(ReviewOutcome.LATER) }) { Text("Later") }
                TextButton(onClick = { record(ReviewOutcome.DISMISSED) }) { Text("No thanks") }
            }
        }
    )
}

/** Opens the store listing, falling back to the web listing when the store is absent. */
private fun openStoreListing(context: Context) {
    val id = context.packageName
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (error: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$id")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
