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

/** A rectangle in PreviewView coordinates. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun has(x: Float, y: Float) = x in l..r && y in t..b
}

// ponytail: "smallest poster-shaped object under the aim point". Busy screens can still fool the detector; the fixed frame is the fallback.
fun posterAt(objects: List<Box>, x: Float, y: Float, viewW: Float, viewH: Float): Box? =
    objects.filter {
        val area = it.w * it.h / (viewW * viewH)
        it.has(x, y) && it.w / it.h in 0.45f..2.4f && area in 0.02f..0.35f
    }.minByOrNull { it.w * it.h }

/** Default viewfinder frame around the aim point when no poster is detected. */
fun defaultFrame(viewW: Float, viewH: Float): Box {
    val w = viewW * 0.62f; val h = w * 0.62f
    return Box((viewW - w) / 2, (viewH - h) / 2, (viewW + w) / 2, (viewH + h) / 2)
}

/** What we send to the AI: the poster plus room above/below for a caption title, clamped to the view. */
fun cropFor(p: Box, viewW: Float, viewH: Float): Box =
    Box(maxOf(0f, p.l - p.w * 0.08f), maxOf(0f, p.t - p.h * 0.35f), minOf(viewW, p.r + p.w * 0.08f), minOf(viewH, p.b + p.h * 0.35f))
