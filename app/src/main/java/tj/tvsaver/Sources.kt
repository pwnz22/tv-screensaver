package tj.tvsaver

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Net {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "tvsaver/1.0 (Android TV screensaver)")
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code: $url")
            // НБТ объявляет windows-1251, но реально отдаёт UTF-8 — читаем всё как UTF-8.
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** POST application/x-www-form-urlencoded. */
    fun post(url: String, form: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) tvsaver/1.0")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write(form.toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code: $url")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}

object WeatherSource {
    fun fetch(city: City): Weather {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1"
        val j = JSONObject(Net.get(url))
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        return Weather(
            temp = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            min = daily.getJSONArray("temperature_2m_min").getDouble(0),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lat = city.lat,
            lon = city.lon,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

object RatesSource {
    const val NBT = "НБТ"
    const val FALLBACK = "open.er-api.com"

    /** Сначала официальный курс Нацбанка, при ошибке — запасной источник. */
    fun fetch(): Rates = runCatching { fetchNbt() }.getOrElse { fetchFallback() }

    /**
     * Ежедневный XML Нацбанка Таджикистана:
     * <ValCurs Date="2026-10-17"><Valute ID="840"><CharCode>USD</CharCode>
     * <Nominal>1</Nominal><Value>9.2395</Value></Valute>...
     * Без параметра date сервер отвечает 500; на выходные и будущие даты
     * возвращает последний действующий курс.
     */
    fun fetchNbt(): Rates {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        df.timeZone = TimeZone.getTimeZone("Asia/Dushanbe")
        val today = df.format(Date())
        val body = Net.get("https://nbt.tj/ru/kurs/export_xml.php?date=$today&export=xmlout")
        // Убираем <?xml ... encoding="windows-1251"?>, чтобы парсер не перекодировал текст.
        val xml = body.substringAfter("?>", body)

        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        val map = HashMap<String, Double>()
        var date = ""
        var code = ""
        var nominal = 1.0
        var value = 0.0
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    when (tag) {
                        "ValCurs" -> date = p.getAttributeValue(null, "Date") ?: ""
                        "Valute" -> { code = ""; nominal = 1.0; value = 0.0 }
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text.trim().replace(',', '.')
                    when (tag) {
                        "CharCode" -> code = t.uppercase()
                        "Nominal" -> nominal = t.toDoubleOrNull() ?: 1.0
                        "Value" -> value = t.toDoubleOrNull() ?: 0.0
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "Valute" && code.length == 3 && value > 0 && nominal > 0) {
                        map[code] = value / nominal
                    }
                    tag = ""
                }
            }
        }
        if (map["USD"] == null) throw IllegalStateException("НБТ: в ответе нет USD")
        return Rates(map, NBT, date, System.currentTimeMillis())
    }

    /** open.er-api.com: rates[X] = сколько X за 1 TJS → переворачиваем. */
    fun fetchFallback(): Rates {
        val j = JSONObject(Net.get("https://open.er-api.com/v6/latest/TJS"))
        if (j.optString("result") != "success") throw IllegalStateException("er-api: ${j.optString("result")}")
        val r = j.getJSONObject("rates")
        val map = HashMap<String, Double>()
        for (k in r.keys()) {
            val v = r.getDouble(k)
            if (v > 0 && k != "TJS") map[k] = 1.0 / v
        }
        val date = runCatching {
            val utc = j.getLong("time_last_update_unix") * 1000
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(utc))
        }.getOrDefault("")
        return Rates(map, FALLBACK, date, System.currentTimeMillis())
    }
}
