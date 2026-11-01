package tj.tvsaver

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.min

/**
 * Слой видео поверх фото: системный MediaPlayer (аппаратный декодер ТВ, без библиотек),
 * со звуком потише ([VOLUME] от громкости ТВ), вывод через SurfaceView — отдельный системный слой без промежуточной
 * текстуры (TextureView на этом ТВ иногда падал внутри Android при создании слоя).
 *
 * Переход: [box] (размытый фон из кадра видео) плавно проявляется поверх фото → на нём
 * появляется видео, вписанное целиком → в конце видео убирается, фон плавно гаснет.
 */
class VideoLayer(private val box: FrameLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var backdrop: ImageView? = null
    private var fade: Animator? = null
    private var finish: ((Boolean) -> Unit)? = null
    private var active = false
    private val timeout = Runnable { finish?.invoke(true) }
    private var startTimeout: Runnable? = null

    /** Видео на экране (или готовится) — фото под ним пока не трогаем. */
    val isActive get() = active

    /**
     * Играет [file] на фоне [backdropBmp] (слой сам его освободит). [onBegin] — начинает
     * проявляться, [onShown] — проявилось, [onFinished] — досмотрели (true) или не смогли (false).
     * Короткие ролики (GIF, «кружочки») крутятся несколько раз, чтобы их успели рассмотреть.
     */
    fun play(
        file: File, backdropBmp: Bitmap?, onBegin: () -> Unit, onShown: () -> Unit, onFinished: (ok: Boolean) -> Unit,
    ) {
        active = true
        if (backdropBmp != null) {
            backdrop = ImageView(box.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(backdropBmp)
            }
            box.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val mp = MediaPlayer()
        player = mp
        var plays = 0
        var loops = 1
        var done = false
        var prepared = false
        var surfaceReady = false
        finish = { ok ->
            if (!done) {
                done = true
                handler.removeCallbacks(timeout)
                startTimeout?.let { handler.removeCallbacks(it) }
                onFinished(ok)
            }
        }
        fun startIfReady() {
            if (!prepared || !surfaceReady || done) return
            startTimeout?.let { handler.removeCallbacks(it) }
            loops = if (mp.duration in 1 until SHORT_MS) SHORT_LOOPS else 1
            mp.start()
            handler.postDelayed(timeout, MAX_MS)
        }

        mp.setVolume(VOLUME, VOLUME)
        mp.setOnPreparedListener {
            prepared = true
            // Размер видео известен — добавляем SurfaceView ровно под «вписанный» прямоугольник.
            val sv = SurfaceView(box.context)
            surfaceView = sv
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (surfaceView !== sv || done) return
                    if (runCatching { mp.setDisplay(holder) }.isFailure) { finish?.invoke(false); return }
                    surfaceReady = true
                    startIfReady()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (surfaceView === sv) runCatching { mp.setDisplay(null) }
                }
            })
            box.addView(sv, fitParams(it.videoWidth, it.videoHeight))
        }
        mp.setOnCompletionListener {
            plays++
            if (plays < loops) { it.seekTo(0); it.start() } else finish?.invoke(true)
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "video error $what/$extra: $file")
            finish?.invoke(false)
            true
        }

        // Сначала плавно проявляем фон, параллельно готовим видео.
        onBegin()
        fadeTo(1f, onShown)
        try {
            mp.setDataSource(file.path)
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "video open failed: $file", t)
            finish?.invoke(false)
        }
        // Страховка: видео так и не запустилось — пропускаем, слайд-шоу не зависает.
        startTimeout = Runnable { if (!done) finish?.invoke(false) }.also { handler.postDelayed(it, 8000) }
    }

    /** Убирает видео сразу, затем плавно гасит фон (под ним уже нужное фото). */
    fun hide(onHidden: () -> Unit = {}) {
        handler.removeCallbacks(timeout)
        releasePlayer()
        if (box.alpha == 0f) { releaseBackdrop(); active = false; onHidden(); return }
        fadeTo(0f) { releaseBackdrop(); active = false; onHidden() }
    }

    fun release() {
        handler.removeCallbacks(timeout)
        startTimeout?.let { handler.removeCallbacks(it) }
        fade?.cancel()
        box.alpha = 0f
        releasePlayer()
        releaseBackdrop()
        active = false
    }

    private fun fadeTo(alpha: Float, end: () -> Unit) {
        fade?.cancel()
        fade = ObjectAnimator.ofFloat(box, View.ALPHA, box.alpha, alpha).apply {
            duration = Slideshow.FADE_MS
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** «Вписать»: всё видео на экране по центру, пропорции сохраняются. */
    private fun fitParams(w: Int, h: Int): FrameLayout.LayoutParams {
        val bw = box.width.takeIf { it > 0 } ?: box.resources.displayMetrics.widthPixels
        val bh = box.height.takeIf { it > 0 } ?: box.resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return FrameLayout.LayoutParams(bw, bh, Gravity.CENTER)
        val s = min(bw.toFloat() / w, bh.toFloat() / h)
        return FrameLayout.LayoutParams((w * s).toInt(), (h * s).toInt(), Gravity.CENTER)
    }

    private fun releasePlayer() {
        finish = null
        player?.release()
        player = null
        surfaceView?.let { box.removeView(it) }
        surfaceView = null
    }

    private fun releaseBackdrop() {
        backdrop?.let {
            val bmp = (it.drawable as? BitmapDrawable)?.bitmap
            box.removeView(it)
            it.setImageDrawable(null)
            bmp?.recycle()
        }
        backdrop = null
    }

    companion object {
        private const val TAG = "tvsaver"
        private const val SHORT_MS = 10_000
        private const val SHORT_LOOPS = 3
        /** Доля от громкости ТВ (линейная амплитуда): 0.3 ≈ −10 дБ, заметно тише среднего. */
        private const val VOLUME = 0.3f
        /** Страховка: даже очень длинное видео не держит экран дольше 2 минут. */
        private const val MAX_MS = 120_000L
    }
}
