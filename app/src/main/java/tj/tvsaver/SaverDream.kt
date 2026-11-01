package tj.tvsaver

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Заставка: фото и видео из Telegram + неподвижная строка сверху (часы, погода, курсы к сомони).
 * Пока заставка на экране, бот опрашивается постоянно (фото появляется сразу);
 * в остальное время раз в ~20 минут заглядывает TelegramJob.
 */
class SaverDream : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var net: ExecutorService? = null
    private var slideshow: Slideshow? = null
    private var running = false

    private lateinit var prefs: Prefs
    private lateinit var cache: DataCache

    private lateinit var panel: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherBox: View
    private lateinit var weatherIcon: WeatherIconView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherMinMax: TextView
    private lateinit var ratesBox: LinearLayout
    private lateinit var prayerBox: View
    private lateinit var prayerLabel: TextView
    private lateinit var prayerTime: TextView
    private var prayer: PrayerDays? = null
    private lateinit var footer: TextView
    private lateinit var caption: TextView
    private lateinit var emptyHint: TextView
    private lateinit var arrowPrev: View
    private lateinit var arrowNext: View

    private var telegram: TelegramSync? = null
    private var telegramThread: Thread? = null

    private val ru = Locale("ru", "RU")
    private val timeFmt = SimpleDateFormat("HH:mm", ru)
    private val dayFmt = SimpleDateFormat("dd.MM", ru)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // любая кнопка пульта, кроме стрелок (см. dispatchKeyEvent), закрывает заставку
        isFullscreen = true
        isScreenBright = true
        setContentView(R.layout.dream)

        prefs = Prefs(this)
        cache = DataCache(this)
        panel = findViewById(R.id.panel)
        val clockBox = findViewById<View>(R.id.clock_box)
        clock = clockBox.findViewById(R.id.big_value)
        date = clockBox.findViewById(R.id.big_caption)
        weatherBox = findViewById(R.id.weather)
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)
        weatherDesc = findViewById(R.id.weather_desc)
        weatherMinMax = findViewById(R.id.weather_minmax)
        ratesBox = findViewById(R.id.rates)
        prayerBox = findViewById(R.id.prayer)
        prayerLabel = prayerBox.findViewById(R.id.big_caption)
        prayerTime = prayerBox.findViewById(R.id.big_value)
        footer = findViewById(R.id.footer)
        caption = findViewById(R.id.caption)
        emptyHint = findViewById(R.id.empty_hint)
        arrowPrev = findViewById(R.id.arrow_prev)
        arrowNext = findViewById(R.id.arrow_next)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = true
        net = Executors.newSingleThreadExecutor()

        slideshow = Slideshow(
            this,
            findViewById<ImageView>(R.id.photo_a),
            findViewById<ImageView>(R.id.photo_b),
            VideoLayer(findViewById(R.id.video_box)),
            prefs.intervalSec * 1000L,
            onPhotoStart = { emptyHint.visibility = View.GONE; showCaption(it.caption) },
            onEmpty = { showEmptyHint() },
        ).also { it.start() }
        isDreaming = true
        startTelegram()

        tickClock()
        renderWeather(cache.loadWeather())
        renderRates(cache.loadRates())
        if (prefs.showWeather) scheduleWeather()
        if (prefs.showRates) scheduleRates()
        if (prefs.showPrayer) {
            // Сразу показываем расписание из кеша (оно на весь месяц), обновляем в фоне.
            prayer = Prayer.load(this)?.takeIf { Prayer.hasToday(it, prefs.city) }
            renderPrayer()
            refreshPrayer()
        }
        NightSleep.apply(this)

        // Первая позиция — сразу после разметки, дальше блок медленно «гуляет».
    }

    /** Стрелки влево/вправо листают фото и видео, остальные кнопки как обычно будят ТВ. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
            else -> return super.dispatchKeyEvent(event)
        }
        // ACTION_UP тоже съедаем, иначе он дойдёт до DreamService и закроет заставку.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (forward) slideshow?.next() else slideshow?.prev()
            flash(if (forward) arrowNext else arrowPrev)
        }
        return true
    }

    /** Нажатая стрелка на экране на миг становится ярче. */
    private fun flash(arrow: View) {
        arrow.animate().cancel()
        arrow.alpha = 1f
        arrow.animate().alpha(ARROW_ALPHA).setStartDelay(250).setDuration(600).withLayer().start()
    }

    override fun onDreamingStopped() {
        stopAll()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        stopAll()
        super.onDetachedFromWindow()
    }

    private fun stopAll() {
        if (!running) return
        running = false
        isDreaming = false
        stopTelegram()
        handler.removeCallbacksAndMessages(null)
        net?.shutdownNow()
        net = null
        slideshow?.stop()
        slideshow = null
    }

    // ---------- Telegram ----------

    private fun startTelegram() {
        val store = TelegramStore(this)
        if (!store.isConfigured) return
        TelegramJob.schedule(this)
        val sync = TelegramSync(this).also { telegram = it }
        telegramThread = Thread({
            while (running) {
                try {
                    TelegramSync.lock.lockInterruptibly()
                    val photos = try { sync.pollOnce(TG_POLL_SEC, live = true) } finally { TelegramSync.lock.unlock() }
                    photos.forEach { p -> handler.post { slideshow?.showNow(p) } }
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (!running) break
                    Log.w(TAG, "telegram poll failed", t)
                    try { Thread.sleep(TG_RETRY_MS) } catch (e: InterruptedException) { break }
                }
            }
        }, "tvsaver-telegram").apply { start() }
    }

    private fun stopTelegram() {
        telegram?.cancel()          // обрывает долгий getUpdates
        telegramThread?.interrupt()
        telegram = null
        telegramThread = null
    }

    /** Фото ещё нет — подсказываем, куда их присылать. */
    private fun showEmptyHint() {
        val bot = TelegramStore(this).botName
        emptyHint.text = if (bot.isNotEmpty()) "Ба боти @$bot акс ё видео фиристед —\nонҳо дар ин ҷо пайдо мешаванд"
            else "Ҳоло акс нест.\nБоти Telegram-ро дар танзимоти заставка пайваст кунед."
        emptyHint.visibility = View.VISIBLE
    }

    /** Подпись к фото (только у фото из Telegram) плавно меняется вместе с фото. */
    private fun showCaption(raw: String?) {
        val text = raw?.let { if (it.startsWith("от ")) "аз " + it.removePrefix("от ") else it }
        caption.animate().cancel()
        if (text.isNullOrBlank()) {
            caption.animate().alpha(0f).setDuration(Slideshow.FADE_MS).withLayer().start()
            return
        }
        caption.animate().alpha(0f).setDuration(if (caption.alpha > 0f) 400L else 0L).withLayer().withEndAction {
            caption.text = text
            caption.animate().alpha(1f).setDuration(Slideshow.FADE_MS).withLayer().start()
        }.start()
    }

    // ---------- Часы ----------

    private fun tickClock() {
        if (!running) return
        val now = Date()
        clock.text = timeFmt.format(now)
        date.text = TajikDate.format()
        // Следующее обновление — ровно в начале следующей минуты.
        val cal = Calendar.getInstance()
        val delay = 60_000L - (cal.get(Calendar.SECOND) * 1000L + cal.get(Calendar.MILLISECOND)) + 50
        handler.postDelayed({ tickClock() }, delay)
        // Футер содержит «ЧЧ:ММ обновления» и пометку об устаревших данных.
        renderFooter()
        renderPrayer()
        if (cal.get(Calendar.MINUTE) % 10 == 0) NightSleep.apply(this)
    }

    // ---------- Намаз ----------

    /**
     * Расписание с shuroiulamo.tj — таблица на месяц. Обновляем, если нет данных на сегодня
     * или им больше 3 дней (чтобы подхватить следующий месяц, когда его опубликуют).
     * Нет сети — работаем по кешу.
     */
    private fun refreshPrayer() {
        if (!running) return
        val city = prefs.city
        val cached = Prayer.load(this)
        val upToDate = cached != null && Prayer.hasToday(cached, city) &&
            System.currentTimeMillis() - cached.fetchedAt < 3 * 24 * 60 * 60_000L
        if (upToDate) { handler.postDelayed({ refreshPrayer() }, 6 * 60 * 60_000L); return }
        net?.execute {
            val fresh = runCatching { Prayer.fetch(city) }
                .onFailure { Log.w(TAG, "prayer failed", it) }.getOrNull()
            handler.post {
                if (!running) return@post
                if (fresh != null) {
                    Prayer.save(this, fresh)
                    prayer = fresh
                    renderPrayer()
                }
                handler.postDelayed({ refreshPrayer() }, if (fresh != null) 6 * 60 * 60_000L else RETRY_MS)
            }
        }
    }

    /** «15:55» / «Намоз – Аср» — следующий намаз по официальному расписанию. */
    private fun renderPrayer() {
        val p = prayer
        val next = if (prefs.showPrayer && p != null) Prayer.next(p) else null
        if (next == null) { prayerBox.visibility = View.GONE; return }
        prayerLabel.text = "Намоз – " + next.first
        prayerTime.text = next.second
        prayerBox.visibility = View.VISIBLE
    }

    // ---------- Погода ----------

    private fun scheduleWeather() {
        val w = cache.loadWeather()
        val city = prefs.city
        val fresh = w != null && sameCity(w, city)
        val delay = if (fresh) (w!!.fetchedAt + WEATHER_EVERY_MS - System.currentTimeMillis()).coerceAtLeast(0) else 0
        handler.postDelayed({ fetchWeather() }, delay)
    }

    private fun fetchWeather() {
        if (!running) return
        val city = prefs.city
        net?.execute {
            val result = runCatching { WeatherSource.fetch(city) }
            handler.post {
                if (!running) return@post
                result.onSuccess {
                    cache.saveWeather(it)
                    renderWeather(it)
                    handler.postDelayed({ fetchWeather() }, WEATHER_EVERY_MS)
                }.onFailure {
                    Log.w(TAG, "weather failed", it)
                    handler.postDelayed({ fetchWeather() }, RETRY_MS)
                }
            }
        }
    }

    private fun sameCity(w: Weather, c: City) = abs(w.lat - c.lat) < 0.01 && abs(w.lon - c.lon) < 0.01

    private fun renderWeather(w: Weather?) {
        if (!prefs.showWeather || w == null || !sameCity(w, prefs.city)) {
            weatherBox.visibility = View.GONE
            renderFooter()
            return
        }
        weatherBox.visibility = View.VISIBLE
        weatherIcon.sky = WeatherCodes.sky(w.code, w.isDay)
        weatherTemp.text = formatTemp(w.temp)
        weatherDesc.text = "${Prefs.tjName(prefs.city)} · ${WeatherCodes.describe(w.code)}"
        weatherMinMax.text = "паст ${formatTemp(w.min)}   баланд ${formatTemp(w.max)}"
        renderFooter()
    }

    private fun formatTemp(t: Double): String {
        val r = t.roundToInt()
        return (if (r > 0) "+$r" else "$r") + "°"
    }

    // ---------- Курсы ----------

    private fun scheduleRates() {
        val r = cache.loadRates()
        val delay = if (r != null) (r.fetchedAt + RATES_EVERY_MS - System.currentTimeMillis()).coerceAtLeast(0) else 0
        handler.postDelayed({ fetchRates() }, delay)
    }

    private fun fetchRates() {
        if (!running) return
        net?.execute {
            val result = runCatching { RatesSource.fetch() }
            handler.post {
                if (!running) return@post
                result.onSuccess {
                    cache.saveRates(it)
                    renderRates(it)
                    // Если сработал только запасной источник — НБТ пробуем снова через час.
                    val next = if (it.source == RatesSource.NBT) RATES_EVERY_MS else 60 * 60_000L
                    handler.postDelayed({ fetchRates() }, next)
                }.onFailure {
                    Log.w(TAG, "rates failed", it)
                    handler.postDelayed({ fetchRates() }, RETRY_MS)
                }
            }
        }
    }

    private fun renderRates(r: Rates?) {
        ratesBox.removeAllViews()
        val codes = prefs.currencies.filter { r?.perUnit?.containsKey(it) == true }
        if (!prefs.showRates || r == null || codes.isEmpty()) {
            ratesBox.visibility = View.GONE
            renderFooter()
            return
        }
        val inflater = LayoutInflater.from(this)
        for (code in codes) {
            val item = inflater.inflate(R.layout.item_rate, ratesBox, false)
            val (label, value) = formatRate(code, r.perUnit.getValue(code))
            item.findViewById<TextView>(R.id.rate_code).text = label
            item.findViewById<TextView>(R.id.rate_value).text = value
            ratesBox.addView(item)
        }
        ratesBox.visibility = View.VISIBLE
        renderFooter()
    }

    /** «1 USD, смн» → «9,2395»; очень мелкие валюты показываем за 100/1000 единиц. */
    private fun formatRate(code: String, perUnit: Double): Pair<String, String> {
        var mult = 1
        while (perUnit * mult < 0.01 && mult < 1_000_000) mult *= 10
        return "$mult $code, смн" to String.format(ru, "%.4f", perUnit * mult)
    }

    // ---------- Футер ----------

    private fun renderFooter() {
        if (!::footer.isInitialized) return
        val parts = ArrayList<String>()
        val now = System.currentTimeMillis()
        cache.loadWeather()?.takeIf { prefs.showWeather && weatherBox.visibility == View.VISIBLE }?.let {
            val stale = now - it.fetchedAt > WEATHER_EVERY_MS * 2
            parts += "Обу ҳаво: Open-Meteo, навсозӣ соати ${timeFmt.format(Date(it.fetchedAt))}" +
                (if (stale) " (интернет нест)" else "")
        }
        cache.loadRates()?.takeIf { prefs.showRates && ratesBox.visibility == View.VISIBLE }?.let {
            val stale = now - it.fetchedAt > RATES_EVERY_MS * 2
            val src = if (it.source == RatesSource.NBT) "Бонки миллии Тоҷикистон" else "${it.source} (эҳтиётӣ)"
            val on = runCatching {
                " барои " + dayFmt.format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.date)!!)
            }.getOrDefault("")
            parts += "Қурби асъор: $src$on, навсозӣ соати ${timeFmt.format(Date(it.fetchedAt))}" +
                (if (stale) " (интернет нест)" else "")
        }
        footer.text = parts.joinToString("\n")
        footer.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        /** Прозрачность стрелок листания в покое (как в dream.xml). */
        private const val ARROW_ALPHA = 0.45f
        /** Заставка на экране — фоновая проверка Telegram в это время не нужна. */
        @Volatile var isDreaming = false

        private const val TG_POLL_SEC = 25
        private const val TG_RETRY_MS = 30_000L
        private const val TAG = "tvsaver"
        private const val WEATHER_EVERY_MS = 30 * 60_000L
        private const val RATES_EVERY_MS = 6 * 60 * 60_000L
        private const val RETRY_MS = 5 * 60_000L
    }
}
