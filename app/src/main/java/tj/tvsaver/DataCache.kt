package tj.tvsaver

import android.content.Context
import org.json.JSONObject

data class Weather(
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val min: Double,
    val max: Double,
    val lat: Double,
    val lon: Double,
    val fetchedAt: Long,
)

data class Rates(
    /** Сколько сомони стоит 1 единица валюты. */
    val perUnit: Map<String, Double>,
    /** «НБТ» или «open.er-api.com». */
    val source: String,
    /** Дата курса из ответа источника (ГГГГ-ММ-ДД), может быть пустой. */
    val date: String,
    val fetchedAt: Long,
)

/** Последние полученные значения — показываем их, пока нет сети. */
class DataCache(context: Context) {
    private val sp = context.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun loadWeather(): Weather? = runCatching {
        val j = JSONObject(sp.getString("weather", null) ?: return null)
        Weather(
            j.getDouble("temp"), j.getInt("code"), j.getBoolean("isDay"),
            j.getDouble("min"), j.getDouble("max"),
            j.getDouble("lat"), j.getDouble("lon"), j.getLong("at"),
        )
    }.getOrNull()

    fun saveWeather(w: Weather) {
        val j = JSONObject()
            .put("temp", w.temp).put("code", w.code).put("isDay", w.isDay)
            .put("min", w.min).put("max", w.max)
            .put("lat", w.lat).put("lon", w.lon).put("at", w.fetchedAt)
        sp.edit().putString("weather", j.toString()).apply()
    }

    fun loadRates(): Rates? = runCatching {
        val j = JSONObject(sp.getString("rates", null) ?: return null)
        val r = j.getJSONObject("rates")
        val map = r.keys().asSequence().associateWith { r.getDouble(it) }
        Rates(map, j.getString("source"), j.optString("date"), j.getLong("at"))
    }.getOrNull()

    fun saveRates(rates: Rates) {
        val r = JSONObject()
        rates.perUnit.forEach { (k, v) -> r.put(k, v) }
        val j = JSONObject()
            .put("rates", r).put("source", rates.source)
            .put("date", rates.date).put("at", rates.fetchedAt)
        sp.edit().putString("rates", j.toString()).apply()
    }
}
