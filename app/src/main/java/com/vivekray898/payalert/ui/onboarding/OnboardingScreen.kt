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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vivekray898.payalert.R
import com.vivekray898.payalert.onboarding.OnboardingRepository
import com.vivekray898.payalert.onboarding.OnboardingSnapshot
import com.vivekray898.payalert.onboarding.OnboardingStep
import com.vivekray898.payalert.ui.PermissionStatus
import kotlinx.coroutines.delay

/**
 * One step in the onboarding wizard. Each step corresponds to one permission and shows
 * its live state, with a Grant/Request button when pending and a green check when granted.
 */
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
    val circleColor = if (granted) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val iconTint = if (granted) {
        MaterialTheme.colorScheme.onTertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(circleColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (granted) R.drawable.ic_check_circle else R.drawable.ic_warning
                    ),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(36.dp)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            if (!granted) {
                Button(onClick = { onGrant() }) {
                    Text("Grant")
                }
                if (step == OnboardingStep.NOTIFICATION_ACCESS ||
                    step == OnboardingStep.BATTERY
                ) {
                    TextButton(onClick = { onGrant() }) {
                        Text("Open system settings")
                    }
                }
            } else {
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    textDecoration = TextDecoration.Underline
                )
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
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Set up PayAlert",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Step ${currentIndex + 1} of ${steps.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        StepCard(
            step = step,
            repository = repository,
            launchedEffectKey = launchedKey,
            onGrant = { grant(step) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(onClick = onComplete) {
                Text("Skip all")
            }
            if (currentIndex < steps.size - 1) {
                Button(
                    onClick = { currentIndex++ },
                    enabled = snapshot.get(step)
                ) {
                    Text("Next")
                }
            } else {
                Button(
                    onClick = {
                        repository.markComplete()
                        onComplete()
                    },
                    enabled = snapshot.get(step)
                ) {
                    Text("Finish")
                }
            }
        }
    }
}