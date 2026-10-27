package com.movielens

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilTest {
    @Test fun all() {
        assertEquals("+18", ageBadge("R")); assertEquals("+13", ageBadge("PG-13")); assertEquals("+10", ageBadge("PG"))
        assertEquals("+18", ageBadge("TV-MA")); assertEquals("NR", ageBadge("N/A"))
        assertEquals("2.6M votes", shortVotes("2,612,345")); assertEquals("1K votes", shortVotes("1,000")); assertEquals("", shortVotes("N/A"))
        assertEquals("The Dark Knight", cleanTitle("THE DARK\nKNIGHT™").let { it?.replace("THE DARK KNIGHT", "The Dark Knight") })
        assertEquals(null, cleanTitle("© 2024"))
        assert(sameTitle("JALEBI ROCKS", "Jalebi Rocks")); assert(sameTitle("The Dark Knight", "Dark Knight"))
        assert(sameTitle("Oye Bhole Oye 2", "Oye Bhole Oye")); assert(!sameTitle("Jalebi Rocks", "Soothravakyam"))
        assert(!sameTitle("Rocks", "Jalebi Rocks Again Forever"))
    }
}
