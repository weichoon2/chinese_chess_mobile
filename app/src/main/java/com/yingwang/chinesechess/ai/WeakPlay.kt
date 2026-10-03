package com.yingwang.chinesechess.ai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Move choice for the levels below 初级.
 *
 * The engine's best move at even three plies is too strong for someone who has just learnt
 * the moves, and Pikafish has no built-in way to play worse. So these levels ask for the
 * best few root moves and draw one at random, each weighted by how close it is to the best:
 * a move [spreadCp] centipawns worse is about a third as likely as the best one, a move three
 * times that far is rare. A mate is never passed over and a move that walks into one is never
 * taken while anything else is left, because missing those reads as broken rather than weak.
 *
 * Calibrated on 2026-10-03 against `go depth 3` (初级) with the desktop build of the same
 * engine and net and these same rules, 30 games each: depth 4, 6 candidates, spread 60
 * (入门) scored 13%, about 325 Elo below 初级; spread 100 (新手) scored 0%.
 */
object WeakPlay {
    /** Mates on a centipawn scale: nearer mates count for more, every mate beats any material. */
    fun comparable(score: PikafishEngine.Score): Int {
        score.mate?.let { m -> return if (m > 0) 30000 - 100 * abs(m) else -30000 + 100 * abs(m) }
        return score.cp ?: 0
    }

    fun <T> pick(candidates: List<Pair<T, PikafishEngine.Score>>, spreadCp: Int, random: Random = Random.Default): T? {
        if (candidates.isEmpty()) return null
        val values = candidates.map { comparable(it.second) }
        val best = values.max()
        if (candidates.any { (it.second.mate ?: 0) > 0 }) {
            return candidates[values.indexOf(best)].first
        }
        val safe = candidates.indices.filter { (candidates[it].second.mate ?: 1) > 0 }
        val pool = safe.ifEmpty { candidates.indices.toList() }
        val weights = pool.map { exp(-(best - values[it]).toDouble() / spreadCp.coerceAtLeast(1)) }
        var r = random.nextDouble() * weights.sum()
        for ((k, i) in pool.withIndex()) {
            r -= weights[k]
            if (r <= 0) return candidates[i].first
        }
        return candidates[pool.last()].first
    }
}
