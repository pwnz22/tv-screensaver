package tj.tvsaver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Минимальный клиент Telegram Bot API на HttpURLConnection (без библиотек). */
class TelegramApi(private val token: String) {

    /** Текущий запрос — чтобы прервать долгий getUpdates, когда заставка закрывается. */
    @Volatile private var current: HttpURLConnection? = null

    fun cancel() {
        current?.disconnect()
    }

    fun getMe(): JSONObject = call("getMe")

    /** Долгий опрос: сервер держит соединение до [timeoutSec] секунд, пока не придёт новое. */
    fun getUpdates(offset: Long, timeoutSec: Int): JSONArray {
        val r = request(
            "getUpdates",
            mapOf(
                "offset" to offset.toString(),
                "timeout" to timeoutSec.toString(),
                "allowed_updates" to """["message","callback_query"]""",
            ),
            readTimeoutMs = (timeoutSec + 15) * 1000,
        )
        return r.getJSONArray("result")
    }

    /**
     * [rows] — кнопки под сообщением («текст → callback_data»);
     * [keyboard] — постоянные кнопки над клавиатурой (их текст приходит обычным сообщением).
     * Возвращает message_id.
     */
    fun sendMessage(
        chatId: Long, text: String,
        rows: List<List<Pair<String, String>>>? = null,
        keyboard: List<List<String>>? = null,
    ): Long {
        val params = mutableMapOf("chat_id" to chatId.toString(), "text" to text)
        markup(rows, keyboard)?.let { params["reply_markup"] = it }
        return request("sendMessage", params).getJSONObject("result").optLong("message_id")
    }

    /** Повторная отправка уже загруженного в Telegram файла по file_id (без загрузки). */
    fun sendMedia(chatId: Long, kind: String, fileId: String, caption: String?, rows: List<List<Pair<String, String>>>?): JSONObject {
        val (method, field) = mediaMethod(kind)
        val params = mutableMapOf("chat_id" to chatId.toString(), field to fileId)
        if (kind != "video_note" && caption != null) params["caption"] = caption
        markup(rows, null)?.let { params["reply_markup"] = it }
        return request(method, params).getJSONObject("result")
    }

    /** Загрузка файла с ТВ (multipart) — для фото, у которых ещё нет file_id. */
    fun uploadMedia(chatId: Long, kind: String, file: File, caption: String?, rows: List<List<Pair<String, String>>>?): JSONObject {
        val (method, field) = mediaMethod(kind)
        val boundary = "tvsaver" + System.nanoTime()
        val c = open("https://api.telegram.org/bot$token/$method", 120_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.buffered().use { out ->
                fun text(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                text("chat_id", chatId.toString())
                if (kind != "video_note" && caption != null) text("caption", caption)
                markup(rows, null)?.let { text("reply_markup", it) }
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val j = runCatching { JSONObject(body) }.getOrNull()
            if (j == null || !j.optBoolean("ok")) throw TelegramException(code, j?.optString("description") ?: "HTTP $code")
            return j.getJSONObject("result")
        } finally {
            c.disconnect()
            current = null
        }
    }

    fun deleteMessage(chatId: Long, messageId: Long): Boolean = runCatching {
        request("deleteMessage", mapOf("chat_id" to chatId.toString(), "message_id" to messageId.toString()))
    }.isSuccess

    /** Меню команд (кнопка «Меню» слева от поля ввода). [chatId] — только для этого чата (владельцу). */
    fun setMyCommands(commands: List<Pair<String, String>>, chatId: Long? = null) {
        val list = JSONArray()
        commands.forEach { (cmd, desc) -> list.put(JSONObject().put("command", cmd).put("description", desc)) }
        val params = mutableMapOf("commands" to list.toString())
        if (chatId != null) params["scope"] = JSONObject().put("type", "chat").put("chat_id", chatId).toString()
        request("setMyCommands", params)
    }

    private fun mediaMethod(kind: String) = when (kind) {
        "video" -> "sendVideo" to "video"
        "animation" -> "sendAnimation" to "animation"
        "video_note" -> "sendVideoNote" to "video_note"
        "document" -> "sendDocument" to "document"
        else -> "sendPhoto" to "photo"
    }

    private fun markup(rows: List<List<Pair<String, String>>>?, keyboard: List<List<String>>?): String? = when {
        !rows.isNullOrEmpty() -> {
            val kb = JSONArray()
            for (r in rows) {
                val row = JSONArray()
                r.forEach { (title, data) -> row.put(JSONObject().put("text", title).put("callback_data", data)) }
                kb.put(row)
            }
            JSONObject().put("inline_keyboard", kb).toString()
        }
        !keyboard.isNullOrEmpty() -> {
            val kb = JSONArray()
            keyboard.forEach { r -> kb.put(JSONArray().apply { r.forEach { put(JSONObject().put("text", it)) } }) }
            JSONObject().put("keyboard", kb).put("resize_keyboard", true).put("is_persistent", true).toString()
        }
        else -> null
    }

    fun answerCallback(id: String, text: String) {
        request("answerCallbackQuery", mapOf("callback_query_id" to id, "text" to text))
    }

    /** Скачивает файл (до 20 МБ — ограничение Bot API) во [dest]. */
    fun download(fileId: String, dest: File, maxBytes: Long = 20L * 1024 * 1024) {
        val path = call("getFile", mapOf("file_id" to fileId)).getString("file_path")
        val c = open("https://api.telegram.org/file/bot$token/$path", 60_000)
        try {
            if (c.responseCode != 200) throw IOException("download HTTP ${c.responseCode}")
            val tmp = File(dest.path + ".part")
            var total = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("файл больше ${maxBytes / 1024 / 1024} МБ")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IOException("не удалось сохранить $dest")
        } finally {
            c.disconnect()
            current = null
        }
    }

    private fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject =
        request(method, params).getJSONObject("result")

    private fun request(method: String, params: Map<String, String>, readTimeoutMs: Int = 20_000): JSONObject {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val c = open("https://api.telegram.org/bot$token/$method?$query", readTimeoutMs)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val j = runCatching { JSONObject(body) }.getOrNull()
            if (j == null || !j.optBoolean("ok")) {
                // Не пишем URL в ошибку — в нём токен.
                throw TelegramException(code, j?.optString("description") ?: "HTTP $code")
            }
            return j
        } finally {
            c.disconnect()
            current = null
        }
    }

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = readTimeoutMs
        current = c
        return c
    }
}

class TelegramException(val code: Int, message: String) : IOException("Telegram: $message")
