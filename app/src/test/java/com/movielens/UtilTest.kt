package com.movielens

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilTest {
    @Test fun all() {
        assertEquals("+18", ageBadge("R")); assertEquals("+13", ageBadge("PG-13")); assertEquals("+10", ageBadge("PG"))
        assertEquals("+18", ageBadge("TV-MA")); assertEquals("NR", ageBadge("N/A")); assertEquals("+18", ageBadge("A"))
        assertEquals("2.6M votes", shortVotes(2_612_345)); assertEquals("1K votes", shortVotes(1000)); assertEquals("", shortVotes(0))

        assertEquals(Box(250f, 400f, 500f, 1000f), toView(500, 500, 250, 200, 1000f, 2000f))  // swapped corners normalised

        // 1000x2000 view: centre (500,1000)
        val left = Box(50f, 900f, 450f, 1100f); val mid = Box(470f, 900f, 870f, 1100f); val far = Box(0f, 0f, 300f, 200f)
        assertEquals(1, focusIndex(listOf(left, mid, far), 500f, 1000f))   // under the centre
        assertEquals(0, focusIndex(listOf(left, far), 500f, 1000f))         // nearest when none is under it
        assertEquals(-1, focusIndex(emptyList(), 500f, 1000f))

        val none = emptySet<String>()
        assertEquals(false, dimmed(8f, listOf("Drama"), "R", 0f, none, none, false))
        assertEquals(true, dimmed(8f, listOf("Drama"), "R", 0f, none, none, true))            // parental hides +18
        assertEquals(true, dimmed(5.5f, listOf("Drama"), "PG", 6f, none, none, false))         // below min rating
        assertEquals(false, dimmed(0f, listOf("Drama"), "PG", 6f, none, none, false))          // unknown rating never dims
        assertEquals(true, dimmed(8f, listOf("Horror"), "PG", 0f, none, genreSet("horror"), false))
        assertEquals(true, dimmed(8f, listOf("Horror"), "PG", 0f, genreSet("Comedy, drama"), none, false))
        assertEquals(false, dimmed(8f, listOf("Drama"), "PG", 0f, genreSet("Comedy, drama"), none, false))

        assertEquals(2, bestIndex(listOf(7.1f, null, 8.4f, 6f)))
        assertEquals(-1, bestIndex(listOf(9f, null)))                                           // one rating isn't a contest
    }
}
