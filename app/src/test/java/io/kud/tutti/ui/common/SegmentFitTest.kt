package io.kud.tutti.ui.common

import io.kud.tutti.ui.common.SegmentFit.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentFitTest {
    private val one = Label(longestWordFits = true, lines = 1)
    private val two = Label(longestWordFits = true, lines = 2)

    @Test fun shortLabelsShareOneLine() = assertEquals(1, SegmentFit.lines(listOf(one, one, one, one)))

    @Test fun oneWrappedLabelGivesEverySegmentTwoLines() = assertEquals(2, SegmentFit.lines(listOf(one, one, one, two)))

    @Test fun aWordThatWouldBreakKeepsTheMenu() = assertNull(SegmentFit.lines(listOf(one, Label(longestWordFits = false, lines = 2))))

    @Test fun threeLinesKeepTheMenu() = assertNull(SegmentFit.lines(listOf(one, Label(longestWordFits = true, lines = 3))))

    @Test fun fiveOptionsKeepTheMenu() = assertNull(SegmentFit.lines(List(5) { one }))

    @Test fun oneOptionIsNotAChoice() = assertNull(SegmentFit.lines(listOf(one)))
}
