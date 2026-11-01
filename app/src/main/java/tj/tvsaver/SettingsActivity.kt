package tj.tvsaver

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Настройки заставки, управляемые пультом.
 *
 * Можно задать и с компьютера через adb (удобно для своего города):
 *   adb shell am start -n tj.tvsaver/.SettingsActivity \
 *     --es city_name "Самарканд" --ef lat 39.65 --ef lon 66.96 \
 *     --es currencies "USD,RUB,EUR" --ei interval 60
 *
 * Telegram-бот (токен от @BotFather):
 *   adb shell am start -n tj.tvsaver/.SettingsActivity --es tg_token "123456:ABC..."
 *   adb shell am start -n tj.tvsaver/.SettingsActivity --ez tg_off true
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var rows: LinearLayout
    private lateinit var photosRow: SettingRow
    private lateinit var tg: TelegramStore
    private var clearArmed = false

    private val readPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        prefs = Prefs(this)
        tg = TelegramStore(this)
        rows = findViewById(R.id.rows)
        applyExtras(intent)
        buildRows()
        findViewById<TextView>(R.id.help).text =
            "Фото и видео присылайте Telegram-боту — они появятся в заставке.\n" +
            "Погода: Open-Meteo. Курсы: Нацбанк Таджикистана (nbt.tj), запасной — open.er-api.com.\n" +
            "В заставке стрелки ← → листают фото и видео, любая другая кнопка — выход."
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyExtras(intent)
        buildRows()
    }

    override fun onResume() {
        super.onResume()
        if (::photosRow.isInitialized) photosRow.value = photosStatus()
    }

    private fun applyExtras(i: Intent) {
        var changed = false
        if (i.hasExtra("lat") && i.hasExtra("lon")) {
            prefs.city = City(i.getStringExtra("city_name") ?: "Мой город",
                i.getFloatExtra("lat", 0f).toDouble(), i.getFloatExtra("lon", 0f).toDouble())
            changed = true
        }
        i.getStringExtra("currencies")?.let { prefs.currencies = it.split(','); changed = true }
        if (i.hasExtra("interval")) { prefs.intervalSec = i.getIntExtra("interval", 30).coerceIn(5, 3600); changed = true }
        if (changed) Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()

        i.getStringExtra("tg_token")?.let { connectBot(it.trim()) }
        if (i.getBooleanExtra("tg_off", false)) {
            tg.reset("")
            TelegramJob.cancel(this)
            Toast.makeText(this, "Telegram-бот отключён", Toast.LENGTH_SHORT).show()
        }
    }

    /** Проверяем токен через getMe и только потом сохраняем. */
    private fun connectBot(token: String) {
        Toast.makeText(this, "Проверяю бота…", Toast.LENGTH_SHORT).show()
        Thread {
            val me = runCatching { TelegramApi(token).getMe() }
            runOnUiThread {
                me.onSuccess {
                    if (token != tg.token) tg.reset(token)
                    tg.botName = it.optString("username")
                    TelegramJob.schedule(this)
                    Toast.makeText(this, "Бот @${tg.botName} подключён", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, "Бот не подключён: ${it.message}", Toast.LENGTH_LONG).show()
                }
                buildRows()
            }
        }.start()
    }

    private fun nightSleepStatus(): String {
        val h = prefs.nightSleepHours
        val v = if (h == 0) "Выкл" else "через $h ч без пульта"
        return if (h > 0 && !NightSleep.canWrite(this)) "$v (нет разрешения)" else v
    }

    private fun telegramStatus(): String = when {
        !tg.isConfigured -> "не подключён"
        tg.ownerId == 0L -> "@${tg.botName}: отправьте боту /start ${tg.ownerCode}"
        else -> "@${tg.botName} · людей: ${tg.allowed.size}"
    }

    private fun buildRows() {
        rows.removeAllViews()

        row("Запустить заставку", "OK") {
            // Somnambulator запускает выбранную в системе заставку.
            val ok = runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")))
            }.isSuccess
            if (!ok) Toast.makeText(this, "Не получилось запустить", Toast.LENGTH_SHORT).show()
        }

        line {
            row("Смена фото", "${prefs.intervalSec} с", it) { r ->
                val list = Prefs.INTERVALS
                val next = list.firstOrNull { it > prefs.intervalSec } ?: list[0]
                prefs.intervalSec = next
                r.value = "$next с"
            }
            row("Город (погода)", prefs.city.name, it) { r ->
                val list = Prefs.CITIES
                val idx = list.indexOfFirst { it.name == prefs.city.name }
                val next = list[(idx + 1) % list.size]   // свой город (idx = -1) → Душанбе
                prefs.city = next
                r.value = next.name
            }
        }

        line {
            row("Погода", SettingRow.onOff(prefs.showWeather), it) { r ->
                prefs.showWeather = !prefs.showWeather
                r.value = SettingRow.onOff(prefs.showWeather)
            }
            row("Курсы валют", SettingRow.onOff(prefs.showRates), it) { r ->
                prefs.showRates = !prefs.showRates
                r.value = SettingRow.onOff(prefs.showRates)
            }
            row("Время намаза", SettingRow.onOff(prefs.showPrayer), it) { r ->
                prefs.showPrayer = !prefs.showPrayer
                r.value = SettingRow.onOff(prefs.showPrayer)
            }
        }

        row("Ночью выключать ТВ (22:00–07:00)", nightSleepStatus()) { r ->
            val list = Prefs.NIGHT_SLEEP_CHOICES
            prefs.nightSleepHours = list[(list.indexOf(prefs.nightSleepHours) + 1) % list.size]
            NightSleep.apply(this)
            r.value = nightSleepStatus()
        }

        section("Валюты к сомони")
        val extra = prefs.currencies.filter { it !in Prefs.CURRENCY_CHOICES }
        val order = Prefs.CURRENCY_CHOICES + extra
        // По 5 в линию, чтобы 10 валют не занимали 10 строк.
        for (chunk in order.chunked(CURRENCIES_PER_LINE)) line { parent ->
            for (code in chunk) row(code, SettingRow.onOff(code in prefs.currencies), parent) { r ->
                val cur = prefs.currencies.toMutableList()
                if (code in cur) cur.remove(code) else cur.add(code)
                // Порядок показа — как в списке настроек.
                prefs.currencies = cur.sortedBy { order.indexOf(it) }
                r.value = SettingRow.onOff(code in prefs.currencies)
            }
            // Неполную линию добиваем пустым местом, чтобы ячейки были одной ширины.
            repeat(CURRENCIES_PER_LINE - chunk.size) { parent.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
        }

        section("Фото и видео")
        row("Telegram-бот", telegramStatus()) {
            if (!tg.isConfigured) Toast.makeText(this, "Подключается с компьютера, см. README", Toast.LENGTH_LONG).show()
        }
        line {
            row("Из Telegram", "${TelegramSync.photos(this).size} шт.", it) { r ->
                // Двойное нажатие — защита от случайного удаления пультом.
                if (!clearArmed) {
                    clearArmed = true
                    Toast.makeText(this, "Нажмите ещё раз, чтобы удалить все фото и видео из Telegram", Toast.LENGTH_SHORT).show()
                } else {
                    clearArmed = false
                    TelegramSync.clear(this)
                    r.value = "0 шт."
                }
            }
            photosRow = row("Свои фото из папки", photosStatus(), it) { r ->
                if (checkSelfPermission(readPermission) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(readPermission), 1)
                } else {
                    r.value = photosStatus()
                }
            }
        }

        rows.getChildAt(0)?.requestFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        photosRow.value = photosStatus()
    }

    private fun photosStatus(): String {
        if (checkSelfPermission(readPermission) != PackageManager.PERMISSION_GRANTED) return "нет доступа — нажмите OK"
        val n = PhotoSource.userPhotos().size
        return if (n == 0) "папка пуста" else "$n фото"
    }

    /** Строка настроек; в [parent]-линии строки делят ширину поровну. */
    private fun row(title: String, value: String, parent: LinearLayout = rows, onClick: (SettingRow) -> Unit): SettingRow {
        val r = SettingRow(this)
        r.title = title
        r.value = value
        r.setOnClickListener { onClick(r) }
        if (parent === rows) {
            rows.addView(r)
        } else {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.bottomMargin = (r.layoutParams as LinearLayout.LayoutParams).bottomMargin
            if (parent.childCount > 0) lp.marginStart = (6 * resources.displayMetrics.density).toInt()
            parent.addView(r, lp)
        }
        return r
    }

    /** Несколько строк в одну линию — пульт ходит по ним стрелками влево/вправо. */
    private fun line(build: (LinearLayout) -> Unit) {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        rows.addView(l, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        build(l)
    }

    private fun section(title: String) {
        val t = TextView(this)
        t.text = title
        t.textSize = 13f
        t.setTextColor(getColor(R.color.text_faint))
        val dp = resources.displayMetrics.density
        t.setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (4 * dp).toInt())
        rows.addView(t)
    }

    companion object {
        private const val CURRENCIES_PER_LINE = 5
    }
}
