package com.movielens

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilTest {
    @Test fun all() {
        assertEquals("+18", ageBadge("R")); assertEquals("+13", ageBadge("PG-13")); assertEquals("+10", ageBadge("PG"))
        assertEquals("+18", ageBadge("TV-MA")); assertEquals("NR", ageBadge("N/A")); assertEquals("+18", ageBadge("A"))
        assertEquals("2.6M votes", shortVotes(2_612_345)); assertEquals("1K votes", shortVotes(1000)); assertEquals("", shortVotes(0))

        // 1000x2000 view, aim at centre (500,1000)
        val tile = Box(300f, 850f, 700f, 1100f)       // poster-shaped tile under the aim point
        val row = Box(0f, 850f, 1000f, 1100f)         // whole shelf row: too wide
        val tv = Box(0f, 0f, 1000f, 2000f)            // whole TV: too big
        val other = Box(720f, 850f, 1000f, 1100f)     // neighbour, not under aim
        assertEquals(tile, posterAt(listOf(row, tv, other, tile), 500f, 1000f, 1000f, 2000f))
        assertEquals(null, posterAt(listOf(row, tv), 500f, 1000f, 1000f, 2000f))

        val c = cropFor(tile, 1000f, 2000f)
        assert(c.t < tile.t && c.b > tile.b)          // room for a title above/below
        assertEquals(0f, cropFor(Box(0f, 0f, 100f, 100f), 1000f, 2000f).t)  // clamped
    }
}
