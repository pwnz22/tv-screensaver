package tj.tvsaver

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

/** Настройки и состояние бота: токен, владелец, кому разрешено присылать фото. */
class TelegramStore(context: Context) {
    private val sp = context.getSharedPreferences("telegram", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var botName: String
        get() = sp.getString("bot_name", "")!!
        set(v) = sp.edit().putString("bot_name", v).apply()

    var offset: Long
        get() = sp.getLong("offset", 0)
        set(v) = sp.edit().putLong("offset", v).apply()

    var ownerId: Long
        get() = sp.getLong("owner", 0)
        set(v) = sp.edit().putLong("owner", v).apply()

    /** Код для «/start КОД»: владельцем станет тот, кто знает код с экрана настроек. */
    val ownerCode: String
        get() = sp.getString("owner_code", null) ?: Random.nextInt(1000, 9999).toString()
            .also { sp.edit().putString("owner_code", it).apply() }

    val isConfigured get() = token.isNotEmpty()

    /** Разрешённые отправители: id → имя. */
    var allowed: Map<Long, String>
        get() = readMap("allowed")
        set(v) = writeMap("allowed", v)

    /** Ожидают решения владельца: id → имя. */
    var pending: Map<Long, String>
        get() = readMap("pending")
        set(v) = writeMap("pending", v)

    /** Как подписывать фото человека: id → «Мамы» (на экране «от Мамы»). */
    var labels: Map<Long, String>
        get() = readMap("labels")
        set(v) = writeMap("labels", v)

    /** Ответ бота «✅ Фото на экране» (message_id) → имена файлов: чтобы удалять ответом «удалить». */
    val replies: Map<Long, String> get() = readMap("replies")

    fun addReply(botMessageId: Long, fileName: String) {
        val m = LinkedHashMap(replies)
        m[botMessageId] = listOfNotNull(m[botMessageId], fileName).joinToString(",")
        // Храним только последние записи — старые фото всё равно со временем удаляются.
        writeMap("replies", m.entries.toList().takeLast(600).associate { it.key to it.value })
    }

    var commandsVersion: Int
        get() = sp.getInt("commands_v", 0)
        set(v) = sp.edit().putInt("commands_v", v).apply()

    /** Чью подпись владелец сейчас вводит (0 — никого). */
    var awaitLabelFor: Long
        get() = sp.getLong("await_label", 0)
        set(v) = sp.edit().putLong("await_label", v).apply()

    /** Новый токен = новый бот: сбрасываем владельца, людей и позицию чтения. */
    fun reset(newToken: String) {
        sp.edit().clear().putString("token", newToken.trim()).apply()
    }

    private fun readMap(key: String): Map<Long, String> = runCatching {
        val j = JSONObject(sp.getString(key, "{}")!!)
        j.keys().asSequence().associate { it.toLong() to j.getString(it) }
    }.getOrDefault(emptyMap())

    private fun writeMap(key: String, m: Map<Long, String>) {
        val j = JSONObject()
        m.forEach { (k, v) -> j.put(k.toString(), v) }
        sp.edit().putString(key, j.toString()).apply()
    }
}

/**
 * Забирает сообщения бота и сохраняет фото от разрешённых людей в files/telegram.
 * Рядом с каждым фото лежит .txt с подписью «от Имя · текст».
 */
class TelegramSync(private val context: Context) {
    private val store = TelegramStore(context)
    @Volatile private var api: TelegramApi? = null
    private var lastMediaGroup = ""
    private var lastReplyId = 0L

    fun cancel() { api?.cancel() }

