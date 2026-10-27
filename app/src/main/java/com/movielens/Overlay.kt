package com.movielens

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Lens-style focus: dims everything but the poster frame, which gets corner brackets, a marching dashed border, and a scan sweep while loading. */
class Overlay(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private var target: RectF? = null
    private var cur: RectF? = null
    var loading = false
        set(v) { field = v; postInvalidateOnAnimation() }
    private val scrim = Paint().apply { color = 0xFF000000.toInt() }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFF5C518.toInt()
        setShadowLayer(10 * d, 0f, 0f, 0xCCF5C518.toInt())
    }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val scan = Paint()
    private val hole = Path()
    private val corners = Path()

    fun select(r: Box) {
        val n = RectF(r.l, r.t, r.r, r.b)
        if (cur == null) cur = RectF(n).apply { inset(-40 * d, -40 * d) }  // zoom-in entrance
        target = n
        postInvalidateOnAnimation()
    }

    /** The frame as currently drawn (what the user sees is what gets sent). */
    fun shown(): Box? = cur?.let { Box(it.left, it.top, it.right, it.bottom) }

    private fun lerp(a: Float, b: Float) = a + (b - a) * 0.2f

    override fun onDraw(c: Canvas) {
        val t = target ?: return
        val r = cur!!
        if (!loading) r.set(lerp(r.left, t.left), lerp(r.top, t.top), lerp(r.right, t.right), lerp(r.bottom, t.bottom))
        val now = SystemClock.uptimeMillis()
        val rad = 16 * d

        hole.reset(); hole.fillType = Path.FillType.EVEN_ODD
        hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        hole.addRoundRect(r, rad, rad, Path.Direction.CW)
        scrim.alpha = if (loading) 150 else 110
        c.drawPath(hole, scrim)

        val phase = (now / (if (loading) 4 else 12) % 1000).toFloat()
        dash.pathEffect = DashPathEffect(floatArrayOf(12 * d, 8 * d), -phase)
        c.drawRoundRect(r, rad, rad, dash)

        if (loading) {
            // Scan line sweeping down the poster.
            val y = r.top + r.height() * ((now % 1400) / 1400f)
            scan.shader = LinearGradient(0f, y - 40 * d, 0f, y, 0x00F5C518, 0x99F5C518.toInt(), Shader.TileMode.CLAMP)
            c.save(); c.clipRect(r); c.drawRect(r.left, y - 40 * d, r.right, y, scan); c.restore()
        }

        val k = minOf(28 * d, r.width() / 3, r.height() / 3); val o = 6 * d; val q = rad / 2
        val l = r.left - o; val tp = r.top - o; val rt = r.right + o; val b = r.bottom + o
        corners.reset()
        corners.moveTo(l, tp + k); corners.lineTo(l, tp + q); corners.quadTo(l, tp, l + q, tp); corners.lineTo(l + k, tp)
        corners.moveTo(rt - k, tp); corners.lineTo(rt - q, tp); corners.quadTo(rt, tp, rt, tp + q); corners.lineTo(rt, tp + k)
        corners.moveTo(rt, b - k); corners.lineTo(rt, b - q); corners.quadTo(rt, b, rt - q, b); corners.lineTo(rt - k, b)
        corners.moveTo(l + k, b); corners.lineTo(l + q, b); corners.quadTo(l, b, l, b - q); corners.lineTo(l, b - k)
        c.drawPath(corners, bracket)
        postInvalidateOnAnimation()
    }
}
