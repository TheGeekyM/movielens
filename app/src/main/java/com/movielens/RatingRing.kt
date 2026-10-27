package com.movielens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Circular IMDb score: the arc fills and the number counts up; spins while loading. */
class RatingRing(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private var value = 0f          // 0–10; 0 = unknown
    private var loading = false
    private var since = 0L
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0x26FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val r = RectF()

    fun setLoading() { loading = true; since = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }

    fun setValue(v: Float) {
        if (!loading && v == value) return
        loading = false; value = v; since = SystemClock.uptimeMillis(); postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val pad = arc.strokeWidth / 2 + d
        r.set(pad, pad, width - pad, height - pad)
        c.drawOval(r, track)
        arc.color = when { value >= 7.5f -> 0xFF2ECC71.toInt(); value >= 6f -> 0xFFF5C518.toInt(); value > 0f -> 0xFFE5484D.toInt(); else -> 0xFF8A8A8E.toInt() }
        if (loading) {
            arc.color = 0xFFF5C518.toInt()
            c.drawArc(r, (now - since) * 0.36f % 360f, 80f, false, arc)
            postInvalidateOnAnimation(); return
        }
        // Ease-out fill + count-up over 800ms.
        val t = ((now - since) / 800f).coerceIn(0f, 1f)
        val e = 1 - (1 - t) * (1 - t) * (1 - t)
        if (value > 0f) c.drawArc(r, -90f, 360f * value / 10f * e, false, arc)
        num.textSize = height * 0.3f
        c.drawText(if (value > 0f) "%.1f".format(value * e) else "–", width / 2f, height / 2f + num.textSize / 3, num)
        if (t < 1f) postInvalidateOnAnimation()
    }
}