    /**
     * Один опрос Telegram. [live] — заставка на экране (фото покажется сразу).
     * Возвращает новые фото. Бросает исключение при ошибке сети.
     */
    fun pollOnce(timeoutSec: Int, live: Boolean): List<Photo> {
        if (!store.isConfigured) return emptyList()
        val api = TelegramApi(store.token).also { this.api = it }
        ensureCommands(api)
        val updates = api.getUpdates(store.offset, timeoutSec)
        val photos = ArrayList<Photo>()
        for (i in 0 until updates.length()) {
            val u = updates.getJSONObject(i)
            // Сдвигаем позицию сразу: даже если обработка упадёт, не зациклимся на одном сообщении.
            store.offset = u.getLong("update_id") + 1
            try {
                u.optJSONObject("message")?.let { handleMessage(api, it, live)?.let(photos::add) }
                u.optJSONObject("callback_query")?.let { handleCallback(api, it) }
            } catch (t: Throwable) {
                Log.w(TAG, "update failed", t)
            }
        }
        return photos
    }

    private fun handleMessage(api: TelegramApi, msg: JSONObject, live: Boolean): Photo? {
        val chat = msg.getJSONObject("chat")
        if (chat.optString("type") != "private") return null
        val from = msg.getJSONObject("from")
        val uid = from.getLong("id")
        val name = listOf(from.optString("first_name"), from.optString("last_name"))
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Гость" }
        val text = msg.optString("text").trim()
        val isOwner = uid == store.ownerId
        val isAllowed = isOwner || store.allowed.containsKey(uid)

        when {
            isOwner && store.awaitLabelFor != 0L && text.isNotEmpty() && !text.startsWith("/") && text !in KB_ALL ->
                setLabel(api, store.awaitLabelFor, text)
            text.startsWith("/start") -> handleStart(api, uid, name, text.removePrefix("/start").trim())
            isAllowed && (text == "/list" || text == KB_LIST) -> sendList(api, uid, isOwner, 0)
            isOwner && (text == "/people" || text == KB_PEOPLE) -> sendPeople(api, uid)
            isAllowed && text.lowercase() in DELETE_WORDS -> handleDelete(api, uid, isOwner, msg)
            isAllowed && text.isNotEmpty() -> api.sendMessage(uid, HELP, keyboard = keyboardFor(uid))
            text.isNotEmpty() -> api.sendMessage(uid, NO_ACCESS)
            else -> {
                val media = pickMedia(msg) ?: run {
                    if (isAllowed) api.sendMessage(uid, "Пришлите фото или видео — покажу на телевизоре 📺")
                    return null
                }
                if (!isAllowed) { api.sendMessage(uid, NO_ACCESS); return null }
                if (media.size > MAX_BYTES) {
                    api.sendMessage(uid, "⚠️ Файл больше 20 МБ — бот Telegram не может его скачать. " +
                        "Отправьте видео покороче и обычным видео (не файлом): Telegram сам его сожмёт.")
                    return null
                }
                return saveMedia(api, msg, uid, name, media, live)
            }
        }
        return null
    }

    private fun handleStart(api: TelegramApi, uid: Long, name: String, arg: String) {
        when {
            store.ownerId == 0L && arg == store.ownerCode -> {
                store.ownerId = uid
                store.allowed = store.allowed + (uid to name)
                api.sendMessage(uid, "Готово! Вы — владелец фоторамки. Присылайте фото, и они появятся на телевизоре.\n\n" +
                    "Когда боту напишет кто-то ещё, я спрошу у вас разрешения. Список людей: /people",
                    keyboard = keyboardFor(uid))
                runCatching { setOwnerCommands(api) }
                askLabel(api, uid)
            }
            store.ownerId == 0L ->
                api.sendMessage(uid, "Фоторамка ещё не настроена. Отправьте /start и код из настроек заставки на телевизоре.")
            uid == store.ownerId || store.allowed.containsKey(uid) ->
                api.sendMessage(uid, "Вы уже можете присылать фото 📷", keyboard = keyboardFor(uid))
            store.pending.containsKey(uid) ->
                api.sendMessage(uid, "Запрос уже у владельца рамки, подождите немного.")
            else -> {
                store.pending = store.pending + (uid to name)
                api.sendMessage(uid, "Запрос отправлен владельцу рамки. Как только он разрешит — присылайте фото.")
                api.sendMessage(store.ownerId, "$name хочет присылать фото на телевизор.",
                    listOf(listOf("✅ Разрешить" to "a:$uid", "Отклонить" to "d:$uid")))
            }
        }
    }

