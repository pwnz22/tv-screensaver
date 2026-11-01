package tj.tvsaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Sky { SUN, MOON, PARTLY_SUN, PARTLY_MOON, CLOUD, FOG, RAIN, SNOW, STORM }

/** Коды WMO (Open-Meteo) → описание по-таджикски и тип иконки. */
object WeatherCodes {
    /** Описание по-таджикски. */
    fun describe(code: Int): String = when (code) {
        0 -> "Осмон соф"
        1 -> "Асосан соф"
        2 -> "Қисман абрнок"
        3 -> "Абрнок"
        45 -> "Туман"
        48 -> "Туман бо қиров"
        51, 53 -> "Борони майда"
        55 -> "Борони майдаи сахт"
        56, 57 -> "Борони майдаи яхбаста"
        61 -> "Борони сабук"
        63 -> "Борон"
        65 -> "Борони сахт"
        66, 67 -> "Борони яхбаста"
        71 -> "Барфи сабук"
        73 -> "Барф"
        75 -> "Барфи сахт"
        77 -> "Донаҳои барф"
        80 -> "Борони кӯтоҳмуддат"
        81 -> "Борони шадид"
        82 -> "Борони хеле шадид"
        85 -> "Барфи кӯтоҳмуддат"
        86 -> "Барфи шадид"
        95 -> "Раъду барқ"
        96, 99 -> "Раъду барқ бо жола"
        else -> "—"
    }

    fun sky(code: Int, isDay: Boolean): Sky = when (code) {
        0 -> if (isDay) Sky.SUN else Sky.MOON
        1, 2 -> if (isDay) Sky.PARTLY_SUN else Sky.PARTLY_MOON
        3 -> Sky.CLOUD
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUD
    }
}

/** Простые погодные значки, нарисованные на Canvas (без картинок и шрифтов). */
class WeatherIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var sky: Sky = Sky.SUN
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        stroke.strokeWidth = s * 0.06f
        when (sky) {
            Sky.SUN -> sun(canvas, s * 0.5f, s * 0.5f, s * 0.2f)
            Sky.MOON -> moon(canvas, s * 0.5f, s * 0.5f, s * 0.3f)
            Sky.PARTLY_SUN -> { sun(canvas, s * 0.36f, s * 0.34f, s * 0.15f); cloud(canvas, s, 0.12f) }
            Sky.PARTLY_MOON -> { moon(canvas, s * 0.38f, s * 0.34f, s * 0.2f); cloud(canvas, s, 0.12f) }
            Sky.CLOUD -> cloud(canvas, s, 0f)
            Sky.FOG -> {
                cloud(canvas, s, -0.1f)
                for (i in 0..1) {
                    val y = s * (0.78f + i * 0.12f)
                    canvas.drawLine(s * 0.18f, y, s * 0.82f, y, stroke)
                }
            }
            Sky.RAIN -> {
                cloud(canvas, s, -0.12f)
                stroke.color = 0xFF81D4FA.toInt()
                for (i in 0..2) {
                    val x = s * (0.32f + i * 0.18f)
                    canvas.drawLine(x, s * 0.72f, x - s * 0.06f, s * 0.9f, stroke)
                }
                stroke.color = 0xFFFFFFFF.toInt()
            }
            Sky.SNOW -> {
                cloud(canvas, s, -0.12f)
                fill.color = 0xFFFFFFFF.toInt()
                for (i in 0..2) {
                    canvas.drawCircle(s * (0.3f + i * 0.2f), s * (0.8f + (i % 2) * 0.08f), s * 0.045f, fill)
                }
            }
            Sky.STORM -> {
                cloud(canvas, s, -0.12f)
                fill.color = 0xFFFFD54F.toInt()
                path.reset()
                path.moveTo(s * 0.52f, s * 0.62f)
                path.lineTo(s * 0.38f, s * 0.82f)
                path.lineTo(s * 0.5f, s * 0.82f)
                path.lineTo(s * 0.42f, s * 0.98f)
                path.lineTo(s * 0.64f, s * 0.74f)
                path.lineTo(s * 0.52f, s * 0.74f)
                path.close()
                canvas.drawPath(path, fill)
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0xFFFFD54F.toInt()
        c.drawCircle(cx, cy, r, fill)
        stroke.color = 0xFFFFD54F.toInt()
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * r * 1.45f, cy + sa * r * 1.45f, cx + ca * r * 1.95f, cy + sa * r * 1.95f, stroke)
        }
        stroke.color = 0xFFFFFFFF.toInt()
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        // Полумесяц: круг минус смещённый круг.
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        path.op(cut, Path.Op.DIFFERENCE)
        fill.color = 0xFFFFF3C4.toInt()
        c.drawPath(path, fill)
    }

    /** Облако; dy сдвигает его по вертикали (доля размера). */
    private fun cloud(c: Canvas, s: Float, dy: Float) {
        val y = s * (0.62f + dy)
        fill.color = 0xFFECEFF1.toInt()
        c.drawCircle(s * 0.36f, y, s * 0.16f, fill)
        c.drawCircle(s * 0.56f, y - s * 0.08f, s * 0.2f, fill)
        c.drawCircle(s * 0.74f, y + s * 0.02f, s * 0.13f, fill)
        c.drawRect(s * 0.36f, y, s * 0.74f, y + s * 0.15f, fill)
        c.drawCircle(s * 0.36f, y + s * 0.0f, s * 0.15f, fill)
    }
}
