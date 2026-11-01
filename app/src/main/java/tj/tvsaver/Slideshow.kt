package tj.tvsaver

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Элемент слайд-шоу: фото или видео. */
sealed class Photo {
    /** Подпись на экране (для фото из Telegram: «аз Модар · Самарқанд»). */
    open val caption: String? get() = null

    /** Файл фото/видео. */
    abstract val file: File

    data class FromFile(override val file: File, override val caption: String? = null) : Photo()
    data class Video(override val file: File, override val caption: String? = null) : Photo()

    companion object {
        private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "3gp")

        fun isVideo(f: File) = f.extension.lowercase() in VIDEO_EXT

        fun of(f: File, caption: String? = null): Photo =
            if (isVideo(f)) Video(f, caption) else FromFile(f, caption)
    }
}

object PhotoSource {
    val folder = File(Environment.getExternalStorageDirectory(), "Pictures/Screensaver")
    private val EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "m4v", "mov")

    fun userPhotos(): List<Photo> =
        (folder.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in EXT && !it.name.startsWith(".") }
            .map { Photo.of(it) }

    /** Все фото и видео по кругу: из Telegram (+ своя папка, если там что-то есть). Каждый круг — заново перемешано. */
    fun load(context: Context): List<Photo> =
        TelegramSync.photos(context) + userPhotos()
}

/** Декодирует фото сразу под размер экрана (не больше), чтобы не держать лишнюю память. */
object PhotoDecoder {
    /** Фото уже 5:4 (и вертикальные) не растягиваем на весь экран, а вписываем на размытом фоне. */
    private const val FIT_BELOW_RATIO = 1.25f

