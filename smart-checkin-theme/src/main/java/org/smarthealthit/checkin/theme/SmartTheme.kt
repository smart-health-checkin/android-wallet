package org.smarthealthit.checkin.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The palette of smart-health-checkin.org (`--theme-*` in the site's
 * `/assets/smart-design.css`), with a light and a dark value for each color.
 * Every text color reaches 4.5:1 on every background color in its mode, and
 * each status color on its own wash.
 */
@Immutable
data class SmartColors(
    /** `--bg`: the site's page; here, the top bar's fill under the page's cards. */
    val bg: Color,
    /** `--bg-alt`: the app's page, a step below the cards (a well in dark). */
    val bgAlt: Color,
    /** `--surface`: cards and bars. */
    val surface: Color,
    /** `--surface-alt`: quiet panels, rows inside a card. */
    val surfaceAlt: Color,
    /** `--fg-1`: body text and headings. */
    val fg1: Color,
    /** `--fg-2`: secondary text. */
    val fg2: Color,
    /** `--fg-3`: captions, labels, meta. */
    val fg3: Color,
    val border: Color,
    val borderStrong: Color,
    val borderSubtle: Color,
    /** Links, primary buttons, selection. */
    val brand: Color,
    /** Pressed; text on [brandWash]. */
    val brandInk: Color,
    /** Selected rows, the current item. */
    val brandWash: Color,
    /** Text on a [brand] fill. */
    val onBrand: Color,
    val focus: Color,
    val ok: Color, val okWash: Color, val okBorder: Color,
    val warn: Color, val warnWash: Color, val warnBorder: Color,
    val bad: Color, val badWash: Color, val badBorder: Color,
    val info: Color, val infoWash: Color, val infoBorder: Color,
    val codeBg: Color,
    val codeFg: Color,
    /** The logo's purple petal: #722772, lifted to #A04CA0 in dark. */
    val logoPurple: Color,
    val isDark: Boolean,
)

val LightSmartColors = SmartColors(
    bg = Color(0xFFFFFFFF),
    bgAlt = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF6F8FA),
    fg1 = Color(0xFF1F2933),
    fg2 = Color(0xFF4B5563),
    fg3 = Color(0xFF5B6675),
    border = Color(0xFFE4E7EB),
    borderStrong = Color(0xFFCBD2D9),
    borderSubtle = Color(0xFFEEF1F4),
    brand = Color(0xFF0E6FB8),
    brandInk = Color(0xFF094D80),
    brandWash = Color(0xFFD6EAF7),
    onBrand = Color(0xFFFFFFFF),
    focus = Color(0xFF0E6FB8),
    ok = Color(0xFF12705E), okWash = Color(0xFFE5F5F1), okBorder = Color(0xFF9BD9CB),
    warn = Color(0xFF7A5A16), warnWash = Color(0xFFFCEFD9), warnBorder = Color(0xFFE7C88F),
    bad = Color(0xFFA32A22), badWash = Color(0xFFFBE4E2), badBorder = Color(0xFFF0B6B1),
    info = Color(0xFF094D80), infoWash = Color(0xFFE8F4FB), infoBorder = Color(0xFFA9D6EE),
    codeBg = Color(0xFFF6F8FA),
    codeFg = Color(0xFF1F2933),
    logoPurple = Color(0xFF722772),
    isDark = false,
)

val DarkSmartColors = SmartColors(
    bg = Color(0xFF11161D),
    bgAlt = Color(0xFF0B0F14),
    surface = Color(0xFF1A2129),
    surfaceAlt = Color(0xFF212933),
    fg1 = Color(0xFFE6EAEF),
    fg2 = Color(0xFFB4BDC7),
    fg3 = Color(0xFF9AA6B2),
    border = Color(0xFF2E3844),
    borderStrong = Color(0xFF404C5B),
    borderSubtle = Color(0xFF262F3A),
    brand = Color(0xFF7CBBEA),
    brandInk = Color(0xFFA6D2F2),
    brandWash = Color(0xFF16324A),
    onBrand = Color(0xFF0E1318),
    focus = Color(0xFF7CBBEA),
    ok = Color(0xFF5BCB9F), okWash = Color(0xFF12342A), okBorder = Color(0xFF23624B),
    warn = Color(0xFFE8B04F), warnWash = Color(0xFF3A2A10), warnBorder = Color(0xFF6B4E1C),
    bad = Color(0xFFF28B82), badWash = Color(0xFF3D1C1A), badBorder = Color(0xFF74332E),
    info = Color(0xFF7CBBEA), infoWash = Color(0xFF16324A), infoBorder = Color(0xFF2A5577),
    codeBg = Color(0xFF0B0F14),
    codeFg = Color(0xFFE6EAEF),
    logoPurple = Color(0xFFA04CA0),
    isDark = true,
)

