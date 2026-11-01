package tj.tvsaver

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Когда заставки нет на экране: раз в ~20 минут забираем фото из Telegram,
 * чтобы они не потерялись (Telegram хранит неполученные сообщения боту лишь 24 часа).
 * Это системный JobScheduler — постоянной службы нет, процесс живёт секунды.
 */
class TelegramJob : JobService() {
    @Volatile private var worker: Thread? = null
    @Volatile private var sync: TelegramSync? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // Заодно держим ночное/дневное «время до сна» в нужном значении (см. NightSleep).
        NightSleep.apply(this)
        // Заставка на экране — она сама опрашивает бота.
        if (SaverDream.isDreaming || !TelegramStore(this).isConfigured) return false
        val s = TelegramSync(this).also { sync = it }
        worker = Thread({
            var retry = false
            if (TelegramSync.lock.tryLock()) {
                try {
                    // Короткий опрос (без ожидания), но до 5 пачек, если накопилось много.
                    for (i in 0 until 5) {
                        if (s.pollOnce(timeoutSec = 0, live = false).isEmpty()) break
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "telegram job failed", t)
                    retry = true
                } finally {
                    TelegramSync.lock.unlock()
                }
            }
            jobFinished(params, retry)
        }, "tvsaver-telegram-job").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        sync?.cancel()
        worker?.interrupt()
        return true
    }

    companion object {
        private const val TAG = "tvsaver"
        private const val JOB_ID = 1001
        private const val PERIOD_MS = 20 * 60_000L

        /** Ставит периодическую проверку (повторный вызов ничего не дублирует). */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TelegramJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)   // переживает перезагрузку ТВ
                .build()
            js.schedule(job)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
