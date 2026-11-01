package tj.tvsaver

import android.content.Context
import android.content.SharedPreferences

data class City(val name: String, val lat: Double, val lon: Double)

/** Пользовательские настройки заставки. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var intervalSec: Int
        get() = sp.getInt("interval", 30)
        set(v) = sp.edit().putInt("interval", v).apply()

    var showWeather: Boolean
        get() = sp.getBoolean("show_weather", true)
        set(v) = sp.edit().putBoolean("show_weather", v).apply()

    var showRates: Boolean
        get() = sp.getBoolean("show_rates", true)
        set(v) = sp.edit().putBoolean("show_rates", v).apply()

    var showPrayer: Boolean
        get() = sp.getBoolean("show_prayer", true)
        set(v) = sp.edit().putBoolean("show_prayer", v).apply()

    /** Ночью (22:00–07:00) уходить в режим ожидания после N часов без пульта; 0 — выключено. */
    var nightSleepHours: Int
        get() = sp.getInt("night_sleep_h", 2)
        set(v) = sp.edit().putInt("night_sleep_h", v).apply()

    /** «Время до сна» днём — прежнее системное значение, его возвращаем утром. */
    var daySleepMs: Int
        get() = sp.getInt("day_sleep_ms", NightSleep.DEFAULT_DAY_MS)
        set(v) = sp.edit().putInt("day_sleep_ms", v).apply()

    /** Коды валют в порядке показа, например ["USD", "RUB"]. */
    var currencies: List<String>
        get() = sp.getString("currencies", DEFAULT_CURRENCIES)!!
            .split(',').map { it.trim().uppercase() }.filter { it.length == 3 }
        set(v) = sp.edit().putString("currencies", v.joinToString(",")).apply()

    var city: City
        get() = City(
            sp.getString("city_name", CITIES[0].name)!!,
            sp.getFloat("city_lat", CITIES[0].lat.toFloat()).toDouble(),
            sp.getFloat("city_lon", CITIES[0].lon.toFloat()).toDouble(),
        )
        set(c) = sp.edit()
            .putString("city_name", c.name)
            .putFloat("city_lat", c.lat.toFloat())
            .putFloat("city_lon", c.lon.toFloat())
            .apply()

    companion object {
        const val DEFAULT_CURRENCIES = "USD,RUB"

        val INTERVALS = intArrayOf(10, 15, 30, 60, 120, 300)
        val NIGHT_SLEEP_CHOICES = intArrayOf(0, 1, 2, 3)

        /** Валюты, которые можно включить в настройках (все есть в фиде НБТ). */
        val CURRENCY_CHOICES = listOf("USD", "RUB", "EUR", "CNY", "KZT", "UZS", "KGS", "GBP", "TRY", "AED")

        /** Названия городов по-таджикски — для экрана заставки. */
        private val TJ_NAMES = mapOf(
            "Худжанд" to "Хуҷанд", "Куляб" to "Кӯлоб", "Пенджикент" to "Панҷакент",
            "Турсунзаде" to "Турсунзода", "Вахдат" to "Ваҳдат", "Хорог" to "Хоруғ",
        )

        fun tjName(city: City) = TJ_NAMES[city.name] ?: city.name

        val CITIES = listOf(
            City("Душанбе", 38.5598, 68.7870),
            City("Худжанд", 40.2826, 69.6222),
            City("Бохтар", 37.8364, 68.7806),
            City("Куляб", 37.9146, 69.7845),
            City("Истаравшан", 39.9108, 69.0064),
            City("Пенджикент", 39.4952, 67.6093),
            City("Турсунзаде", 38.5108, 68.2303),
            City("Вахдат", 38.5563, 69.0135),
            City("Хорог", 37.4897, 71.5530),
        )
    }
}