    private fun handleCallback(api: TelegramApi, cb: JSONObject) {
        val id = cb.getString("id")
        val from = cb.getJSONObject("from").getLong("id")
        val isOwner = from == store.ownerId
        val data = cb.optString("data")
        val arg = data.substringAfter(':')
        when (data.substringBefore(':')) {
            "x" -> { deleteFromList(api, cb, from, isOwner, arg); return }
            "p" -> {
                api.answerCallback(id, "")
                if (isOwner || store.allowed.containsKey(from)) sendList(api, from, isOwner, arg.toIntOrNull() ?: 0)
                return
            }
        }
        if (!isOwner) {
            api.answerCallback(id, "Это может сделать только владелец рамки"); return
        }
        val uid = data.substringAfter(':').toLongOrNull() ?: return
        when (data.substringBefore(':')) {
            "a" -> {
                val name = store.pending[uid] ?: "Гость"
                store.pending = store.pending - uid
                store.allowed = store.allowed + (uid to name)
                api.answerCallback(id, "$name добавлен(а)")
                api.sendMessage(store.ownerId, "✅ $name теперь может присылать фото.")
                api.sendMessage(uid, "Вам разрешили присылать фото на телевизор. Присылайте! 📷", keyboard = keyboardFor(uid))
                askLabel(api, uid)
            }
            "n" -> {
                if (!store.allowed.containsKey(uid)) return
                api.answerCallback(id, "Напишите подпись")
                askLabel(api, uid)
            }
            "d" -> {
                store.pending = store.pending - uid
                api.answerCallback(id, "Отклонено")
            }
            "r" -> {
                val name = store.allowed[uid] ?: return
                store.allowed = store.allowed - uid
                store.labels = store.labels - uid
                api.answerCallback(id, "$name удалён(а)")
                api.sendMessage(store.ownerId, "$name больше не может присылать фото.")
            }
        }
    }

    /** Свои фото/видео (владельцу — все), новые сверху, по 10 штук; под каждым — «🗑 Удалить». */
    private fun sendList(api: TelegramApi, uid: Long, isOwner: Boolean, offset: Int) {
        val items = mediaFiles(folder(context)).sortedByDescending { it.lastModified() }
            .filter { isOwner || meta(it).sender == uid }
        if (items.isEmpty()) {
            api.sendMessage(uid, if (isOwner) "На телевизоре пока нет фото из Telegram." else "На телевизоре пока нет ваших фото.")
            return
        }
        val page = items.drop(offset).take(PAGE)
        for (f in page) {
            val m = meta(f)
            val caption = listOfNotNull(m.label, shownDate(f.name)).joinToString("\n")
            val rows = listOf(listOf("🗑 Удалить" to "x:${f.name}"))
            try {
                if (m.fileId != null) {
                    api.sendMedia(uid, m.kind, m.fileId, caption, rows)
                } else if (!Photo.isVideo(f)) {
                    // Фото из ранних версий: один раз загружаем с ТВ и запоминаем file_id.
                    val sent = api.uploadMedia(uid, "photo", f, caption, rows)
                    sent.optJSONArray("photo")?.let { sizes ->
                        if (sizes.length() > 0) writeMeta(f, m.copy(fileId = sizes.getJSONObject(sizes.length() - 1).getString("file_id"), kind = "photo"))
                    }
                } else {
                    // Старые видео (до этой версии) не перезаливаем — слишком тяжело для ТВ.
                    api.sendMessage(uid, "🎬 Видео · $caption", rows)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "list item failed: ${f.name}", t)
                api.sendMessage(uid, "📄 ${f.name} · $caption", rows)
            }
        }
        val shown = offset + page.size
        val more = if (shown < items.size) listOf(listOf("Ещё ▶" to "p:$shown")) else null
        api.sendMessage(uid, "Показано ${offset + 1}–$shown из ${items.size}.", more)
    }

