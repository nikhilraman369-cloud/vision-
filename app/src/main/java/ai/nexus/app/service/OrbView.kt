package ai.nexus.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The animated NEXUS orb. Each state has its own motion so you can tell at a glance what it's doing:
 *  idle: slow breathing glow · listening: green ripples · hearing you: fast pulse ·
 *  thinking: amber arcs spinning · working: dashed ring turning · speaking: blue sound-wave ring ·
 *  waiting for you: orange slow pulse · stuck: red with a shake · teaching: red dashed outer ring.
 */
class OrbView(context: Context) : View(context) {
    enum class Mode { IDLE, LISTENING, HEARING, THINKING, WORKING, SPEAKING, WAITING, STUCK }

    var mode: Mode = Mode.IDLE
        set(v) { if (field != v) { field = v; changedAt = SystemClock.uptimeMillis(); invalidate() } }
    var teaching = false
        set(v) { field = v; invalidate() }
    var label = "N"
        set(v) { field = v; invalidate() }

    private var changedAt = 0L
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val arc = RectF()
    private val dash = DashPathEffect(floatArrayOf(6f, 6f), 0f)

    private fun colorFor(m: Mode) = when (m) {
        Mode.IDLE -> 0xFF6C5CE7.toInt()
        Mode.LISTENING -> 0xFF00B894.toInt()
        Mode.HEARING -> 0xFF00D8A4.toInt()
        Mode.THINKING -> 0xFFFDCB6E.toInt()
        Mode.WORKING -> 0xFFA29BFE.toInt()
        Mode.SPEAKING -> 0xFF0984E3.toInt()
        Mode.WAITING -> 0xFFE17055.toInt()
        Mode.STUCK -> 0xFFD63031.toInt()
    }

    private fun lighter(c: Int) = Color.argb(255, (Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
    private fun withAlpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis()
        val w = width.toFloat(); val h = height.toFloat()
        var cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f
        val core = r * 0.66f
        val col = colorFor(mode)
        fun wave(periodMs: Double, phase: Double = 0.0) = sin(2 * PI * (t / periodMs + phase)).toFloat()

        if (mode == Mode.STUCK && t - changedAt < 700) cx += wave(90.0) * 3 * d   // a short "no" shake

        // ---- around the core
        when (mode) {
            Mode.IDLE -> { fill.color = withAlpha(col, 0.22f + 0.10f * wave(2600.0)); c.drawCircle(cx, cy, core * (1.12f + 0.05f * wave(2600.0)), fill) }
            Mode.LISTENING, Mode.HEARING -> {
                stroke.pathEffect = null; stroke.strokeWidth = 2.5f * d
                val period = if (mode == Mode.HEARING) 900.0 else 1500.0
                for (i in 0 until 3) {
                    val p = (((t / period) + i / 3.0) % 1.0).toFloat()
                    stroke.color = withAlpha(col, (1f - p) * 0.75f)
                    c.drawCircle(cx, cy, core + (r - core) * p, stroke)
                }
            }
            Mode.THINKING -> {
                stroke.pathEffect = null; stroke.strokeWidth = 3f * d; stroke.color = col
                val rr = core + (r - core) * 0.55f
                arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
                val a = (t / 3L % 360).toFloat()
                c.drawArc(arc, a, 100f, false, stroke)
                stroke.color = withAlpha(col, 0.55f); c.drawArc(arc, a + 180f, 60f, false, stroke)
            }
            Mode.WORKING -> {
                stroke.pathEffect = dash; stroke.strokeWidth = 2.5f * d; stroke.color = col
                val rr = core + (r - core) * 0.55f
                c.save(); c.rotate((t / 6L % 360).toFloat(), cx, cy); c.drawCircle(cx, cy, rr, stroke); c.restore()
                stroke.pathEffect = null
            }
            Mode.SPEAKING -> {
                stroke.pathEffect = null; stroke.strokeWidth = 2.2f * d; stroke.color = col
                val bars = 28
                for (i in 0 until bars) {
                    val ang = 2 * PI * i / bars
                    val amp = 0.35f + 0.65f * ((sin(t / 110.0 + i * 1.7) + sin(t / 170.0 + i * 0.9)).toFloat() / 4f + 0.5f)
                    val r1 = core + 2 * d; val r2 = core + 2 * d + (r - core - 3 * d) * amp
                    c.drawLine(cx + (r1 * kotlin.math.cos(ang)).toFloat(), cy + (r1 * sin(ang)).toFloat(), cx + (r2 * kotlin.math.cos(ang)).toFloat(), cy + (r2 * sin(ang)).toFloat(), stroke)
                }
            }
            Mode.WAITING -> { fill.color = withAlpha(col, 0.25f + 0.2f * wave(1600.0)); c.drawCircle(cx, cy, core * 1.25f, fill) }
            Mode.STUCK -> { fill.color = withAlpha(col, 0.3f); c.drawCircle(cx, cy, core * 1.2f, fill) }
        }

        // ---- the core orb
        val scale = when (mode) {
            Mode.HEARING -> 1f + 0.07f * wave(260.0)
            Mode.IDLE -> 1f + 0.025f * wave(2600.0)
            else -> 1f
        }
        val cr = core * scale
        fill.shader = RadialGradient(cx - cr * 0.3f, cy - cr * 0.35f, cr * 1.4f, lighter(col), col, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, cr, fill)
        fill.shader = null
        stroke.pathEffect = null; stroke.strokeWidth = 1.5f * d; stroke.color = 0xCCFFFFFF.toInt()
        c.drawCircle(cx, cy, cr, stroke)
        if (teaching) {
            stroke.pathEffect = dash; stroke.strokeWidth = 3f * d; stroke.color = 0xFFFF3B30.toInt()
            c.save(); c.rotate((t / 8L % 360).toFloat(), cx, cy); c.drawCircle(cx, cy, r - 2 * d, stroke); c.restore()
            stroke.pathEffect = null
        }
        text.textSize = cr * 0.95f
        c.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2f, text)

        // Smooth while busy, gentle (and cheap) while idle.
        if (visibility == VISIBLE) postInvalidateDelayed(if (mode == Mode.IDLE && !teaching) 66 else 16)
    }
}
