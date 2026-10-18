package com.vivekray898.payalert.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vivekray898.payalert.R
import com.vivekray898.payalert.onboarding.OnboardingRepository
import com.vivekray898.payalert.onboarding.OnboardingSnapshot
import com.vivekray898.payalert.onboarding.OnboardingStep
import com.vivekray898.payalert.ui.PermissionStatus
import kotlinx.coroutines.delay

/** One permission step, presented as a calm, focused dashboard card. */
@Composable
private fun StepCard(
    step: OnboardingStep,
    repository: OnboardingRepository,
    launchedEffectKey: Int,
    onGrant: () -> Unit
) {
    // The snapshot must be read inside the composable to get the latest state after
    // system settings returns.
    val snapshot = repository.snapshot()
    val granted = snapshot.get(step)
    val title = when (step) {
        OnboardingStep.POST_NOTIFICATIONS -> "Post notifications"
        OnboardingStep.SMS -> "SMS alerts"
        OnboardingStep.NOTIFICATION_ACCESS -> "Notification access"
        OnboardingStep.BATTERY -> "Battery optimisation"
    }
    val description = when (step) {
        OnboardingStep.POST_NOTIFICATIONS -> "Let PayAlert show a persistent status notification"
        OnboardingStep.SMS -> "Read bank transaction SMS so payments are detected"
        OnboardingStep.NOTIFICATION_ACCESS -> "Read UPI and banking app notifications in real time"
        OnboardingStep.BATTERY -> "Keep PayAlert alive when the screen is off (optional)"
    }
    // When the user has already granted, don't re-launch the intent; just poll once.
    // When it's still pending, launch the intent once and let LaunchedEffect re-check.
    var key by remember(step) { mutableStateOf(0) }
    LaunchedEffect(key, launchedEffectKey) {
        if (!granted && key == 0) {
            key++
            onGrant()
        }
        // After launching, poll every 500 ms until granted or the user leaves.
        while (!repository.snapshot().get(step)) {
            delay(500L)
        }
    }
    val statusColor = if (granted) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val statusContainer = if (granted) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = statusContainer,
                    contentColor = statusColor,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(
                                id = if (granted) R.drawable.ic_check_circle else R.drawable.ic_warning
                            ),
                            contentDescription = if (granted) "Permission granted" else "Permission required",
                            tint = statusColor,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Surface(
                    color = statusContainer,
                    contentColor = statusColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (granted) "Ready" else "Action needed",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!granted) {
                Button(
                    onClick = { onGrant() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable permission")
                }
                if (step == OnboardingStep.NOTIFICATION_ACCESS ||
                    step == OnboardingStep.BATTERY
                ) {
                    OutlinedButton(
                        onClick = { onGrant() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open system settings")
                    }
                }
            } else {
                Text("Permission is ready. You can continue.", color = statusColor)
            }
        }
    }
}

/**
 * Full-screen onboarding wizard. Walks through four steps, one at a time, and sets a
 * "completed" flag so it is shown only once on first launch. Re-entered from Settings
 * via [reenter].
 */
@Composable
fun OnboardingScreen(
    repository: OnboardingRepository,
    onComplete: () -> Unit,
    requestedStep: OnboardingStep? = null,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableStateOf(requestedStep?.ordinal ?: 0) }
    // Re-read the live permission state on every resume. A plain function call is not
    // tracked by Compose, so a grant made in system Settings would not trigger a
    // recomposition without this.
    var snapshot by remember { mutableStateOf(repository.snapshot()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = repository.snapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val steps = listOf(
        OnboardingStep.POST_NOTIFICATIONS,
        OnboardingStep.SMS,
        OnboardingStep.NOTIFICATION_ACCESS,
        OnboardingStep.BATTERY
    )
    val step = steps[currentIndex]
    var launchedKey by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) launchedKey++
    }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = (Manifest.permission.RECEIVE_SMS in result && result[Manifest.permission.RECEIVE_SMS] == true) &&
            (Manifest.permission.READ_SMS in result && result[Manifest.permission.READ_SMS] == true)
        if (allGranted) launchedKey++
    }

    fun grant(step: OnboardingStep) {
        when (step) {
            OnboardingStep.POST_NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Pre-API 33: always granted.
                    launchedKey++
                }
            }
            OnboardingStep.SMS -> {
                val permissions = listOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                ).toTypedArray()
                smsLauncher.launch(permissions)
            }
            OnboardingStep.NOTIFICATION_ACCESS -> {
                // Opens system settings; cannot be granted programmatically.
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
                // Polling LaunchedEffect will detect the change.
            }
            OnboardingStep.BATTERY -> {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "PayAlert logo",
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.padding(6.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Set up PayAlert",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "A quick setup for reliable payment alerts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                    )
                }
                Text(
                    "${currentIndex + 1}/${steps.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (index <= currentIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }
            Text(
                text = when (step) {
                    OnboardingStep.POST_NOTIFICATIONS -> "Keep important alerts visible"
                    OnboardingStep.SMS -> "Read bank payment messages"
                    OnboardingStep.NOTIFICATION_ACCESS -> "Listen for UPI payments"
                    OnboardingStep.BATTERY -> "Stay reliable in the background"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            StepCard(
                step = step,
                repository = repository,
                launchedEffectKey = launchedKey,
                onGrant = { grant(step) }
            )
            Text(
                text = "You can change these permissions later from Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onComplete) {
                    Text("Skip setup")
                }
                if (currentIndex < steps.size - 1) {
                    Button(
                        onClick = { currentIndex++ },
                        enabled = snapshot.get(step)
                    ) {
                        Text("Continue")
                    }
                } else {
                    Button(
                        onClick = {
                            repository.markComplete()
                            onComplete()
                        },
                        enabled = snapshot.get(step)
                    ) {
                        Text("Finish setup")
                    }
                }
            }
        }
    }
}