    private fun deleteFromList(api: TelegramApi, cb: JSONObject, from: Long, isOwner: Boolean, name: String) {
        val id = cb.getString("id")
        val message = cb.optJSONObject("message")
        val chatId = message?.getJSONObject("chat")?.getLong("id") ?: from
        val f = File(folder(context), name)
        if (name.contains('/') || !f.exists()) {
            api.answerCallback(id, "Уже удалено")
            message?.let { api.deleteMessage(chatId, it.getLong("message_id")) }
            return
        }
        if (!isOwner && meta(f).sender != from) { api.answerCallback(id, "Удалить можно только своё"); return }
        f.delete(); File(f.path + ".txt").delete()
        api.answerCallback(id, "🗑 Удалено с телевизора")
        message?.let { api.deleteMessage(chatId, it.getLong("message_id")) }
    }

    /** «20261020-221501-123.jpg» → «20.10 22:15». */
    private fun shownDate(name: String): String? =
        Regex("""^\d{4}(\d{2})(\d{2})-(\d{2})(\d{2})""").find(name)?.destructured?.let { (mm, dd, hh, mi) -> "$dd.$mm $hh:$mi" }

    /** Подпись, автор, file_id и тип — хранятся рядом с файлом в .txt (по строке). */
    private data class Meta(val label: String?, val sender: Long?, val fileId: String?, val kind: String)

    private fun meta(f: File): Meta {
        val lines = runCatching { File(f.path + ".txt").readLines() }.getOrDefault(emptyList())
        return Meta(
            lines.getOrNull(0), lines.getOrNull(1)?.toLongOrNull(), lines.getOrNull(2)?.takeIf { it.isNotBlank() },
            lines.getOrNull(3)?.takeIf { it.isNotBlank() } ?: if (Photo.isVideo(f)) "video" else "photo",
        )
    }

    private fun writeMeta(f: File, m: Meta) {
        File(f.path + ".txt").writeText(listOf(m.label ?: "", m.sender?.toString() ?: "", m.fileId ?: "", m.kind).joinToString("\n"))
    }

    private fun keyboardFor(uid: Long): List<List<String>> =
        if (uid == store.ownerId) listOf(listOf(KB_LIST, KB_PEOPLE), listOf(KB_HELP)) else listOf(listOf(KB_LIST, KB_HELP))

    /** Меню команд (кнопка «Меню» слева от поля ввода) — один раз на версию. */
    private fun ensureCommands(api: TelegramApi) {
        if (store.commandsVersion == COMMANDS_VERSION) return
        runCatching {
            api.setMyCommands(listOf("list" to "Мои фото и видео на ТВ", "help" to "Помощь"))
            setOwnerCommands(api)
            store.commandsVersion = COMMANDS_VERSION
        }.onFailure { Log.w(TAG, "setMyCommands failed", it) }
    }

    private fun setOwnerCommands(api: TelegramApi) {
        if (store.ownerId == 0L) return
        api.setMyCommands(listOf("list" to "Все фото и видео на ТВ", "people" to "Кто может присылать фото", "help" to "Помощь"), store.ownerId)
    }

    private fun sendPeople(api: TelegramApi, owner: Long) {
        val lines = store.allowed.map { (id, n) ->
            val who = if (id == owner) "вы" else n
            "• $who — на экране «${label(id, n)}»"
        }
        val text = "Присылать фото могут:\n" + lines.joinToString("\n") +
            "\n\nНовые люди: пусть отправят боту /start."
        val rows = store.allowed.map { (id, n) ->
            val who = if (id == owner) "мою" else n
            buildList {
                add("✏️ Подпись: $who" to "n:$id")
                if (id != owner) add("Убрать" to "r:$id")
            }
        }
        api.sendMessage(owner, text, rows)
    }

