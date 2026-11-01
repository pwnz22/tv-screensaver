package tj.tvsaver

import android.content.Context

/** Какие фото/видео уже показывались — чтобы новые шли первыми. Храним имена файлов. */
class SeenPhotos(context: Context) {
    private val sp = context.getSharedPreferences("seen", Context.MODE_PRIVATE)
    private var names: MutableSet<String> = HashSet(sp.getStringSet(KEY, emptySet())!!)

    fun contains(name: String) = name in names

    fun add(name: String) {
        if (names.add(name)) save()
    }

    /** Забываем удалённые файлы, чтобы список не рос бесконечно. */
    fun prune(existing: Collection<String>) {
        if (names.retainAll(existing.toSet())) save()
    }

    private fun save() {
        sp.edit().putStringSet(KEY, HashSet(names)).apply()
    }

    private companion object { const val KEY = "names" }
}
