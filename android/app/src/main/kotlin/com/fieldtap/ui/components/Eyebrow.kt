package com.fieldtap.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.SectionLabel
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * The Momentum **mini-label** above a metric or a section ("Serving cell · NR SA", "Last 5 min",
 * "Median NR RSRP"). It is rendered **uppercase and letter-spaced** in `onSurfaceVariant` (see
 * [SectionLabel]) — the small-caps overline of the mockup — so callers pass ordinary sentence-case strings
 * and the component does the uppercasing; TalkBack still hears the natural-case text.
 *
 * Pass [heading] `= true` when it leads a section, so it becomes a TalkBack heading users can jump
 * between; leave it false when it labels a value inline (the hosting tile/row already reads the whole
 * phrase). [tone] colours the label for a section that carries a state; the default is `onSurfaceVariant`.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    heading: Boolean = false,
    tone: StatusTone? = null,
) {
    val color = tone?.let { FieldTapDesign.colors.status(it).color } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        // The Momentum mini-label is uppercase + tracked (see SectionLabel); TalkBack still hears natural case.
        text = text.uppercase(),
        style = SectionLabel,
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
 * The section-label type used as an **identity tag**: a subtle neutral pill with a [ShapeRoles.Pill]
 * outline for a RAT, band, PLMN or PCI ("NR N78", "LTE B3", "PLMN 311480"). Normal tracking; the [text] is
 * shown as-is, so inherently-acronym identifiers stay uppercase because that is how they are written, not
 * as a stylistic shout. Decorative outline; the hosting row's semantics reads the value, so this tag
 * clears its own (its text is repeated there).
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
    val border = family?.color ?: MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = modifier,
        shape = ShapeRoles.Pill,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = content,
        border = BorderStroke(Sizes.HairlineWidth, border),
    ) {
        Text(
            text = text,
            style = SectionLabel,
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
        Eyebrow(text = "Serving cell · NR SA", heading = true)
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
