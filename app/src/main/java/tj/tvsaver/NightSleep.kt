package tj.tvsaver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Ночное автовыключение: с 22:00 до 07:00 системное «время до сна» (Settings.Secure
 * sleep_timeout) ставится на N часов — если никто не трогает пульт, Android сам переводит ТВ
 * в режим ожидания. Днём возвращается прежнее значение. Видео в плеерах это не прерывает:
 * пока они играют, Android не засыпает.
 *
 * Нужно разрешение, которое выдаётся только через adb:
 *   adb shell pm grant tj.tvsaver android.permission.WRITE_SECURE_SETTINGS
 */
object NightSleep {
    private const val KEY = "sleep_timeout"
    private const val NIGHT_FROM = 22
    private const val NIGHT_TO = 7
    const val DEFAULT_DAY_MS = 24 * 60 * 60_000
    private const val MAX_NIGHT_MS = 3 * 60 * 60_000

    fun canWrite(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isNight(cal: Calendar = Calendar.getInstance()): Boolean {
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return h >= NIGHT_FROM || h < NIGHT_TO
    }

    /** Выставляет «время до сна» по времени суток. Безопасно вызывать часто. */
    fun apply(context: Context) {
        if (!canWrite(context)) return
        val prefs = Prefs(context)
        val cr = context.contentResolver
        val current = Settings.Secure.getInt(cr, KEY, DEFAULT_DAY_MS)
        val nightMs = prefs.nightSleepHours * 60 * 60_000
        // Запоминаем «дневное» значение (не наше ночное: ночные — не дольше 3 часов).
        if (current > MAX_NIGHT_MS) prefs.daySleepMs = current
        val want = if (prefs.nightSleepHours > 0 && isNight()) nightMs else prefs.daySleepMs
        if (want != current) {
            runCatching { Settings.Secure.putInt(cr, KEY, want) }
                .onSuccess { Log.i("tvsaver", "sleep_timeout $current → $want") }
                .onFailure { Log.w("tvsaver", "sleep_timeout write failed", it) }
        }
    }
}
