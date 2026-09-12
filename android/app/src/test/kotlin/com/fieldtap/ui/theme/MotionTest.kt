package com.fieldtap.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion contract: FieldTap's own [Motion] specs are physical springs (and a short tween for effects)
 * by default, and collapse to [snap] for every spec when the user has removed animations. (Material 3
 * 1.4.0's `MotionScheme` is `internal`, so bare Material components cannot be wired to a custom scheme and
 * keep Material's own default motion; only FieldTap's own components, driven by [Motion], are under test.)
 */
class MotionTest {
    @Test
    fun normalMotionUsesPhysicalSpecs() {
        assertTrue(Motion.spatial<Float>(reduced = false) is SpringSpec<*>)
        assertTrue(Motion.container<Float>(reduced = false) is SpringSpec<*>)
        assertTrue(Motion.entry<Float>(reduced = false) is SpringSpec<*>)
        assertTrue(Motion.effect<Float>(reduced = false) is TweenSpec<*>)
    }

    @Test
    fun reducedMotionSnapsEverySpec() {
        assertTrue(Motion.spatial<Float>(reduced = true) is SnapSpec<*>)
        assertTrue(Motion.container<Float>(reduced = true) is SnapSpec<*>)
        assertTrue(Motion.entry<Float>(reduced = true) is SnapSpec<*>)
        assertTrue(Motion.effect<Float>(reduced = true) is SnapSpec<*>)
    }

    @Test
    fun standardEasingIsDefined() {
        assertNotNull(Motion.Standard)
    }
}
