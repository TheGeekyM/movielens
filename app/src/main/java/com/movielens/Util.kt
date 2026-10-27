package com.movielens

/** Certificate (US or Indian) -> age badge. */
fun ageBadge(rated: String?): String = when (rated?.uppercase()) {
    "G", "TV-G", "TV-Y", "APPROVED", "PASSED", "U" -> "ALL"
    "TV-Y7", "TV-Y7-FV" -> "+7"
    "PG", "TV-PG" -> "+10"
    "PG-13", "U/A", "UA", "UA 13+" -> "+13"
    "TV-14" -> "+14"
    "R", "NC-17", "TV-MA", "X", "A" -> "+18"
    null, "", "N/A", "NOT RATED", "UNRATED" -> "NR"
    else -> rated
}

/** 2612345 -> "2.6M votes" */
fun shortVotes(n: Long): String {
    if (n <= 0) return ""
    val s = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }
    return s.replace(".0", "") + " votes"
}

/** A rectangle in view coordinates. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun has(x: Float, y: Float) = x in l..r && y in t..b
    fun dist(x: Float, y: Float) = Math.hypot(maxOf(l - x, 0f, x - r).toDouble(), maxOf(t - y, 0f, y - b).toDouble())
}

/** AI box (0–1000 normalised) -> view coordinates. */
fun toView(x0: Int, y0: Int, x1: Int, y1: Int, w: Float, h: Float) =
    Box(minOf(x0, x1) * w / 1000, minOf(y0, y1) * h / 1000, maxOf(x0, x1) * w / 1000, maxOf(y0, y1) * h / 1000)

/** The poster the phone is aimed at: the one under the centre, else the nearest. */
fun focusIndex(boxes: List<Box>, cx: Float, cy: Float): Int =
    boxes.indices.minByOrNull { boxes[it].dist(cx, cy) } ?: -1

/** Should this poster fade out under the user's taste / parental settings? Unknown values never dim. */
fun dimmed(rating: Float, genres: List<String>, age: String, minRating: Float, likes: Set<String>, dislikes: Set<String>, parental: Boolean): Boolean {
    val g = genres.map { it.lowercase() }.toSet()
    return (parental && ageBadge(age) == "+18") ||
        (rating > 0 && rating < minRating) ||
        g.any { it in dislikes } ||
        (likes.isNotEmpty() && g.isNotEmpty() && g.none { it in likes })
}

/** Highest rating among at least two rated posters, else -1. */
fun bestIndex(ratings: List<Float?>): Int =
    if (ratings.count { it != null } < 2) -1 else ratings.indices.filter { ratings[it] != null }.maxBy { ratings[it]!! }

/** "drama, Comedy ," -> {"drama", "comedy"} */
fun genreSet(s: String) = s.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