    fun decode(context: Context, photo: Photo, screenW: Int, screenH: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = when (photo) {
                is Photo.FromFile -> ImageDecoder.createSource(photo.file)
                is Photo.Video -> throw IllegalArgumentException("видео не декодируется как фото")
            }
            var fit = false
            val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                // Вертикальное/почти квадратное фото на весь экран обрезалось бы почти целиком —
                // такое показываем целиком на размытом фоне.
                fit = w.toFloat() / h < FIT_BELOW_RATIO
                val scale = if (fit) min(1f, min(screenW.toFloat() / w, screenH.toFloat() / h))
                    // «Cover»: картинка должна закрыть экран целиком, но не больше этого.
                    else min(1f, max(screenW.toFloat() / w, screenH.toFloat() / h))
                d.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
                // HARDWARE-битмап живёт только в видеопамяти: нет второй копии в ОЗУ.
                // Для «вписанного» фото нужен обычный — из него ещё рисуем фон.
                d.allocator = if (fit) ImageDecoder.ALLOCATOR_SOFTWARE else ImageDecoder.ALLOCATOR_HARDWARE
            }
            return if (fit) composeOnBlur(bmp, screenW, screenH) else bmp
        }
        return decodeLegacy(context, photo, screenW, screenH)
    }

    /** Размытый фон под видео (из кадра в середине ролика): по бокам вертикального видео и для плавного перехода. */
    fun videoBackdrop(file: File, screenW: Int, screenH: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 500
            val frame = (if (Build.VERSION.SDK_INT >= 27)
                r.getScaledFrameAtTime(durUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
            else r.getFrameAtTime(durUs)) ?: return null
            val out = Backdrop.render(frame, screenW, screenH)
            frame.recycle()
            return Backdrop.toHardware(out)
        } finally {
            r.release()
        }
    }

    /** Фото по центру, по бокам — оно же, размытое и затемнённое (см. [Backdrop]). */
    private fun composeOnBlur(photo: Bitmap, screenW: Int, screenH: Int): Bitmap {
        val out = Backdrop.render(photo, screenW, screenH)
        val canvas = Canvas(out)
        canvas.drawBitmap(photo, (screenW - photo.width) / 2f, (screenH - photo.height) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        photo.recycle()
        return Backdrop.toHardware(out)
    }

    /** Для API 26–27: inSampleSize (степень двойки) под экран. */
    private fun decodeLegacy(context: Context, photo: Photo, screenW: Int, screenH: Int): Bitmap {
        fun open() = when (photo) {
            is Photo.FromFile -> photo.file.inputStream()
            is Photo.Video -> throw IllegalArgumentException("видео не декодируется как фото")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Не удалось открыть $photo")
    }
}

/**
 * Две ImageView друг над другом: новое фото проявляется поверх старого (crossfade),
 * каждое медленно приближается/сдвигается (Ken Burns). В памяти максимум два битмапа.
 * Видео играет слоем [video] поверх фото; после него снова идут фото.
 */
class Slideshow(
    private val context: Context,
    private val viewA: ImageView,
    private val viewB: ImageView,
    private val video: VideoLayer,
    private val intervalMs: Long,
    /** Новое фото начинает проявляться (можно показать его подпись). */
    private val onPhotoStart: (Photo) -> Unit = {},
    /** Вызывается, когда новое фото полностью проявилось — до следующего декодирования процессор свободен. */
    private val onPhotoShown: () -> Unit = {},
    /** Показывать нечего (фото из Telegram ещё нет). */
    private val onEmpty: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    // Декодер с низким приоритетом: распаковка JPEG не должна отнимать кадры у анимаций.
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "tvsaver-decoder")
    }
    private var photos: List<Photo> = emptyList()
    private var queue = ArrayDeque<Photo>()
    private var front: ImageView = viewA
    private var running = false
    private var failures = 0
    private var currentFade: Animator? = null
    private val nextTask = Runnable { showNext() }
    private val seen = SeenPhotos(context)
    /** Идёт декодирование или crossfade — новый показ начинать нельзя. */
    private var busy = false
    /** Пришло фото «вне очереди», пока были заняты — покажем сразу после текущего перехода. */
    private var urgent = false
    /** Показанные по порядку (последнее — на экране): для стрелки «назад». */
    private val history = ArrayDeque<Photo>()
    /** Меняется, когда видео прервали стрелкой: его поздний onFinished уже ничего не запускает. */
    private var videoToken = 0

    fun start() {
        running = true
        showNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
        currentFade?.cancel()
        video.release()
        for (v in listOf(viewA, viewB)) {
            v.animate().cancel()
            release(v)
        }
    }

    /** Показать фото вне очереди (только что пришло из Telegram). */
    fun showNow(photo: Photo) {
        if (!running) return
        queue.addFirst(photo)
        // Видео не обрываем: новое покажется сразу после него.
        if (busy || video.isActive) { urgent = true; return }
        handler.removeCallbacks(nextTask)
        showNext()
    }

    /** Стрелка «вправо»: следующее фото/видео сразу (идущее видео обрывается). */
    fun next() {
        if (!jumpAllowed()) return
        showNext(manual = true)
    }

    /** Стрелка «влево»: предыдущее; текущее встаёт в очередь следующим. */
    fun prev() {
        if (history.size < 2 || !jumpAllowed()) return
        queue.addFirst(history.removeLast())
        queue.addFirst(history.removeLast())
        showNext(manual = true)
    }

    /** Во время декодирования/перехода нажатие пропускаем — переход и так короткий. */
    private fun jumpAllowed(): Boolean {
        if (!running || busy) return false
        handler.removeCallbacks(nextTask)
        videoToken++
        return true
    }

    private fun remember(photo: Photo) {
        seen.add(photo.file.name)
        history.addLast(photo)
        if (history.size > HISTORY_MAX) history.removeFirst()
    }

    private fun nextPhoto(): Photo? {
        if (queue.isEmpty()) {
            // Каждый круг заново читаем папку: новые фото подхватятся без перезапуска.
            // Ещё не показанные — первыми, по порядку получения; остальные — вперемешку.
            photos = PhotoSource.load(context)
            seen.prune(photos.map { it.file.name })
            val (fresh, old) = photos.partition { !seen.contains(it.file.name) }
            queue = ArrayDeque(fresh.sortedBy { it.file.lastModified() } + old.shuffled())
        }
        return queue.removeFirstOrNull()
    }

    private fun showNext(manual: Boolean = false) {
        if (!running) return
        val photo = nextPhoto() ?: run {
            // Пусто: подсказка на экране, через интервал проверим снова (новые фото придут и через showNow).
            onEmpty()
            handler.postDelayed(nextTask, intervalMs)
            return
        }
        busy = true
        urgent = false
        if (photo is Photo.Video) { startVideo(photo); return }
        val dm = context.resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels)
        val h = min(dm.widthPixels, dm.heightPixels)
        decoder.execute {
            val bmp = try {
                PhotoDecoder.decode(context, photo, w, h)
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed: $photo", t)
                null
            }
            handler.post {
                if (!running) { bmp?.recycle(); return@post }
                if (bmp == null) {
                    // Битый файл — пропускаем; если всё подряд битое, не крутимся в цикле.
                    failures++
                    busy = false
                    handler.postDelayed(nextTask, if (failures > 5) intervalMs else 500)
                } else {
                    failures = 0
                    crossfade(bmp, photo, if (manual) MANUAL_FADE_MS else FADE_MS)
                    handler.postDelayed(nextTask, intervalMs)
                }
            }
        }
    }

    private fun startVideo(item: Photo.Video) {
        handler.removeCallbacks(nextTask)
        val dm = context.resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels)
        val h = min(dm.widthPixels, dm.heightPixels)
        // Кадр для размытого фона достаём в фоне — это доли секунды, но не на главном потоке.
        decoder.execute {
            val bg = try { PhotoDecoder.videoBackdrop(item.file, w, h) } catch (t: Throwable) { null }
            handler.post {
                if (!running) { bg?.recycle(); return@post }
                playVideo(item, bg)
            }
        }
    }

    private fun playVideo(item: Photo.Video, backdrop: Bitmap?) {
        val go = {
            val token = ++videoToken
            video.play(
                item.file,
                backdrop,
                onBegin = { remember(item); onPhotoStart(item) },
                onShown = { busy = false; onPhotoShown() },
                onFinished = { ok ->
                    if (token != videoToken) return@play
                    busy = false
                    if (ok) {
                        failures = 0
                        showNext()   // следующее фото проявится из-под видео
                    } else {
                        failures++
                        handler.postDelayed(nextTask, if (failures > 5) intervalMs else 500)
                    }
                },
            )
        }
        // Видео подряд: сначала убираем предыдущее.
        if (video.isActive) video.hide { if (running) go() else backdrop?.recycle() } else go()
    }

    private fun crossfade(bmp: Bitmap, photo: Photo, fadeMs: Long) {
        remember(photo)
        onPhotoStart(photo)
        val back = if (front === viewA) viewB else viewA
        val old = front
        back.setImageBitmap(bmp)
        kenBurns(back)
        if (video.isActive) {
            // Фото под видео меняем мгновенно (его не видно), потом гасим видео.
            viewB.alpha = if (back === viewB) 1f else 0f
            old.animate().cancel()
            release(old)
            front = back
            video.hide { transitionDone() }
            return
        }
        // Порядок слоёв не меняем (иначе фото закроет блок с часами):
        // viewB лежит поверх viewA, поэтому либо проявляем B, либо гасим B над новым A.
        val fade = if (back === viewB) {
            back.alpha = 0f
            ObjectAnimator.ofFloat(viewB, View.ALPHA, 0f, 1f)
        } else {
            back.alpha = 1f
            ObjectAnimator.ofFloat(viewB, View.ALPHA, 1f, 0f)
        }
        fade.duration = fadeMs
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Старый битмап больше не нужен — сразу освобождаем.
                old.animate().cancel()
                release(old)
                transitionDone()
            }
        })
        currentFade = fade
        fade.start()
        front = back
    }

    private fun transitionDone() {
        busy = false
        if (!running) return
        if (urgent) {
            urgent = false
            handler.removeCallbacks(nextTask)
            showNext()
        } else {
            onPhotoShown()
        }
    }

    private fun kenBurns(v: ImageView) {
        val zoomIn = Random.nextBoolean()
        val s0 = if (zoomIn) 1.03f else 1.13f
        val s1 = if (zoomIn) 1.13f else 1.03f
        // Сдвиг не больше запаса от увеличения, чтобы не показывать края.
        val maxShiftX = (min(s0, s1) - 1f) * v.width / 2f * 0.9f
        val maxShiftY = (min(s0, s1) - 1f) * v.height / 2f * 0.9f
        v.animate().cancel()
        v.scaleX = s0; v.scaleY = s0
        v.translationX = Random.nextFloat() * 2 * maxShiftX - maxShiftX
        v.translationY = Random.nextFloat() * 2 * maxShiftY - maxShiftY
        v.animate()
            .scaleX(s1).scaleY(s1)
            .translationX(Random.nextFloat() * 2 * maxShiftX - maxShiftX)
            .translationY(Random.nextFloat() * 2 * maxShiftY - maxShiftY)
            .setInterpolator(LinearInterpolator())
            .setDuration(intervalMs + FADE_MS * 2)
            .start()
    }

    private fun release(v: ImageView) {
        val bmp = (v.drawable as? BitmapDrawable)?.bitmap
        v.setImageDrawable(null)
        bmp?.recycle()
    }

    companion object {
        private const val TAG = "tvsaver"
        const val FADE_MS = 1500L
        /** Переход по стрелке быстрее обычного, чтобы листать без ожидания. */
        private const val MANUAL_FADE_MS = 500L
        private const val HISTORY_MAX = 100
    }
}
