package com.jaikhurana.aiusagewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class BraidTest {
    /** Wherever two strands meet, one is clearly in front, whatever the number of accounts. */
    @Test fun everyCrossingHasAStrandInFront() {
        for (count in 2..6) {
            var t = 0.0
            while (t < 4 * PI) {
                for (i in 0 until count) for (j in i + 1 until count) {
                    if (abs(Braid.offset(i, count, t) - Braid.offset(j, count, t)) < 0.05) {
                        assertTrue("$count strands, $i×$j at $t", abs(Braid.depth(i, count, t) - Braid.depth(j, count, t)) > 0.3)
                    }
                }
                t += 0.01
            }
        }
    }

    /** Two strands take turns in front; four make six pairs, each crossing twice a turn. */
    @Test fun crossingsAreFoundAndTakeTurns() {
        val two = Braid.crossings(2, 0.0, 4 * PI)
        assertEquals(4, two.size)
        for (k in 1 until two.size) assertNotEquals(two[k - 1].front, two[k].front)
        val four = Braid.crossings(4, 0.0, 2 * PI)
        assertEquals(12, four.size)
        four.forEach { c ->
            assertEquals(Braid.offset(c.front, 4, c.t), Braid.offset(c.back, 4, c.t), 1e-4)
            assertTrue(Braid.depth(c.front, 4, c.t) > Braid.depth(c.back, 4, c.t))
        }
    }
}
