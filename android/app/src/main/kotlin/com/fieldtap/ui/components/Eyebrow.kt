package com.fieldtap.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.ui.theme.EyebrowCase
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import com.fieldtap.ui.theme.Eyebrow as EyebrowStyle

/**
 * The editorial signature: a small, UPPERCASE, letter-spaced overline above a metric or a section
 * ("SERVING · NR SA", "LAST 5 MIN", "MEDIAN LTE RSRP"). It replaces heavy title rows — calmer, and it
 * saves vertical space for the number.
 *
 * The text is uppercased for display (via [EyebrowCase], which keeps unit tokens like "dBm" in their fixed
 * casing), but TalkBack reads the original-case [text] (never spelled out). Pass [heading] `= true` when it
 * leads a section, so it becomes a TalkBack heading users can jump between; leave it false when it labels a
 * value inline (the hosting tile/row already reads the whole phrase). [tone] colours the overline for a
 * section that carries a state; the default is `onSurfaceVariant`.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    heading: Boolean = false,
    tone: StatusTone? = null,
) {
    val color = tone?.let { FieldTapDesign.colors.status(it).color } ?: MaterialTheme.colorScheme.onSurfaceVariant
    // Read the locale observably (LocalLocale) so an eyebrow re-uppercases if the user changes their locale.
    val locale = LocalLocale.current.platformLocale
    Text(
        text = EyebrowCase.uppercase(text, locale),
        style = EyebrowStyle,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics {
            contentDescription = text
            if (heading) heading()
        },
    )
}

/**
 * The eyebrow type used as an **identity tag**: a tracked-caps chip with a [ShapeRoles.Pill] outline for
 * a RAT, band, PLMN or PCI ("NR N78", "LTE B3", "PLMN 311480"). Decorative outline; the hosting row's
 * semantics reads the value, so this tag clears its own (its text is repeated there).
 *
 * @param tone colours the outline and text for a tag that carries a state; the default is neutral
 *   (`outline` border, `onSurfaceVariant` text).
 */
@Composable
fun EyebrowTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone? = null,
) {
    val family = tone?.let { FieldTapDesign.colors.status(it) }
    val content = family?.color ?: MaterialTheme.colorScheme.onSurfaceVariant
    val border = family?.color ?: MaterialTheme.colorScheme.outline
    val locale = LocalLocale.current.platformLocale
    Surface(
        modifier = modifier,
        shape = ShapeRoles.Pill,
        color = Color.Transparent,
        contentColor = content,
        border = BorderStroke(Sizes.HairlineWidth, border),
    ) {
        Text(
            text = EyebrowCase.uppercase(text, locale),
            style = EyebrowStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.Sm, vertical = Spacing.Xxs),
        )
    }
}

@FieldTapPreviews
@Composable
private fun EyebrowPreview() {
    PreviewSurface {
        Eyebrow(text = "Serving · NR SA", heading = true)
        Eyebrow(text = "Last 5 min")
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.Sm),
        ) {
            EyebrowTag(text = "NR N78")
            EyebrowTag(text = "PLMN 311480")
            EyebrowTag(text = "Gaps", tone = StatusTone.ERROR)
        }
    }
}
