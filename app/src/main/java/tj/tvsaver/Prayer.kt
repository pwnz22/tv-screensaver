package tj.tvsaver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Время намазов по датам: «yyyy-MM-dd» → [Бомдод, Пешин, Аср, Шом, Хуфтан, конец Хуфтана]
 * в минутах от полуночи.
 */
data class PrayerDays(val cityName: String, val days: Map<String, IntArray>, val fetchedAt: Long)

/**
 * Официальное расписание Совета улемов / Исламского центра РТ: shuroiulamo.tj/ru/namaz.
 * Сайт отдаёт таблицу на месяц (POST /ru/namaz/ntime, fmonth/fyear) для Душанбе;
 * в ячейках интервалы «от - до», берём начало. Следующий месяц сайт публикует заранее
 * не всегда — тогда утренний Бомдод берём из конца Хуфтана («19:07 - 05:20» → 05:21).
 */
object Prayer {
    /** Бомдод, Пешин, Аср, Шом, Хуфтан — так их называют в Таджикистане. */
    val NAMES = listOf("Бомдод", "Пешин", "Аср", "Шом", "Хуфтан")

    /** Сдвиг города относительно Душанбе (минуты), как в расписаниях Исламского центра. */
    private val CITY_SHIFT = mapOf(
        "Душанбе" to 0, "Вахдат" to 0, "Турсунзаде" to 0,
        "Худжанд" to -7, "Истаравшан" to -5, "Куляб" to -5,
        "Бохтар" to 4, "Пенджикент" to 5, "Хорог" to -12,
    )

    private val tz = TimeZone.getTimeZone("Asia/Dushanbe")
    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }

    /** Текущий месяц + следующий (если уже опубликован). */
    fun fetch(city: City): PrayerDays {
        val c = Calendar.getInstance(tz)
        val days = LinkedHashMap<String, IntArray>()
        days += fetchMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        c.add(Calendar.MONTH, 1)
        runCatching { days += fetchMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1) }
        if (days.isEmpty()) throw IllegalStateException("shuroiulamo: пустое расписание")
        val shift = CITY_SHIFT[city.name] ?: 0
        val shifted = days.mapValues { (_, t) -> IntArray(t.size) { t[it] + shift } }
        return PrayerDays(city.name, shifted, System.currentTimeMillis())
    }

    private fun fetchMonth(year: Int, month: Int): Map<String, IntArray> {
        val html = Net.post("https://shuroiulamo.tj/ru/namaz/ntime", "fmonth=%02d&fyear=%d&fday=0".format(month, year))
        if (html.trim() == "no") return emptyMap()   // месяц ещё не опубликован
        val result = LinkedHashMap<String, IntArray>()
        val cell = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
        val time = Regex("""(\d{1,2}):(\d{2})""")
        for (row in Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            // День недели | дата | хиджра | Фаджр | Зухр | Аср | макрух | Магриб | Иша
            val cells = cell.findAll(row.groupValues[1]).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
            if (cells.size < 9) continue
            val (dd, mm, yyyy) = cells[1].split(".").takeIf { it.size == 3 } ?: continue
            fun start(i: Int) = time.find(cells[i])?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            fun end(i: Int) = time.findAll(cells[i]).lastOrNull()?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            val t = listOf(start(3), start(4), start(5), start(7), start(8), end(8))
            if (t.any { it == null }) continue
            result["$yyyy-$mm-$dd"] = t.map { it!! }.toIntArray()
        }
        return result
    }

    /** Следующий намаз: имя и «ЧЧ:ММ». null — нет данных на сегодня. */
    fun next(p: PrayerDays, now: Calendar = Calendar.getInstance(tz)): Pair<String, String>? {
        val today = dayFmt().format(now.time)
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val t = p.days[today] ?: return null
        val i = (0 until 5).firstOrNull { t[it] > minutes }
        if (i != null) return NAMES[i] to hm(t[i])
        // После Хуфтана — завтрашний Бомдод (или «конец Хуфтана + 1 мин», если месяц ещё не опубликован).
        val fajr = p.days[plusDays(today, 1)]?.get(0) ?: (t[5] + 1)
        return NAMES[0] to hm(fajr)
    }

    /** Есть расписание на сегодня для этого города. */
    fun hasToday(p: PrayerDays, city: City) = p.cityName == city.name && p.days.containsKey(dayFmt().format(Date()))

    private fun hm(m: Int) = String.format(Locale.US, "%02d:%02d", (m / 60) % 24, m % 60)

    private fun plusDays(day: String, n: Int): String {
        val f = dayFmt()
        val c = Calendar.getInstance(tz).apply { time = f.parse(day)!!; add(Calendar.DAY_OF_MONTH, n) }
        return f.format(c.time)
    }

    // ---- кеш ----

    fun load(context: Context): PrayerDays? = runCatching {
        val j = JSONObject(context.getSharedPreferences("cache", Context.MODE_PRIVATE).getString("prayer2", null) ?: return null)
        val d = j.getJSONObject("days")
        val days = d.keys().asSequence().associateWith { k ->
            val a = d.getJSONArray(k); IntArray(a.length()) { a.getInt(it) }
        }
        PrayerDays(j.getString("city"), days, j.getLong("at"))
    }.getOrNull()

    fun save(context: Context, p: PrayerDays) {
        val d = JSONObject()
        p.days.forEach { (k, v) -> d.put(k, JSONArray(v.toList())) }
        val j = JSONObject().put("city", p.cityName).put("days", d).put("at", p.fetchedAt)
        context.getSharedPreferences("cache", Context.MODE_PRIVATE).edit()
            .remove("prayer")   // старый формат (taqvim.tj)
            .putString("prayer2", j.toString()).apply()
    }
}
