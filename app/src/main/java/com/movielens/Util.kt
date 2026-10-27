package com.movielens

import com.google.mlkit.vision.text.Text

/** US (OMDb "Rated") certificate -> age badge. */
fun ageBadge(rated: String?): String = when (rated?.uppercase()) {
    "G", "TV-G", "TV-Y", "APPROVED", "PASSED" -> "ALL"
    "TV-Y7", "TV-Y7-FV" -> "+7"
    "PG", "TV-PG" -> "+10"
    "PG-13" -> "+13"
    "TV-14" -> "+14"
    "R", "NC-17", "TV-MA", "X" -> "+18"
    null, "", "N/A", "NOT RATED", "UNRATED" -> "NR"
    else -> rated
}

/** "2,612,345" -> "2.6M votes" */
fun shortVotes(v: String?): String {
    val n = v?.replace(",", "")?.toLongOrNull() ?: return ""
    val s = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }
    return s.replace(".0", "") + " votes"
}

/** Keep title-ish characters, drop noise; null if not a plausible title. */
fun cleanTitle(raw: String): String? {
    val t = raw.replace('\n', ' ').replace(Regex("[^\\p{L}\\p{N} :'&!?.,-]"), "")
        .replace(Regex("\\s+"), " ").trim()
    return t.takeIf { it.length in 2..60 && it.any(Char::isLetter) }
}

// ponytail: big-text-near-centre heuristic, posters put the title in the largest font and the user aims at the poster they mean.
fun titleCandidates(text: Text, w: Int, h: Int): List<String> {
    val cx = w / 2f; val cy = h / 2f; val maxD = Math.hypot(cx.toDouble(), cy.toDouble())
    return text.textBlocks.sortedByDescending { b ->
        val box = b.boundingBox ?: return@sortedByDescending 0.0
        val d = Math.hypot((box.exactCenterX() - cx).toDouble(), (box.exactCenterY() - cy).toDouble())
        b.lines.maxOf { it.boundingBox?.height() ?: 0 } * (1 - d / maxD).let { it * it }
    }.mapNotNull { cleanTitle(it.text) }.distinct().take(3)
}

private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it != "the" }.toSet()

/** Did OMDb return the title we actually read? Guards against its fuzzy search returning a different movie. */
fun sameTitle(query: String, title: String): Boolean {
    val q = words(query); val t = words(title)
    if (q.isEmpty() || t.isEmpty()) return false
    return q.containsAll(t) || (q intersect t).size * 2 >= (q union t).size
}
