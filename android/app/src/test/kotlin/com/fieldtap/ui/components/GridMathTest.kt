package com.fieldtap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GridMathTest {
    @Test
    fun twoColumnsOnAPhone() {
        // 360 dp phone, 16 dp gutters: 328 px at density 1, cells of 148 with a 12 gap.
        assertEquals(2, GridMath.columns(availablePx = 328, minCellPx = 148, gapPx = 12, maxColumns = 3, itemCount = 4))
        assertEquals(158, GridMath.cellWidth(availablePx = 328, columns = 2, gapPx = 12))
    }

    @Test
    fun largeFontScaleDropsToOneColumn() {
        // Font scale 1.3 widens the minimum cell to 192.
        assertEquals(1, GridMath.columns(availablePx = 328, minCellPx = 192, gapPx = 12, maxColumns = 3, itemCount = 4))
    }

    @Test
    fun landscapeIsCappedByMaxColumnsAndItemCount() {
        assertEquals(3, GridMath.columns(availablePx = 700, minCellPx = 148, gapPx = 12, maxColumns = 3, itemCount = 6))
        assertEquals(2, GridMath.columns(availablePx = 700, minCellPx = 148, gapPx = 12, maxColumns = 3, itemCount = 2))
    }

    @Test
    fun neverFewerThanOneColumnOrANegativeWidth() {
        assertEquals(1, GridMath.columns(availablePx = 100, minCellPx = 148, gapPx = 12, maxColumns = 3, itemCount = 4))
        assertEquals(1, GridMath.columns(availablePx = 500, minCellPx = 148, gapPx = 12, maxColumns = 0, itemCount = 4))
        assertEquals(1, GridMath.columns(availablePx = 500, minCellPx = 148, gapPx = 12, maxColumns = 3, itemCount = 0))
        assertEquals(0, GridMath.cellWidth(availablePx = 10, columns = 3, gapPx = 12))
    }
}
