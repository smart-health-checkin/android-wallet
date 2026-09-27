package org.smarthealthit.checkin.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The SMART starburst. The purple petal follows the system's dark setting through resources. */
@Composable
fun SmartLogo(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Image(
        painter = painterResource(R.drawable.smart_logo),
        contentDescription = null,
        modifier = modifier.size(width = height * 91f / 75f, height = height),
    )
}

/** The site's spectrum stripe: six equal bands, 3dp high. */
@Composable
fun SpectrumStripe(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(3.dp)) {
        SmartSpectrum.forEach { Box(Modifier.weight(1f).height(3.dp).background(it)) }
    }
}

/**
 * The top bar, as on the site: the spectrum stripe, then the logo and the
 * app's name on the surface color, with a line below. It fills the status bar
 * area and pads itself below it.
 */
@Composable
fun SmartTopBar(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = SmartTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        SpectrumStripe()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartLogo(height = 26.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.fg1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** A card, like the site's `.smart-panel`: surface, 1dp border, 12dp corners, no shadow. */
@Composable
fun SmartCard(
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    border: Color? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SmartTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (quiet) colors.surfaceAlt else colors.surface,
        border = BorderStroke(1.dp, border ?: colors.border),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** A section heading inside a card or page. */
@Composable
fun SmartHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = SmartTheme.colors.fg1,
    )
}

enum class StatusTone { Ok, Warn, Bad, Info, Neutral }

/** A short status label, like the site's `.smart-pill`: "Completed", "Declined". */
@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val c = SmartTheme.colors
    val (fg, bg, line) = when (tone) {
        StatusTone.Ok -> Triple(c.ok, c.okWash, c.okBorder)
        StatusTone.Warn -> Triple(c.warn, c.warnWash, c.warnBorder)
        StatusTone.Bad -> Triple(c.bad, c.badWash, c.badBorder)
        StatusTone.Info -> Triple(c.info, c.infoWash, c.infoBorder)
        StatusTone.Neutral -> Triple(c.fg2, c.surfaceAlt, c.border)
    }
    // 12dp corners: a pill on one line, a rounded box if a long label wraps.
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = text,
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(BorderStroke(1.dp, line), shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
    )
}

/** The main action: a brand fill, 8dp corners, at least 48dp high. */
@Composable
fun SmartPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

/** A secondary action: an outlined button on the surface, 8dp corners, at least 48dp high. */
@Composable
fun SmartSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = SmartTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, if (enabled) c.borderStrong else c.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.fg1),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

/** A quiet text action, like the site's link buttons ("Show", "Hide"). Its touch target is at least 48dp. */
@Composable
fun SmartTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(contentColor = SmartTheme.colors.brand),
        content = content,
    )
}

/**
 * A code block, like the site's `pre.smart-code`: monospace on the code color,
 * scrolling sideways, and scrolling down inside [maxHeight].
 */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxHeight: Dp = 360.dp) {
    val c = SmartTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(MaterialTheme.shapes.small)
            .background(c.codeBg)
            .border(BorderStroke(1.dp, c.border), MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
            color = c.codeFg,
            softWrap = false,
        )
    }
}
