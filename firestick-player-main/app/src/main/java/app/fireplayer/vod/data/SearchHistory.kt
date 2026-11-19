package app.fireplayer.vod.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * The Search tab's history, kept on this TV only (its own small file): the searches made, newest first, at most
 * [SearchHistoryRules.MAX]. Main thread; reads and writes are a few bytes.
 */
class SearchHistory(private val prefs: SharedPreferences) {

    /** The searches, newest first. */
    var entries: List<String> = SearchHistoryRules.read(prefs.getString(KEY, null))
        private set

    /** [query] made (submitted, or a result opened from it): first, an older copy of it gone. */
    fun add(query: String) = save(SearchHistoryRules.add(entries, query))

    fun remove(query: String) = save(SearchHistoryRules.remove(entries, query))

    fun clear() = save(emptyList())

    private fun save(next: List<String>) {
        if (next == entries) return
        entries = next
        prefs.edit().putString(KEY, SearchHistoryRules.write(next)).apply()
    }

    companion object {
        private const val FILE = "fireplayer_search"
        private const val KEY = "history"

        fun of(context: Context) = SearchHistory(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** The history's rules, pure (unit-tested). */
object SearchHistoryRules {

    /** Searches kept. */
    const val MAX = 20

    /** Spaces trimmed and runs of them made one: how a search is kept and compared. */
    fun clean(query: String): String = query.trim().replace(SPACES, " ")

    /** [query] first; the same search (any letter case) earlier in the list goes; at most [MAX]. Blank: no change. */
    fun add(list: List<String>, query: String): List<String> {
        val q = clean(query)
        if (q.isEmpty()) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    fun remove(list: List<String>, query: String): List<String> = list.filterNot { it.equals(clean(query), ignoreCase = true) }

    fun write(list: List<String>): String = JSONArray(list).toString()

    /** The stored list; nothing when it's missing or unreadable. */
    fun read(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(text)
            var out = emptyList<String>()
            for (i in a.length() - 1 downTo 0) out = add(out, a.optString(i))
            out
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    private val SPACES = Regex("\\s+")
}
