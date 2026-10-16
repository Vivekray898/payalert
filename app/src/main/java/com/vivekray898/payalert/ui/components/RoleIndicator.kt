package com.vivekray898.payalert.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Converts the role values used by team state into the labels shown to a person. */
internal fun roleDisplayName(role: String?): String? = when (role?.trim()?.lowercase(Locale.ROOT)) {
    "owner" -> "Owner"
    "employee" -> "Employee"
    else -> null
}

/**
 * Compact, read-only role badge for screens that already have role state available.
 *
 * Unknown or absent role values render nothing so an unconfigured device does not show a guessed
 * identity. A caller can collect its role state and pass the current value to [role].
 */
@Composable
fun RoleIndicator(
    role: String?,
    modifier: Modifier = Modifier
) {
    val label = roleDisplayName(role) ?: return
    Surface(
        modifier = modifier.semantics { contentDescription = "Role: $label" },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