/** The SMART spectrum, the logo's colors in the order of the site's stripe. Fills only, never text. */
val SmartSpectrum = listOf(
    Color(0xFFE5443B), Color(0xFFF09623), Color(0xFF6FB43F),
    Color(0xFF1FA88E), Color(0xFF3DA9DC), Color(0xFF6E4FA2),
)

/** Material 3 roles, mapped from the site's tokens. */
fun SmartColors.toColorScheme(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = brandWash,
        onPrimaryContainer = brandInk,
        inversePrimary = if (isDark) LightSmartColors.brand else DarkSmartColors.brand,
        secondary = brandInk,
        onSecondary = if (isDark) onBrand else Color.White,
        secondaryContainer = brandWash,
        onSecondaryContainer = brandInk,
        tertiary = ok,
        onTertiary = if (isDark) onBrand else Color.White,
        tertiaryContainer = okWash,
        onTertiaryContainer = ok,
        background = bgAlt,
        onBackground = fg1,
        surface = surface,
        onSurface = fg1,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = fg2,
        // No tonal tint: cards stay the site's surface color at any elevation.
        surfaceTint = surface,
        surfaceBright = surface,
        surfaceDim = bgAlt,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceAlt,
        surfaceContainerHighest = surfaceAlt,
        inverseSurface = fg1,
        inverseOnSurface = bg,
        error = bad,
        onError = if (isDark) onBrand else Color.White,
        errorContainer = badWash,
        onErrorContainer = bad,
        outline = borderStrong,
        outlineVariant = border,
        scrim = Color.Black,
    )
}

@OptIn(ExperimentalTextApi::class)
/** Inter, the site's sans (bundled, SIL Open Font License; see assets/licenses/Inter-OFL.txt). */
val InterFamily: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            R.font.inter,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

private fun TextStyle.inter(weight: FontWeight? = fontWeight, tracking: Float = 0f) =
    copy(fontFamily = InterFamily, fontWeight = weight, letterSpacing = tracking.sp)

/** Material's type scale in Inter, with the site's weights: headings 600 to 700, buttons 600, no extra tracking. */
val SmartTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.inter(FontWeight.Bold),
        displayMedium = t.displayMedium.inter(FontWeight.Bold),
        displaySmall = t.displaySmall.inter(FontWeight.Bold),
        headlineLarge = t.headlineLarge.inter(FontWeight.Bold),
        headlineMedium = t.headlineMedium.inter(FontWeight.Bold),
        headlineSmall = t.headlineSmall.inter(FontWeight.SemiBold),
        titleLarge = t.titleLarge.inter(FontWeight.SemiBold),
        titleMedium = t.titleMedium.inter(FontWeight.SemiBold),
        titleSmall = t.titleSmall.inter(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.inter(FontWeight.Normal),
        bodyMedium = t.bodyMedium.inter(FontWeight.Normal),
        bodySmall = t.bodySmall.inter(FontWeight.Normal),
        labelLarge = t.labelLarge.inter(FontWeight.SemiBold),
        labelMedium = t.labelMedium.inter(FontWeight.SemiBold),
        labelSmall = t.labelSmall.inter(FontWeight.SemiBold, tracking = 0.2f),
    )
}

/** The site's radii: 4 (sm), 8 (md: buttons, fields), 12 (lg: cards), 16 (xl: sheets). */
object SmartRadius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
}

val SmartShapes = Shapes(
    extraSmall = RoundedCornerShape(SmartRadius.sm),
    small = RoundedCornerShape(SmartRadius.md),
    medium = RoundedCornerShape(SmartRadius.lg),
    large = RoundedCornerShape(SmartRadius.xl),
    extraLarge = RoundedCornerShape(SmartRadius.xl),
)

private val LocalSmartColors = staticCompositionLocalOf { LightSmartColors }

/** Access to the site's tokens beyond what Material's color scheme has (status colors, fg-3, code). */
object SmartTheme {
    val colors: SmartColors
        @Composable @ReadOnlyComposable get() = LocalSmartColors.current
}

/**
 * The SMART Health Check-in theme. Follows the system's dark setting unless
 * [darkTheme] says otherwise.
 */
@Composable
fun SmartTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkSmartColors else LightSmartColors
    CompositionLocalProvider(LocalSmartColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = SmartTypography,
            shapes = SmartShapes,
            content = content,
        )
    }
}

/**
 * Draws the app edge to edge with transparent status and navigation bars whose
 * icons are dark in light mode and light in dark mode, following the system
 * setting. Call before `setContent`; the screens pad themselves with the insets.
 */
fun ComponentActivity.enableSmartEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
    )
}
