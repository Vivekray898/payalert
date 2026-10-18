package com.vivekray898.payalert.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes

/**
 * PayAlert's finance-dashboard palette.
 *
 * Blue owns navigation and primary actions, while green and red keep the two money directions
 * visually distinct at a glance. The light scheme is the product default: a cool gray canvas
 * gives the white cards and blue header treatment enough contrast without adding visual noise.
 */
object Brand {
    val Primary = Color(0xFF1B63E8)
    val Ink = Color(0xFF12213F)
    val Background = Color(0xFFF4F7FB)
    val Surface = Color(0xFFFFFFFF)
    val Credit = Color(0xFF087A4D)
    val Debit = Color(0xFFC83F52)
    val Warning = Color(0xFFA56A00)
    val Accent = Color(0xFF5E4AE3)
}

private val LightColors = lightColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4ECFF),
    onPrimaryContainer = Brand.Ink,

    // Secondary is used by existing transaction rows for money out.
    secondary = Brand.Debit,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6EB),
    onSecondaryContainer = Color(0xFF5E1523),

    // Tertiary is used by existing transaction rows for money in.
    tertiary = Brand.Credit,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF4E8),
    onTertiaryContainer = Color(0xFF064B30),

    error = Brand.Debit,
    onError = Color.White,
    errorContainer = Color(0xFFFFE6EB),
    onErrorContainer = Color(0xFF5E1523),

    background = Brand.Background,
    onBackground = Brand.Ink,
    surface = Brand.Surface,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEBF0F7),
    onSurfaceVariant = Color(0xFF53627A),
    outline = Color(0xFFCAD3E1),
    outlineVariant = Color(0xFFDCE3ED),

    inverseSurface = Brand.Ink,
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFF9DBBFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DBBFF),
    onPrimary = Color(0xFF062A73),
    primaryContainer = Color(0xFF174AAB),
    onPrimaryContainer = Color(0xFFDCE7FF),

    secondary = Color(0xFFFFA0AD),
    onSecondary = Color(0xFF5E1523),
    secondaryContainer = Color(0xFF762B3C),
    onSecondaryContainer = Color(0xFFFFD9DE),

    tertiary = Color(0xFF7DDBA9),
    onTertiary = Color(0xFF003821),
    tertiaryContainer = Color(0xFF075B3B),
    onTertiaryContainer = Color(0xFFA6F2C5),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0E1728),
    onBackground = Color(0xFFE8EEF8),
    surface = Color(0xFF14233B),
    onSurface = Color(0xFFE8EEF8),
    surfaceVariant = Color(0xFF24344D),
    onSurfaceVariant = Color(0xFFB7C4D8),
    outline = Color(0xFF8494AD),
    outlineVariant = Color(0xFF3C4C65),

    inverseSurface = Color(0xFFE8EEF8),
    inverseOnSurface = Brand.Ink,
    inversePrimary = Brand.Primary
)

private val PayAlertTypography = Typography().copy(
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

private val PayAlertShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
)

/**
 * PayAlert's Material3 theme. Dynamic color is deliberately disabled so the financial signals
 * remain stable across devices. The app uses the light dashboard by default; the dark scheme is
 * retained for callers that explicitly opt into it.
 */
@Composable
fun PayAlertTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PayAlertTypography,
        shapes = PayAlertShapes,
        content = content
    )
}
