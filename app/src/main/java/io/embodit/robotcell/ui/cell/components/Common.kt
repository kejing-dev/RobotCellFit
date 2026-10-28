package io.embodit.robotcell.ui.cell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.robotcell.domain.model.UnitPref
import io.embodit.robotcell.ui.cell.debug.probe
import io.embodit.robotcell.ui.theme.CellColors
import java.util.Locale
import kotlin.math.roundToInt

/** Panel padding and the corner radius of the glass (design: Material.Regular root, 32 dp corners). */
internal val PanelPadding = 16.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp

/** Glass root of a panel: clipped shape, Material.Regular, padding. */
@Composable
internal fun Glass(
    width: Dp,
    height: Dp,
    corner: Dp = 32.dp,
    material: Material = Material.Regular,
    padding: Dp = PanelPadding,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
    }
}

/** Type roles of the design that differ from the stock PicoTheme roles (design `typography.roles`). */
internal object CellType {
    /** Name badge, chip values: titleMedium 16 / 20 at 600, tabular figures. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Chip labels, tile names, plates: labelLarge 14 / 18 at 600, tabular figures. */
    val figure: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** The joint angle: headlineSmall 24 / 30 at 600, tabular figures. */
    val angle: TextStyle
        @Composable
        get() = PicoTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Section titles inside a tab (v0.1.3 tier 1 of 3 within a tab): titleMedium 16 at 600, labelPrimary. */
    val section: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Captions, metrics, hints under controls (tier 3): bodySmall at 500 (drawn in labelSecondary). */
    val caption: TextStyle
        @Composable get() = PicoTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")

    /** Hints, dialog body, snackbar: bodyLarge 16 / 24 at 500. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")
}

/** A length in the chosen unit, rounded to 1 cm / 1 in (design `rules.units`). */
internal fun fmtLen(metres: Float, units: UnitPref): String =
    when (units) {
        UnitPref.CM -> "${(metres * 100f).roundToInt()} cm"
        UnitPref.IN -> "${(metres / 0.0254f).roundToInt()} in"
    }

internal fun fmtDeg(deg: Float): String = String.format(Locale.US, "%d°", deg.roundToInt())

/** A 56 x 48 icon key (more, undo, delete, rotate, plus, minus). */
@Composable
internal fun Key(name: String, iconRes: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillPrimary, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
        size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/**
 * A worded button: capsule, 48 dp high. [primary] = white fill with ink text (the confirming action),
 * [danger] = error fill with ink text; otherwise the tertiary fill with glass text.
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 104.dp,
    height: Dp = 48.dp,
    iconRes: Int? = null,
) {
    val container =
        when {
            danger -> PicoTheme.colorScheme.error
            primary -> PicoTheme.colorScheme.labelPrimaryLight
            else -> PicoTheme.colorScheme.fillTertiary
        }
    // disabled: SpatialUI dims the whole button itself; full-strength content keeps it legible as "off" (D13, v0.1.3)
    val content = if (primary || danger) CellColors.StripeInk else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CellType.title)
    }
}

/** Title row of a panel: an optional leading key, the title, an optional trailing key. */
@Composable
internal fun TitleRow(title: String, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) leading()
        Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = CellType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}
