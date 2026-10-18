package com.vivekray898.payalert.ui.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vivekray898.payalert.ui.components.BlueHeader
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.ValueRow
import com.vivekray898.payalert.upi.QrEncoder
import com.vivekray898.payalert.upi.UpiLink

/**
 * The reference's "scan and pay" utility is a QR code generator for the user's own UPI id.
 * PayAlert builds the same standard UPI payment URI and encodes it, without needing a
 * camera or any account. The URI is shown as text too, so it can be copied into any app.
 */
@Composable
fun QrScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppGraphHolder.settings(context) }

    var upiId by remember { mutableStateOf(settings.upiId) }
    var payeeName by remember { mutableStateOf(settings.payeeName) }
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    val amount = amountText.trim().toDoubleOrNull()
    val uri = if (upiId.isBlank()) "" else UpiLink.build(upiId, payeeName, amount, noteText)

    Column(modifier = Modifier.fillMaxSize()) {
        BlueHeader(title = "Scan to Pay")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Your Payment QR Code",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (uri.isEmpty()) {
                        IconQrPlaceholder()
                        Text("No UPI Address", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Add your UPI address to generate payment QR code",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(onClick = { showEditor = true }) { Text("＋  Add UPI Address") }
                    } else {
                        val bitmap = remember(uri) { renderQr(uri) }
                        if (bitmap == null) {
                            Text(
                                "Could not generate the code — the UPI id may be too long.",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "UPI payment QR code",
                                modifier = Modifier
                                    .size(240.dp)
                                    .background(androidx.compose.ui.graphics.Color.White)
                            )
                        }
                        Text(payeeName.ifBlank { upiId }, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("UPI link", uri))
                                copied = true
                            }) { Text(if (copied) "Copied" else "Copy link") }
                            Button(onClick = { openUpiApp(context, uri) }) { Text("Open app") }
                        }
                        TextButton(onClick = { showEditor = true }) { Text("Edit UPI details") }
                    }
                }
            }

            if (showEditor) {
                SectionCard(title = "Payment details") {
                    OutlinedTextField(
                        value = upiId,
                        onValueChange = {
                            upiId = it
                            error = null
                            settings.upiId = it
                        },
                        label = { Text("UPI id, e.g. name@bank") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = payeeName,
                        onValueChange = {
                            payeeName = it
                            settings.payeeName = it
                        },
                        label = { Text("Payee name shown to payer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Note (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (amountText.isNotBlank() && amount == null) {
                        Text("Amount must be a number", color = MaterialTheme.colorScheme.error)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (upiId.isNotBlank()) {
                        TextButton(onClick = { showEditor = false }) { Text("Done") }
                    }
                }
            }

            SectionCard(title = "How to Use") {
                Text(
                    "1. Share or display this QR code to receive payments\n" +
                        "2. The payer can scan it with any UPI app\n" +
                        "3. Add an amount to create a specific payment QR",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IconQrPlaceholder() {
    Icon(
        painter = androidx.compose.ui.res.painterResource(id = com.vivekray898.payalert.R.drawable.ic_qr_code),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(72.dp)
    )
}

private fun openUpiApp(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
    try {
        context.startActivity(Intent.createChooser(intent, "Pay with"))
    } catch (error: Exception) {
        // No UPI app installed — the link can still be copied and shared.
    }
}

/** Renders [content] as a QR bitmap, or null when the payload cannot be encoded. */
internal fun renderQr(content: String, size: Int = 640): Bitmap? {
    val matrix = QrEncoder.matrix(content, size) ?: return null
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

/**
 * Small holder so the QR screen does not need a ViewModel for two persisted strings.
 * Values are stored in the same preference file as every other setting.
 */
private object AppGraphHolder {
    private var instance: PayAlertQrSettings? = null

    fun settings(context: Context): PayAlertQrSettings =
        instance ?: PayAlertQrSettings(context.applicationContext).also { instance = it }
}

/** UPI identity settings persisted alongside the rest of the app settings. */
class PayAlertQrSettings(context: Context) {
    private val prefs = context.getSharedPreferences("payalert_upi", Context.MODE_PRIVATE)

    var upiId: String
        get() = prefs.getString("upi_id", "") ?: ""
        set(value) = prefs.edit().putString("upi_id", value.trim()).apply()

    var payeeName: String
        get() = prefs.getString("payee_name", "") ?: ""
        set(value) = prefs.edit().putString("payee_name", value.trim()).apply()
}
