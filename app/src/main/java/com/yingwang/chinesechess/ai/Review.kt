package com.yingwang.chinesechess.ai

import kotlin.math.exp

/**
 * How much a move cost, for the review after a game.
 *
 * A raw centipawn drop picks the wrong move in a lost game: going from 黑优 11 to being mated
 * in one is a drop of thousands of centipawns, yet the game was gone long before. So a move is
 * measured by how much it changed the mover's share of the game, on the same logistic curve as
 * the evaluation bar and the review graph (+100 cp is about 59%, a mate is all of it). The
 * move that turned an even game into a bad one counts; moves played in a decided game do not.
 */
object Review {
    /** Slope of the evaluation bar's curve, per centipawn. */
    const val SLOPE = 0.00368208

    /** The share of the game held by the side the [score] is for, from 0 to 1. */
    fun share(score: PikafishEngine.Score): Double = 1.0 / (1.0 + exp(-SLOPE * WeakPlay.comparable(score)))

    /**
     * What a move cost its player: [before] is the position before it, for the player to move;
     * [after] the position after it, for the opponent, who is then to move.
     */
    fun cost(before: PikafishEngine.Score, after: PikafishEngine.Score): Double =
        share(before) - (1.0 - share(after))

    /** How a move is judged by its [cost]; the shallow review search is good to a few percent. */
    enum class Verdict { FINE, INACCURACY, MISTAKE, BLUNDER }

    const val INACCURACY = 0.05
    const val MISTAKE = 0.10
    const val BLUNDER = 0.25

    fun verdict(cost: Double): Verdict = when {
        cost >= BLUNDER -> Verdict.BLUNDER
        cost >= MISTAKE -> Verdict.MISTAKE
        cost >= INACCURACY -> Verdict.INACCURACY
        else -> Verdict.FINE
    }
}
