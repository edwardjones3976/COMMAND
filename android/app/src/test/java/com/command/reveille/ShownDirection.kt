package com.command.reveille

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals

/**
 * The direction a screen was really laid out in, read from inside the composition. A test that is meant to be right to left says so with
 * [assertRtl], because the qualifier alone is not proof: `ldrtl` selects resources, but the layout direction of the configuration follows
 * the locale, so a test needs a language that is written right to left as well, and has to check that it got one. Hebrew (`he`) is used and not
 * Arabic, because Arabic writes its numbers in other digits and a test that looks for "10 min" would not find it.
 */
class ShownDirection {
    private var direction: LayoutDirection? = null

    /** Called from inside the composition that is shown. */
    @Composable
    fun RecordDirection() {
        direction = LocalLayoutDirection.current
    }

    fun assertRtl() = assertEquals("the screen was shown right to left", LayoutDirection.Rtl, direction)
}
