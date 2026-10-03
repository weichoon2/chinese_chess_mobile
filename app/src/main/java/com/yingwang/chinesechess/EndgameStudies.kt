package com.yingwang.chinesechess

import android.content.Context
import org.json.JSONArray

/**
 * An endgame study from a classical manual: red to move and mate in [mateIn] against the
 * best defence. [mateIn] and [solution] come from Pikafish, which checked every study
 * before it went in (legal position, forced mate, the same length at two search depths).
 */
data class EndgameStudy(
    val id: String,
    val name: String,
    val source: String,
    val fen: String,
    val mateIn: Int,
    val solution: List<String>
)

/**
 * The studies, read once from `assets/endgames.json`, and which of them the player has
 * solved. The earlier eight were drawn by hand and seven of them were not legal positions
 * (a general already in check, generals facing, an elephant or advisor on a point it can
 * never reach); these come from the books instead.
 */
object EndgameStudies {
    private const val ASSET = "endgames.json"
    private const val PREFS = "endgames"
    private const val KEY_SOLVED = "solved"

    @Volatile private var cache: List<EndgameStudy>? = null

    fun all(context: Context): List<EndgameStudy> = cache ?: load(context).also { cache = it }

    fun byId(context: Context, id: String): EndgameStudy? = all(context).firstOrNull { it.id == id }

    /** The next study after [current] that is not solved yet, wrapping round; null when all are. */
    fun nextUnsolved(context: Context, current: EndgameStudy?): EndgameStudy? {
        val studies = all(context)
        val solved = solved(context)
        val start = studies.indexOfFirst { it.id == current?.id } + 1
        return (studies.indices).map { studies[(start + it) % studies.size] }.firstOrNull { it.id !in solved }
    }

    fun solved(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SOLVED, emptySet()) ?: emptySet()

    fun markSolved(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SOLVED, solved(context) + id).apply()
    }

    private fun load(context: Context): List<EndgameStudy> {
        val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val solution = o.optJSONArray("solution") ?: JSONArray()
            EndgameStudy(
                id = o.getString("id"),
                name = o.getString("name"),
                source = o.optString("source"),
                fen = o.getString("fen"),
                mateIn = o.getInt("mate_in"),
                solution = (0 until solution.length()).map { solution.getString(it) }
            )
        }.sortedWith(compareBy({ it.mateIn }, { it.id }))
    }
}
