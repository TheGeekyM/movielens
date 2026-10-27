package com.movielens

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Camera overlay: a springy rating chip on every poster, Lens-style corner brackets that glide to the focused one,
 * and twinkling dots while scanning.
 */
class Tags(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    class Tag(val box: Box) {
        var ratedAt = 0L
        var rating: String? = null  // null = still loading
            set(v) { field = v; ratedAt = SystemClock.uptimeMillis() }
        var dim = false             // filtered out by taste / parental settings
        var adult = false           // show an 18+ badge
    }

    private val d = resources.displayMetrics.density
    var tags: List<Tag> = emptyList()
        set(v) { field = v; born = SystemClock.uptimeMillis(); if (v.isEmpty()) { target = null }; kick() }
    var selected = -1
        set(v) {
            field = v
            target = tags.getOrNull(v)?.box?.let { RectF(it.l, it.t, it.r, it.b) }
            target?.let { t -> if (cur == null) cur = RectF(t).apply { inset(-36 * d, -36 * d) } }  // zoom-in entrance
            kick()
        }
    var best = -1                   // gets the crown
        set(v) { field = v; kick() }
    var scanning = false
        set(v) { field = v; if (v) dots = List(28) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 6f) }; kick() }

    private var born = 0L
    private var target: RectF? = null
    private var cur: RectF? = null
    private var dim = 0f
    private var dots: List<FloatArray> = emptyList()

    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(6 * d, 0f, 2 * d, 0x66000000) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val shimmer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
        setShadowLayer(8 * d, 0f, 0f, 0x88000000.toInt())
    }
    private val scrim = Paint().apply { color = 0xFF000000.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val crown = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22 * d; textAlign = Paint.Align.CENTER }
    private val fade = Paint().apply { color = 0xB3000000.toInt() }
    private val r = RectF()
    private val path = Path()

    private fun kick() = postInvalidateOnAnimation()

    /** Index of the smallest box under (x, y), or -1. */
    fun at(x: Float, y: Float) = tags.indices.filter { tags[it].box.has(x, y) }.minByOrNull { tags[it].box.w * tags[it].box.h } ?: -1

    /** 0→1 with a little overshoot, like a spring. */
    private fun spring(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val s = 1.7f; val x = t - 1
        return x * x * ((s + 1) * x + s) + 1
    }

    private fun lerp(a: Float, b: Float, k: Float = 0.18f) = a + (b - a) * k

    private fun colorFor(rating: String) = rating.toFloatOrNull().let {
        when {
            it == null -> 0xFF8A8A8E.toInt()
            it >= 7.5f -> 0xFF2ECC71.toInt()
            it >= 6f -> 0xFFF5C518.toInt()
            else -> 0xFFE5484D.toInt()
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = scanning

        // Twinkling dots while the AI looks at the screen.
        if (scanning) dots.forEach { (x, y, p) ->
            val a = (sin(now / 260.0 + p) * 0.5 + 0.5).toFloat()
            dot.alpha = (220 * a).toInt()
            c.drawCircle(x * width, y * height, (2 + 3 * a) * d, dot)
        }

        // Dim everything but the focused poster, and glide the brackets to it.
        val t = target
        dim = lerp(dim, if (t != null) 1f else 0f)
        val fr = cur
        if (fr != null) {
            if (t != null) fr.set(lerp(fr.left, t.left), lerp(fr.top, t.top), lerp(fr.right, t.right), lerp(fr.bottom, t.bottom))
            val rad = 14 * d
            path.reset(); path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.addRoundRect(fr, rad, rad, Path.Direction.CW)
            scrim.alpha = (90 * dim).toInt(); c.drawPath(path, scrim)

            val k = minOf(26 * d, fr.width() / 3, fr.height() / 3); val o = 5 * d; val q = rad / 2
            val l = fr.left - o; val tp = fr.top - o; val rt = fr.right + o; val b = fr.bottom + o
            path.reset()
            path.moveTo(l, tp + k); path.lineTo(l, tp + q); path.quadTo(l, tp, l + q, tp); path.lineTo(l + k, tp)
            path.moveTo(rt - k, tp); path.lineTo(rt - q, tp); path.quadTo(rt, tp, rt, tp + q); path.lineTo(rt, tp + k)
            path.moveTo(rt, b - k); path.lineTo(rt, b - q); path.quadTo(rt, b, rt - q, b); path.lineTo(rt - k, b)
            path.moveTo(l + k, b); path.lineTo(l + q, b); path.quadTo(l, b, l, b - q); path.lineTo(l, b - k)
            bracket.alpha = (255 * dim).toInt(); c.drawPath(path, bracket)
            if (t == null && dim < 0.02f) cur = null
            animating = true
        }

        // Rating chips at each poster's top-right corner: staggered spring pop-in, shimmer while loading, re-pop on arrival.
        tags.forEachIndexed { i, tag ->
            val appear = spring((now - born - i * 60) / 380f)
            if (appear == 0f) { animating = true; return@forEachIndexed }
            if (tag.dim) c.drawRoundRect(tag.box.l, tag.box.t, tag.box.r, tag.box.b, 10 * d, 10 * d, fade)
            val pop = if (tag.rating != null) 1f + 0.18f * (1 - spring((now - tag.ratedAt) / 320f).coerceAtMost(1f)) else 1f
            val s = appear * pop
            if (s != 1f || tag.rating == null) animating = true

            val text = (if (tag.adult) "18+  " else "") + (tag.rating?.let { "★ $it" } ?: "★ 0.0")
            val w = label.measureText(text) + 18 * d; val h = 24 * d
            val cx = tag.box.r - w / 2 - 6 * d; val cy = tag.box.t + h / 2 + 6 * d
            r.set(cx - w / 2 * s, cy - h / 2 * s, cx + w / 2 * s, cy + h / 2 * s)
            if (tag.rating == null) {
                val x = r.left + (r.width() * 2) * ((now % 1100) / 1100f) - r.width()
                shimmer.shader = LinearGradient(x, 0f, x + r.width(), 0f, intArrayOf(0xCC2A2A2E.toInt(), 0xCC55555C.toInt(), 0xCC2A2A2E.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, h / 2, h / 2, shimmer)
            } else {
                chip.color = if (tag.adult) 0xFFE5484D.toInt() else colorFor(tag.rating!!)
                chip.alpha = if (tag.dim) 140 else 255
                c.drawRoundRect(r, h / 2 * s, h / 2 * s, chip)
                label.color = 0xFF111114.toInt(); label.textSize = 13 * d * s
                c.drawText(text, cx, cy + label.textSize / 3, label)
            }
            if (i == best) {
                // Bobbing crown over the best-rated poster.
                val bob = (sin(now / 300.0) * 3 * d).toFloat()
                c.drawText("👑", cx, r.top - 4 * d + bob, crown)
                animating = true
            }
        }
        if (animating) kick()
    }
}
