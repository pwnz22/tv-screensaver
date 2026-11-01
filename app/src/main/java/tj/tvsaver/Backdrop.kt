package tj.tvsaver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max

/**
 * Размытый затемнённый фон из картинки — для вертикальных фото и видео, чтобы по бокам
 * не было пустоты. Без тяжёлых фильтров: сжимаем до крошечного размера, размываем
 * «скользящим средним» и плавно растягиваем на экран.
 */
object Backdrop {

    /** Обычный (software) битмап размером с экран — на нём ещё можно рисовать. */
    fun render(src: Bitmap, screenW: Int, screenH: Int): Bitmap {
        val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // Берём центральную часть в пропорциях экрана («cover»).
        val scale = max(screenW.toFloat() / src.width, screenH.toFloat() / src.height)
        val cropW = (screenW / scale).toInt().coerceIn(1, src.width)
        val cropH = (screenH / scale).toInt().coerceIn(1, src.height)
        val cropX = (src.width - cropW) / 2
        val cropY = (src.height - cropH) / 2
        val tinyW = 24
        val tinyH = max(1, tinyW * screenH / screenW)
        val tiny = Bitmap.createBitmap(tinyW, tinyH, Bitmap.Config.ARGB_8888)
        Canvas(tiny).drawBitmap(src, Rect(cropX, cropY, cropX + cropW, cropY + cropH), Rect(0, 0, tinyW, tinyH), paint)
        val mid = Bitmap.createScaledBitmap(tiny, tinyW * 4, tinyH * 4, true)
        tiny.recycle()
        boxBlur(mid, radius = 4, passes = 3)
        val canvas = Canvas(out)
        canvas.drawBitmap(mid, null, Rect(0, 0, screenW, screenH), paint)
        canvas.drawColor(0x73000000)   // затемняем, чтобы главное изображение выделялось
        mid.recycle()
        return out
    }

    /** Переводит в HARDWARE-битмап (живёт в видеопамяти), исходный освобождает. */
    fun toHardware(bmp: Bitmap): Bitmap {
        val hw = bmp.copy(Bitmap.Config.HARDWARE, false) ?: return bmp
        bmp.recycle()
        return hw
    }

    /** Несколько проходов «скользящего среднего» ≈ гауссово размытие. Только для крошечных картинок. */
    private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h)
        repeat(passes) {
            blurLine(a, b, w, h, radius, horizontal = true)
            blurLine(b, a, w, h, radius, horizontal = false)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var al = 0; var rd = 0; var gr = 0; var bl = 0; var n = 0
                for (k in (i - r).coerceAtLeast(0)..(i + r).coerceAtMost(len - 1)) {
                    val c = src[if (horizontal) line * w + k else k * w + line]
                    al += c ushr 24; rd += (c shr 16) and 0xFF; gr += (c shr 8) and 0xFF; bl += c and 0xFF; n++
                }
                dst[if (horizontal) line * w + i else i * w + line] =
                    ((al / n) shl 24) or ((rd / n) shl 16) or ((gr / n) shl 8) or (bl / n)
            }
        }
    }
}
