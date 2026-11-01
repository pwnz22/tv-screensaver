package tj.tvsaver

import java.util.Calendar

/** Дата по-таджикски: «Сешанбе, 20 октябр». Названия заданы явно — таджикской локали на ТВ может не быть. */
object TajikDate {
    // Calendar.SUNDAY = 1 … SATURDAY = 7
    private val DAYS = arrayOf("Якшанбе", "Душанбе", "Сешанбе", "Чоршанбе", "Панҷшанбе", "Ҷумъа", "Шанбе")
    private val MONTHS = arrayOf(
        "январ", "феврал", "март", "апрел", "май", "июн",
        "июл", "август", "сентябр", "октябр", "ноябр", "декабр",
    )

    fun format(cal: Calendar = Calendar.getInstance()): String =
        "${DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]}, ${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]}"
}
