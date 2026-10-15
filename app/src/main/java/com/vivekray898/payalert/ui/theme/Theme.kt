package com.vivekray898.payalert.ui.theme

import androidx.compose.runtime.Composable

/** Brand palette extracted from the launcher icon
 * (`app/src/main/ic_launcher-playstore.png`, 512x512, sampled with PIL).
 *
 * Method: every non-background pixel was clustered by nearest-neighbour to four seed colours
 * (navy, orange, teal, gold) and the modal saturated pixel in each cluster was taken as the
 * source constant. The four source values below are the single most representative pixels; the
 * Material3 ramps derived from them live in LightColors / DarkColors.
 *
 *  - Primary    (navy wordmark "Pay")   : #122550
 *  - Secondary  (orange wordmark "Alert", bell body, coin) : #F65026
 *  - Tertiary   (green $ inside the bell) : #058E7A
 *  - Accent     (gold bell top, coin face) : #FE8D13
 *
 * Reproduce with: python3 -c "from PIL import Image; import collections; ..." — see
 * screenshots/PALETTE.md for the exact command and its output.
 */
object Brand {
    val Primary    = androidx.compose.ui.graphics.Color(0xFF122550)
    val Secondary  = androidx.compose.ui.graphics.Color(0xFFF65026)
    val Tertiary   = androidx.compose.ui.graphics.Color(0xFF058E7A)
    val Accent     = androidx.compose.ui.graphics.Color(0xFFFE8D13)
}

// ---------------------------------------------------------------------------
// Derive tints from the four source colours so the whole scheme stays on-brand.
// Alpha-tints use ARGB; full palette tints use HSV interpolation toward white.
// ---------------------------------------------------------------------------

/** Light tint of `c` blended toward white by `f` (0 = pure `c`, 1 = white). */
private fun tintTowardsWhite(c: androidx.compose.ui.graphics.Color, f: Float): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(
        c.red + (1f - c.red) * f,
        c.green + (1f - c.green) * f,
        c.blue + (1f - c.blue) * f,
        c.alpha
    )
}

/** Dark tint of `c` blended toward black by `f` (0 = pure `c`, 1 = black). */
private fun tintTowardsBlack(c: androidx.compose.ui.graphics.Color, f: Float): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(
        c.red * (1f - f),
        c.green * (1f - f),
        c.blue * (1f - f),
        c.alpha
    )
}

// Light palette — Material3 DayNight. No dynamic color: the brand palette wins on every device.
private val LightColors = androidx.compose.material3.lightColorScheme(
    primary             = Brand.Primary,
    onPrimary           = androidx.compose.ui.graphics.Color.White,
    primaryContainer    = tintTowardsWhite(Brand.Primary, 0.92f),
    onPrimaryContainer  = Brand.Primary,
    secondary           = Brand.Secondary,
    onSecondary         = androidx.compose.ui.graphics.Color.White,
    secondaryContainer  = tintTowardsWhite(Brand.Secondary, 0.85f),
    onSecondaryContainer= Brand.Secondary,
    tertiary            = Brand.Tertiary,
    onTertiary          = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer   = tintTowardsWhite(Brand.Tertiary, 0.88f),
    onTertiaryContainer = Brand.Tertiary,
    error               = androidx.compose.ui.graphics.Color(0xFFB3261E),
    onError             = androidx.compose.ui.graphics.Color.White,
    errorContainer      = tintTowardsWhite(androidx.compose.ui.graphics.Color(0xFFB3261E), 0.85f),
    onErrorContainer    = androidx.compose.ui.graphics.Color(0xFF410E0C),

    background = androidx.compose.ui.graphics.Color(0xFFFAFAFA),
    onBackground = Brand.Primary,
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = Brand.Primary,
    surfaceVariant = tintTowardsWhite(Brand.Primary, 0.94f),
    onSurfaceVariant = Brand.Primary.copy(alpha = 0.6f),
    outline = Brand.Primary.copy(alpha = 0.20f),
    outlineVariant = Brand.Primary.copy(alpha = 0.12f),

    inverseSurface = Brand.Primary,
    inverseOnSurface = androidx.compose.ui.graphics.Color.White,
    inversePrimary = tintTowardsWhite(Brand.Primary, 0.85f)
)

// Dark palette — hand-tuned from the same four source colours, not a naive invert.
private val DarkColors = androidx.compose.material3.darkColorScheme(
    // Primary stays navy but brightened so it reads well on dark surfaces.
    primary             = tintTowardsWhite(Brand.Primary, 0.78f),
    onPrimary           = Brand.Primary,
    primaryContainer    = tintTowardsBlack(Brand.Primary, 0.55f),
    onPrimaryContainer  = tintTowardsWhite(Brand.Primary, 0.85f),

    secondary           = Brand.Secondary,
    onSecondary         = androidx.compose.ui.graphics.Color.White,
    secondaryContainer  = tintTowardsBlack(Brand.Secondary, 0.45f),
    onSecondaryContainer= tintTowardsWhite(Brand.Secondary, 0.9f),

    tertiary            = tintTowardsWhite(Brand.Tertiary, 0.55f),
    onTertiary          = Brand.Tertiary,
    tertiaryContainer   = tintTowardsBlack(Brand.Tertiary, 0.50f),
    onTertiaryContainer = tintTowardsWhite(Brand.Tertiary, 0.85f),

    error               = androidx.compose.ui.graphics.Color(0xFFF2B8B5),
    onError             = androidx.compose.ui.graphics.Color(0xFF450E0C),
    errorContainer      = androidx.compose.ui.graphics.Color(0xFF5C1A16),
    onErrorContainer    = androidx.compose.ui.graphics.Color(0xFFF2B8B5),

    background = androidx.compose.ui.graphics.Color(0xFF10131C),
    onBackground = tintTowardsWhite(Brand.Primary, 0.85f),
    surface = androidx.compose.ui.graphics.Color(0xFF171B24),
    onSurface = tintTowardsWhite(Brand.Primary, 0.88f),
    surfaceVariant = tintTowardsBlack(Brand.Primary, 0.22f),
    onSurfaceVariant = tintTowardsWhite(Brand.Primary, 0.62f),
    outline = tintTowardsWhite(Brand.Primary, 0.20f),
    outlineVariant = tintTowardsWhite(Brand.Primary, 0.14f),

    inverseSurface = tintTowardsWhite(Brand.Primary, 0.88f),
    inverseOnSurface = Brand.Primary,
    inversePrimary = Brand.Primary
)

/** PayAlert's Material3 theme. Dynamic color is deliberately disabled — the brand palette
 * (derived from the launcher icon) is the source of truth on every device, day and night. */
@Composable
fun PayAlertTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
