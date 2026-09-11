package com.fieldtap.e2e

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldtap.MainActivity
import com.fieldtap.ui.FieldTapTheme
import com.fieldtap.ui.live.RecordingState
import com.fieldtap.ui.live.RecordingStatusStrip
import com.fieldtap.ui.live.RecordingStrip
import com.fieldtap.ui.live.StripGps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The recording strip pinned under Live's top bar stays one whole line in every state and GPS state, at font scale 1.3 on
 * a 360 dp wide phone: no state wraps, and none is cut short with an ellipsis. It once read "Location off: not recording ·
 * 6 fresh / samples" over two lines. The strip is drawn alone, in this app's theme, over MainActivity's content.
 */
@RunWith(AndroidJUnit4::class)
class RecordingStripTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(FailureCapture(GROUP))

    @Test
    fun everyStateIsOneWholeLineAtFontScale130On360Dp() {
        E2e.upright()
        val checked = mutableListOf<String>()
        for (state in RecordingState.entries) {
            for (gps in StripGps.entries) {
                val strip = RecordingStrip(state = state, freshSamples = MANY_SAMPLES, gps = gps)
                compose.runOnUiThread {
                    compose.activity.setContent {
                        DeviceConfigurationOverride(
                            DeviceConfigurationOverride.FontScale(FONT_SCALE) then DeviceConfigurationOverride.ForcedSize(DpSize(WIDTH, HEIGHT)),
                        ) {
                            FieldTapTheme(darkTheme = false) {
                                Box { RecordingStatusStrip(strip) }
                            }
                        }
                    }
                }
                compose.waitForIdle()
                val layouts = textLayouts()
                assertTrue("the strip shows no text for $state with GPS $gps", layouts.isNotEmpty())
                for ((text, layout) in layouts) {
                    assertEquals("\"$text\" ($state, GPS $gps) wraps", 1, layout.lineCount)
                    assertFalse("\"$text\" ($state, GPS $gps) is cut short", layout.isLineEllipsized(0))
                }
                checked += "$state/$gps: " + layouts.joinToString(" | ") { it.first }
            }
        }
        E2e.writeResult("recording-strip-result.json", linkedMapOf("font_scale" to FONT_SCALE.toString(), "checked" to checked.joinToString("; ")))
    }

    /** Every text on screen with how it was laid out: the strip is the only content. */
    private fun textLayouts(): List<Pair<String, TextLayoutResult>> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                results.firstOrNull()?.let { it.layoutInput.text.text to it }
            }

    private companion object {
        const val GROUP = "recording-strip"
        const val FONT_SCALE = 1.3f
        val WIDTH = 360.dp
        val HEIGHT = 640.dp

        /** "Recording · 12,345 samples": five digits and a grouping separator, an hour and a half at 2 s. */
        const val MANY_SAMPLES = 12_345L
    }
}