    /** Спрашиваем владельца, как подписывать фото человека [uid]. */
    private fun askLabel(api: TelegramApi, uid: Long) {
        store.awaitLabelFor = uid
        val who = if (uid == store.ownerId) "ваши фото" else "фото от ${store.allowed[uid] ?: "этого человека"}"
        api.sendMessage(store.ownerId, "Как подписывать $who на экране? Напишите одно-два слова после «от», " +
            "например: Мамы, Папы, бабушки.\nОтправьте «-», чтобы показывать имя из Telegram.")
    }

    private fun setLabel(api: TelegramApi, uid: Long, text: String) {
        store.awaitLabelFor = 0
        val value = text.trim().removePrefix("от ").trim().take(30)
        store.labels = if (value == "-" || value.isEmpty()) store.labels - uid else store.labels + (uid to value)
        api.sendMessage(store.ownerId, "Готово: на экране будет «${label(uid, store.allowed[uid] ?: "")}».")
    }

    /** «аз Модар» (на экране по-таджикски) — подпись владельца, иначе имя из профиля. */
    private fun label(uid: Long, profileName: String) = "аз " + (store.labels[uid] ?: profileName)

    private class Media(val fileId: String, val ext: String, val size: Long, val kind: String) {
        val isVideo get() = ext == "mp4" || ext == "mov"
    }

    /**
     * Что прислали: видео / GIF / «кружочек» (Telegram отдаёт их как MP4), фото (берём самый
     * большой вариант) или картинку/видео файлом.
     */
    private fun pickMedia(msg: JSONObject): Media? {
        fun of(o: JSONObject, ext: String, kind: String) = Media(o.getString("file_id"), ext, o.optLong("file_size", 0), kind)
        for (key in listOf("video", "animation", "video_note")) {
            msg.optJSONObject(key)?.let { return of(it, if (it.optString("mime_type") == "video/quicktime") "mov" else "mp4", key) }
        }
        msg.optJSONArray("photo")?.let { sizes ->
            if (sizes.length() > 0) return of(sizes.getJSONObject(sizes.length() - 1), "jpg", "photo")
        }
        msg.optJSONObject("document")?.let { doc ->
            val ext = when (doc.optString("mime_type")) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                "video/mp4" -> "mp4"; "video/quicktime" -> "mov"
                else -> return null
            }
            return of(doc, ext, "document")
        }
        return null
    }

    private fun saveMedia(
        api: TelegramApi, msg: JSONObject, uid: Long, name: String, media: Media, live: Boolean,
    ): Photo? {
        val dir = folder(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(dir, "$stamp-${msg.getLong("message_id")}.${media.ext}")
        val what = if (media.isVideo) "Видео" else "Фото"
        val group = msg.optString("media_group_id")
        // В альбоме отвечаем один раз, а не на каждое фото.
        val reply = group.isEmpty() || group != lastMediaGroup
        lastMediaGroup = group
        try {
            api.download(media.fileId, dest, MAX_BYTES)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            api.sendMessage(uid, "⚠️ Не получилось скачать ${what.lowercase()}, отправьте ещё раз.")
            return null
        }
        val caption = msg.optString("caption").trim().take(80)
        val label = label(uid, store.allowed[uid] ?: name) + if (caption.isNotEmpty()) " · $caption" else ""
        // Подпись и кто прислал (удалить может только автор или владелец).
        writeMeta(dest, Meta(label, uid, media.fileId, media.kind))
        trim(dir)
        if (reply) {
            lastReplyId = api.sendMessage(uid, (if (live) "✅ $what на экране" else "✅ Сохранено — появится в заставке") +
                "\nЧтобы убрать с ТВ — ответьте на это сообщение словом «удалить».")
            store.addReply(lastReplyId, dest.name)
        } else if (lastReplyId != 0L) {
            store.addReply(lastReplyId, dest.name)   // остальные фото альбома — к тому же ответу
        }
        return Photo.of(dest, label)
    }

    /**
     * «удалить» в ответ на своё фото/видео или на ответ бота «✅ …» — убирает его с телевизора.
     * Удалить может автор или владелец рамки.
     */
    private fun handleDelete(api: TelegramApi, uid: Long, isOwner: Boolean, msg: JSONObject) {
        val target = msg.optJSONObject("reply_to_message") ?: run {
            api.sendMessage(uid, "Ответьте словом «удалить» на фото (или на моё «✅ …» под ним) — и я уберу его с телевизора.")
            return
        }
        val id = target.getLong("message_id")
        val dir = folder(context)
        val files = store.replies[id]?.split(',')?.map { File(dir, it) }
            ?: mediaFiles(dir).filter { it.name.contains("-$id.") }
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) { api.sendMessage(uid, "Не нашёл это на телевизоре — возможно, уже удалено."); return }
        var removed = 0
        for (f in existing) {
            val sender = runCatching { File(f.path + ".txt").readLines().getOrNull(1)?.toLong() }.getOrNull()
            if (!isOwner && sender != uid) continue
            f.delete(); File(f.path + ".txt").delete(); removed++
        }
        api.sendMessage(uid, when {
            removed == 0 -> "Удалить можно только своё фото (или владельцу рамки)."
            removed == 1 -> "🗑 Удалено с телевизора."
            else -> "🗑 Удалено с телевизора: $removed шт."
        })
    }

