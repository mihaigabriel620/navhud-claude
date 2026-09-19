package com.mihai.navhud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A compass rose with the car on it, for the calibration screen.
 *
 * Two things are drawn, and the whole point is that you can see them disagree:
 * the **rose** turns so that north stays north, and the **car** sits at the
 * heading the app currently believes. Park facing down a road you can see, look
 * at where the car is pointing, and if it is wrong you can say by how much.
 */
class CompassView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val amber = Color.parseColor("#FF9D00")

    /** Where the app thinks the car is pointing, degrees. NaN when unknown. */
    var carHeading: Double = Double.NaN
        set(v) { field = v; invalidate() }

    /** The raw compass reading, for the second, dimmer needle. NaN to hide. */
    var rawHeading: Double = Double.NaN
        set(v) { field = v; invalidate() }

    /**
     * Which sectors of the circle the phone has been turned through, for the
     * calibration screen. The ring fills as the driver spins it, so "turn it
     * all the way round" has an end you can see rather than being a guess.
     */
    var covered: BooleanArray? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - 6f
        if (r <= 0f) return

        // The dial face.
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FF11151C")
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.parseColor("#33FFFFFF")
        canvas.drawCircle(cx, cy, r, p)

        // The coverage ring, drawn outside the ticks.
        covered?.let { c ->
            val sweep = 360f / c.size
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            for (i in c.indices) {
                p.color = if (c[i]) amber else Color.parseColor("#22FFFFFF")
                canvas.drawArc(
                    cx - r + 4f, cy - r + 4f, cx + r - 4f, cy + r - 4f,
                    i * sweep - 90f + 1f, sweep - 2f, false, p
                )
            }
        }

        // Ticks every 15 degrees, longer every 90. The rose does not rotate:
        // north is up, which is how you read a bearing off it.
        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians(deg - 90.0)
            val major = deg % 90 == 0
            val inner = if (major) r - 22f else r - 12f
            p.strokeWidth = if (major) 3f else 1.5f
            p.color = if (major) Color.parseColor("#8AFFFFFF") else Color.parseColor("#33FFFFFF")
            canvas.drawLine(
                cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                cx + ((r - 4f) * cos(a)).toFloat(), cy + ((r - 4f) * sin(a)).toFloat(), p
            )
        }

        p.style = Paint.Style.FILL
        t.textSize = r * 0.16f
        val labels = listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270)
        for ((name, deg) in labels) {
            val a = Math.toRadians(deg - 90.0)
            val rr = r - 40f
            t.color = if (deg == 0) Color.parseColor("#FF6A5A") else Color.parseColor("#B6BDC7")
            canvas.drawText(name,
                cx + (rr * cos(a)).toFloat(),
                cy + (rr * sin(a)).toFloat() + t.textSize * 0.36f, t)
        }

        // The raw compass reading, thin and grey: what the phone says before
        // the mounting offset is applied. Useful when the two are far apart,
        // because that difference *is* the offset.
        if (!rawHeading.isNaN()) {
            p.color = Color.parseColor("#55FFFFFF")
            p.strokeWidth = 3f
            p.style = Paint.Style.STROKE
            val a = Math.toRadians(rawHeading - 90.0)
            canvas.drawLine(cx, cy,
                cx + ((r - 18f) * cos(a)).toFloat(),
                cy + ((r - 18f) * sin(a)).toFloat(), p)
            p.style = Paint.Style.FILL
        }

        // The car.
        if (carHeading.isNaN()) {
            p.color = Color.parseColor("#4D6A727C")
            canvas.drawCircle(cx, cy, r * 0.13f, p)
            t.textSize = r * 0.13f
            t.color = Color.parseColor("#8A8F97")
            canvas.drawText("?", cx, cy + t.textSize * 0.36f, t)
            return
        }

        canvas.save()
        canvas.rotate(carHeading.toFloat(), cx, cy)
        val len = r * 0.60f
        val halfW = r * 0.22f
        val path = Path().apply {
            moveTo(cx, cy - len)
            lineTo(cx + halfW, cy + len * 0.55f)
            lineTo(cx, cy + len * 0.18f)
            lineTo(cx - halfW, cy + len * 0.55f)
            close()
        }
        p.color = Color.parseColor("#F2F4F7")
        canvas.drawPath(path, p)
        p.color = amber
        val inner = Path().apply {
            moveTo(cx, cy - len * 0.82f)
            lineTo(cx + halfW * 0.72f, cy + len * 0.42f)
            lineTo(cx, cy + len * 0.12f)
            lineTo(cx - halfW * 0.72f, cy + len * 0.42f)
            close()
        }
        canvas.drawPath(inner, p)
        canvas.restore()
    }
}