    /** Храним не больше MAX_PHOTOS фото и MAX_VIDEOS видео: самые старые удаляем вместе с подписями. */
    private fun trim(dir: File) {
        val (videos, photos) = mediaFiles(dir).sortedBy { it.lastModified() }.partition { Photo.isVideo(it) }
        val extra = photos.take((photos.size - MAX_PHOTOS).coerceAtLeast(0)) +
            videos.take((videos.size - MAX_VIDEOS).coerceAtLeast(0))
        extra.forEach { it.delete(); File(it.path + ".txt").delete() }
    }

    companion object {
        private const val TAG = "tvsaver"
        const val MAX_PHOTOS = 300
        const val MAX_VIDEOS = 30
        /** Больше Bot API скачать не даёт. */
        const val MAX_BYTES = 20L * 1024 * 1024

        /** Один опрос за раз: заставка и фоновая проверка не должны мешать друг другу (409 Conflict). */
        val lock = ReentrantLock()

        fun folder(context: Context) = File(context.filesDir, "telegram")

        fun mediaFiles(dir: File): List<File> =
            (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".txt") && !it.name.endsWith(".part") }

        /** Все фото и видео из Telegram с подписями. */
        fun photos(context: Context): List<Photo> = mediaFiles(folder(context)).map {
            Photo.of(it, runCatching { File(it.path + ".txt").readLines().firstOrNull() }.getOrNull())
        }

        fun clear(context: Context) {
            folder(context).listFiles()?.forEach { it.delete() }
        }

        private const val HELP = "Присылайте фото или короткие видео (до 20 МБ) — они появятся на телевизоре. " +
            "Можно добавить подпись. Видео показываются без звука.\n" +
            "Список ваших фото с кнопкой «Удалить» — «📷 Фото на ТВ» внизу или /list.\n" +
            "Ещё можно ответить на фото словом «удалить»."
        private const val PAGE = 10
        private const val COMMANDS_VERSION = 1
        private const val KB_LIST = "📷 Фото на ТВ"
        private const val KB_PEOPLE = "👥 Люди"
        private const val KB_HELP = "❓ Помощь"
        private val KB_ALL = setOf(KB_LIST, KB_PEOPLE, KB_HELP)
        private val DELETE_WORDS = setOf("удалить", "удали", "удалить фото", "удалить видео", "delete", "/delete", "🗑")
        private const val NO_ACCESS = "У вас пока нет доступа. Отправьте /start — я спрошу разрешения у владельца."
    }
}
