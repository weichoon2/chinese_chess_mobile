package com.yingwang.chinesechess

import android.content.Context
import android.util.Log
import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.ai.PikafishEngine
import com.yingwang.chinesechess.ai.WeakPlay
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controls the game flow and AI interactions
 */
class GameController(
    private val context: Context,
    aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL,
    private val audio: GameAudioManager
) {
    private val difficulty: AIDifficulty = aiDifficulty
    /** Bumped whenever the position is reset, so a search finished against an old game is dropped. */
    private var gameGeneration = 0
    /** One conversation with the engine process at a time. */
    private val engineMutex = Mutex()

    /**
     * Engine view of the position from red's side: centipawns, or moves to mate
     * (positive = red mates). Null when the engine is unavailable.
     */
    data class Evaluation(val cpRed: Int?, val mateRed: Int?)
    /**
     * [candidates] above 1 makes the level choose among that many engine moves instead of
     * always the best (see [com.yingwang.chinesechess.ai.WeakPlay]); [spreadCp] is how far
     * from the best it is willing to stray.
     */
    enum class AIDifficulty(val pikafishDepth: Int, val candidates: Int = 1, val spreadCp: Int = 0) {
        NOVICE(4, candidates = 6, spreadCp = 100),
        LEARNER(4, candidates = 6, spreadCp = 60),
        BEGINNER(3),
        INTERMEDIATE(6),
        ADVANCED(10),
        PROFESSIONAL(15),
        MASTER(20),
        GRANDMASTER(0)        // Pikafish unlimited depth
    }

    enum class GameMode {
        PLAYER_VS_PLAYER,
        PLAYER_VS_AI,
        AI_VS_AI
    }

    private var board = Board.createInitialBoard()
    private var initialBoard = Board.createInitialBoard()
    private var gameMode = GameMode.PLAYER_VS_AI
    private var aiColor = PieceColor.BLACK
    /** The study being solved, if this is endgame practice. */
    private var endgame: EndgameStudy? = null
    private val inEndgame: Boolean get() = endgame != null
    /** Set when the result is announced; cleared by anything that resets or rewinds the game. */
    private var gameOver = false
    private val fallbackAI: ChessAI = ChessAI(maxDepth = 3, timeLimit = 2000, quiescenceDepth = 2)
    private var pikafishEngine: PikafishEngine? = null

    private var moveHistory = mutableListOf<Move>()
    private var positionHashes = mutableListOf<Long>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameStartTime = 0L
    private var currentMoveStartTime = 0L
    private var redScore = 0
    private var blackScore = 0
    private val redCapturedPieces = mutableListOf<Piece>()
    private val blackCapturedPieces = mutableListOf<Piece>()

    // Replay state
    private var replayMode = false
    private var replayIndex = 0
    private var replayMoves = listOf<Move>()

    var onBoardUpdated: ((Board) -> Unit)? = null
    var onEvaluationUpdated: ((Evaluation?) -> Unit)? = null
    var onGameOver: ((GameResult) -> Unit)? = null
    var onAIThinking: ((Boolean) -> Unit)? = null
    var onMoveCompleted: ((Move) -> Unit)? = null
    var onStatsUpdated: ((GameStats) -> Unit)? = null
    var onMoveAnimationRequested: ((Move, Board) -> Unit)? = null
    /** The player made as many moves as the study allows without mating. */
    var onEndgameFailed: ((EndgameStudy) -> Unit)? = null

    data class GameStats(
        val redScore: Int,
        val blackScore: Int,
        val moveNumber: Int,
        val gameTime: Long,
        val lastMoveTime: Long,
        val redCapturedPieces: List<Piece> = emptyList(),
        val blackCapturedPieces: List<Piece> = emptyList()
    )

    sealed class GameResult {
        data class Checkmate(val winner: PieceColor) : GameResult()
        object Stalemate : GameResult()
        data class PerpetualCheck(val winner: PieceColor) : GameResult()
        object RepetitionDraw : GameResult()
    }

    fun setGameMode(mode: GameMode, aiColor: PieceColor = PieceColor.BLACK) {
        this.gameMode = mode
        this.aiColor = aiColor
    }

    fun startNewGame() {
        gameGeneration++
        gameOver = false
        replayMode = false
        endgame = null
        board = Board.createInitialBoard()
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        fallbackAI.clearCache()
        gameStartTime = System.currentTimeMillis()
        currentMoveStartTime = gameStartTime
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        onBoardUpdated?.invoke(board)
        updateStats()
        onEvaluationUpdated?.invoke(null)

        // Trigger AI move if needed
        if (shouldAIMove()) {
            makeAIMove()
        } else {
            refreshEvaluation()
        }
    }

    fun getCurrentBoard(): Board = board

    /** Position the current game started from (standard, or an endgame study). */
    fun getInitialBoard(): Board = initialBoard

    fun getMoveHistory(): List<Move> = moveHistory.toList()

    fun makePlayerMove(move: Move): Boolean {
        if (replayMode) return false

        // Validate move
        val legalMoves = board.getAllLegalMoves()
        if (move !in legalMoves) {
            return false
        }

        // Play appropriate sound
        if (move.capturedPiece != null) {
            audio.playCaptureSound()
        } else {
            audio.playMoveSound()
        }

        // Update score and captured pieces if capturing
        if (move.capturedPiece != null) {
            val captureValue = move.capturedPiece.type.baseValue
            if (move.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(move.capturedPiece)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(move.capturedPiece)
            }
        }

        // Request animation before mutating board state
        onMoveAnimationRequested?.invoke(move, board.copy())

        // Make the move
        val moveStartTime = currentMoveStartTime
        board.makeMoveInPlace(move)
        moveHistory.add(move)
        positionHashes.add(board.getPositionHash())
        currentMoveStartTime = System.currentTimeMillis()

        // Check for check condition and play sound
        if (board.isInCheck(board.currentPlayer)) {
            audio.playCheckSound()
        }

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(move)
        updateStats()

        // Check game over
        if (checkGameOver()) {
            return true
        }

        val study = endgame
        if (study != null && endgameMovesLeft() <= 0) {
            gameOver = true
            audio.playGameOverSound()
            onEndgameFailed?.invoke(study)
            return true
        }

        // AI's turn; its search reports the evaluation, otherwise ask for one.
        if (shouldAIMove()) {
            makeAIMove()
        } else {
            refreshEvaluation()
        }

        return true
    }

    private fun shouldAIMove(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> false
            GameMode.PLAYER_VS_AI -> board.currentPlayer == aiColor
            GameMode.AI_VS_AI -> true
        }
    }

    private suspend fun ensurePikafish(): PikafishEngine? {
        if (pikafishEngine != null) return pikafishEngine
        return try {
            val engine = PikafishEngine(context)
            engine.start()
            pikafishEngine = engine
            engine
        } catch (e: Exception) {
            Log.e("GameController", "Failed to start Pikafish", e)
            null
        }
    }

    fun makeAIMove() {
        if (board.isCheckmate() || board.isStalemate()) return

        onAIThinking?.invoke(true)

        coroutineScope.launch {
            try {
                val generation = gameGeneration
                val thinkStart = System.currentTimeMillis()
                val sideToMove = board.currentPlayer
                var (move, score) = searchBestMove(null)

                // Shuffling: the engine happily walks a piece back and forth when nothing is
                // pressing, which reads as dithering. If the chosen move revisits a position
                // or reverses its own last move, search again with those moves off the table
                // and take the alternative when it costs little. A third occurrence of a
                // position is avoided regardless of score.
                if (move != null && (timesSeenAfter(move) >= 1 || looksLikeShuffling(move, sideToMove))) {
                    val forced = timesSeenAfter(move) >= 2
                    val fresh = board.getAllLegalMoves().filter {
                        timesSeenAfter(it) == 0 && !looksLikeShuffling(it, sideToMove)
                    }
                    val (alternative, altScore) = searchBestMove(fresh)
                    if (alternative != null && (forced || acceptableAlternative(score, altScore))) {
                        move = alternative
                        score = altScore
                    }
                }

                if (move != null) {
                    // A reply that lands the instant the player lifts a finger reads as a glitch;
                    // hold it back so every AI move takes at least MIN_THINK_MS.
                    val elapsed = System.currentTimeMillis() - thinkStart
                    if (elapsed < MIN_THINK_MS) delay(MIN_THINK_MS - elapsed)
                    if (generation == gameGeneration) {
                        // The root score of the search is the engine's view of the line it chose.
                        onEvaluationUpdated?.invoke(toRedPerspective(score, sideToMove))
                        applyAIMove(move)
                    }
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    /**
     * Engine search, optionally restricted to [candidates]; null means every legal move.
     * Returns the move and, when Pikafish produced it, its root score for the side to move.
     */
    private suspend fun searchBestMove(candidates: List<Move>?): Pair<Move?, PikafishEngine.Score?> {
        if (candidates != null && candidates.isEmpty()) return null to null
        // An endgame study is only a study against the best defence, whatever the level.
        val depth = when {
            inEndgame -> ENDGAME_DEFENCE_DEPTH
            difficulty.pikafishDepth > 0 -> difficulty.pikafishDepth
            else -> 0
        }
        val timeMs = if (depth == 0) 10000L else 0L  // 棋圣: 10s unlimited
        val fromEngine = engineMutex.withLock {
            val engine = ensurePikafish() ?: return@withLock null
            if (difficulty.candidates > 1 && !inEndgame) {
                val options = engine.findCandidates(board, depth, difficulty.candidates, candidates)
                val choice = WeakPlay.pick(options, difficulty.spreadCp) ?: return@withLock null
                return@withLock choice to options.first { it.first == choice }.second
            }
            val move = engine.findBestMove(board, depth = depth, moveTimeMs = timeMs, searchMoves = candidates)
            if (move != null) move to engine.lastScore else null
        }
        if (fromEngine != null) return fromEngine
        return fallbackAI.findBestMove(board, moveHistory, allowedMoves = candidates) to null
    }

    private fun toRedPerspective(score: PikafishEngine.Score?, sideToMove: PieceColor): Evaluation? {
        if (score == null) return null
        val sign = if (sideToMove == PieceColor.RED) 1 else -1
        return Evaluation(cpRed = score.cp?.let { it * sign }, mateRed = score.mate?.let { it * sign })
    }

    /**
     * Shallow engine evaluation of [target] (the live board by default), published through
     * [onEvaluationUpdated] unless the game moved on while it ran.
     */
    private fun refreshEvaluation(target: Board = board) {
        val snapshot = target.copy()
        if (snapshot.isCheckmate() || snapshot.isStalemate()) return
        val generation = gameGeneration
        val moveCount = moveHistory.size
        val replayAt = replayIndex
        coroutineScope.launch {
            val score = engineMutex.withLock {
                val engine = ensurePikafish() ?: return@withLock null
                engine.evaluate(snapshot, depth = 10)
            }
            if (generation == gameGeneration && moveCount == moveHistory.size && replayAt == replayIndex) {
                onEvaluationUpdated?.invoke(toRedPerspective(score, snapshot.currentPlayer))
            }
        }
    }

    private suspend fun applyAIMove(finalMove: Move) {
        if (finalMove.capturedPiece != null) audio.playCaptureSound() else audio.playMoveSound()

        val captured = finalMove.capturedPiece
        if (captured != null) {
            val captureValue = captured.type.baseValue
            if (finalMove.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(captured)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(captured)
            }
        }

        // Request animation before mutating board state
        onMoveAnimationRequested?.invoke(finalMove, board.copy())

        board.makeMoveInPlace(finalMove)
        moveHistory.add(finalMove)
        positionHashes.add(board.getPositionHash())
        currentMoveStartTime = System.currentTimeMillis()

        if (board.isInCheck(board.currentPlayer)) audio.playCheckSound()

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(finalMove)
        updateStats()

        if (!checkGameOver() && gameMode == GameMode.AI_VS_AI) {
            delay(500) // Brief pause for visualization
            makeAIMove()
        }
    }

    /** How many times the position after [move] has already occurred in this game. */
    private fun timesSeenAfter(move: Move): Int {
        val testBoard = board.copy()
        testBoard.makeMoveInPlace(move)
        val hash = testBoard.getPositionHash()
        return positionHashes.count { it == hash }
    }

    /** True if [move] undoes [side]'s previous move or repeats the one before that. */
    private fun looksLikeShuffling(move: Move, side: PieceColor): Boolean {
        val own = moveHistory.filter { it.piece.color == side }
        val last = own.lastOrNull() ?: return false
        if (last.from == move.to && last.to == move.from) return true
        val twoAgo = own.getOrNull(own.size - 2) ?: return false
        return twoAgo.from == move.from && twoAgo.to == move.to
    }

    /**
     * Whether an alternative to the engine's first choice is worth playing to avoid a
     * repetition: never give up a mate, never walk into one, otherwise within 60 cp.
     * Without scores (fallback search) any alternative is fine.
     */
    private fun acceptableAlternative(best: PikafishEngine.Score?, alt: PikafishEngine.Score?): Boolean {
        if (best == null || alt == null) return true
        best.mate?.let { return it < 0 }          // getting mated anyway: anything goes; mating: keep it
        alt.mate?.let { return it > 0 }           // alternative mates: fine; gets mated: no
        val bestCp = best.cp ?: return true
        val altCp = alt.cp ?: return true
        return altCp >= bestCp - 60
    }

    fun isGameOver(): Boolean = gameOver

    private fun checkGameOver(): Boolean {
        gameOver = true
        when {
            board.isCheckmate() -> {
                audio.playGameOverSound()
                val winner = board.currentPlayer.opposite()
                onGameOver?.invoke(GameResult.Checkmate(winner))
                return true
            }
            board.isStalemate() -> {
                audio.playGameOverSound()
                onGameOver?.invoke(GameResult.Stalemate)
                return true
            }
        }

        // Repetition detection: same position 3 times
        val currentHash = positionHashes.last()
        val count = positionHashes.count { it == currentHash }
        if (count >= 3) {
            audio.playGameOverSound()
            if (board.isInCheck(board.currentPlayer)) {
                // Current player is in check → opponent perpetually checking → opponent loses
                val winner = board.currentPlayer
                onGameOver?.invoke(GameResult.PerpetualCheck(winner))
            } else {
                onGameOver?.invoke(GameResult.RepetitionDraw)
            }
            return true
        }

        gameOver = false
        return false
    }

    fun undoLastMove(): Boolean {
        gameGeneration++
        gameOver = false
        if (moveHistory.isEmpty()) return false

        // In player vs AI mode, undo two moves (player and AI)
        val movesToUndo = if (gameMode == GameMode.PLAYER_VS_AI) 2 else 1

        repeat(movesToUndo.coerceAtMost(moveHistory.size)) {
            moveHistory.removeAt(moveHistory.size - 1)
        }

        // Rebuild board and captured pieces from history
        board = initialBoard.copy()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        for (move in moveHistory) {
            if (move.capturedPiece != null) {
                val captureValue = move.capturedPiece.type.baseValue
                if (move.piece.color == PieceColor.RED) {
                    redScore += captureValue
                    redCapturedPieces.add(move.capturedPiece)
                } else {
                    blackScore += captureValue
                    blackCapturedPieces.add(move.capturedPiece)
                }
            }
            board.makeMoveInPlace(move)
            positionHashes.add(board.getPositionHash())
        }

        onBoardUpdated?.invoke(board)
        updateStats()
        refreshEvaluation()
        return true
    }

    fun startEndgame(study: EndgameStudy) {
        gameGeneration++
        gameOver = false
        replayMode = false
        endgame = study
        board = Fen.parse(study.fen)
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        fallbackAI.clearCache()
        gameStartTime = System.currentTimeMillis()
        currentMoveStartTime = gameStartTime
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        gameMode = GameMode.PLAYER_VS_AI
        aiColor = board.currentPlayer.opposite()
        onBoardUpdated?.invoke(board)
        updateStats()
        onEvaluationUpdated?.invoke(null)
        if (!shouldAIMove()) refreshEvaluation()
    }

    fun getEndgame(): EndgameStudy? = endgame

    /** Moves the player still has to deliver mate in the current study. */
    fun endgameMovesLeft(): Int {
        val study = endgame ?: return 0
        val player = aiColor.opposite()
        return study.mateIn - moveHistory.count { it.piece.color == player }
    }

    /**
     * Sets the study up again and opens its solution in the replay, one move at a time.
     * Leaving the replay leaves the study at its start, ready to try.
     */
    fun showSolution(study: EndgameStudy) {
        startEndgame(study)
        val moves = mutableListOf<Move>()
        val walk = initialBoard.copy()
        for (uci in study.solution) {
            if (uci.length < 4) break
            val from = Position(9 - (uci[1] - '0'), uci[0] - 'a')
            val to = Position(9 - (uci[3] - '0'), uci[2] - 'a')
            val piece = walk.getPiece(from) ?: break
            val move = Move(from, to, piece, walk.getPiece(to))
            moves.add(move)
            walk.makeMoveInPlace(move)
        }
        if (moves.isEmpty()) return
        replayMode = true
        replayMoves = moves
        replayIndex = 0
        rebuildBoardToIndex(0)
    }

    fun isEndgameMode(): Boolean = inEndgame

    // --- Replay Mode ---

    fun enterReplayMode(): Boolean {
        if (moveHistory.isEmpty()) return false
        replayMode = true
        replayMoves = moveHistory.toList()
        replayIndex = replayMoves.size
        return true
    }

    fun exitReplayMode() {
        gameGeneration++
        replayMode = false
        replayMoves = emptyList()
        replayIndex = 0
        onBoardUpdated?.invoke(board)
    }

    fun isInReplayMode() = replayMode

    fun replayStepBack(): Boolean {
        if (!replayMode || replayIndex <= 0) return false
        replayIndex--
        rebuildBoardToIndex(replayIndex)
        return true
    }

    fun replayStepForward(): Boolean {
        if (!replayMode || replayIndex >= replayMoves.size) return false
        replayIndex++
        rebuildBoardToIndex(replayIndex)
        return true
    }

    fun replayToStart() {
        if (!replayMode) return
        replayIndex = 0
        rebuildBoardToIndex(0)
    }

    fun replayToEnd() {
        if (!replayMode) return
        replayIndex = replayMoves.size
        rebuildBoardToIndex(replayIndex)
    }

    fun getReplayIndex(): Int = replayIndex

    fun getReplayLength(): Int = replayMoves.size

    /** Shows the position after [index] moves of the game being replayed. */
    fun replayGoTo(index: Int) {
        if (!replayMode) return
        replayIndex = index.coerceIn(0, replayMoves.size)
        rebuildBoardToIndex(replayIndex)
    }

    fun getReplayInfo(): String =
        if (replayMode) context.getString(R.string.replay_progress, replayIndex, replayMoves.size) else ""

    private fun rebuildBoardToIndex(index: Int) {
        val tempBoard = initialBoard.copy()
        for (i in 0 until index) {
            tempBoard.makeMoveInPlace(replayMoves[i])
        }
        val lastReplayMove = if (index > 0) replayMoves[index - 1] else null
        onBoardUpdated?.invoke(tempBoard)
        if (lastReplayMove != null) {
            onMoveCompleted?.invoke(lastReplayMove)
        }
        refreshEvaluation(tempBoard)
    }

    fun getAIStats(): String {
        return "Difficulty: $difficulty, Engine: ${if (pikafishEngine != null) "Pikafish" else "fallback"}"
    }

    fun getGameMode(): GameMode = gameMode

    fun getAIColor(): PieceColor = aiColor

    fun getDifficulty(): AIDifficulty = difficulty

    fun getGameStartTime(): Long = gameStartTime

    fun isPlayerTurn(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> true
            GameMode.PLAYER_VS_AI -> board.currentPlayer != aiColor
            GameMode.AI_VS_AI -> false
        }
    }

    private fun updateStats() {
        val gameTime = System.currentTimeMillis() - gameStartTime
        val lastMoveTime = System.currentTimeMillis() - currentMoveStartTime
        val stats = GameStats(
            redScore = redScore,
            blackScore = blackScore,
            moveNumber = moveHistory.size,
            gameTime = gameTime,
            lastMoveTime = lastMoveTime,
            redCapturedPieces = redCapturedPieces.toList(),
            blackCapturedPieces = blackCapturedPieces.toList()
        )
        onStatsUpdated?.invoke(stats)
    }

    fun saveGame(context: Context): Boolean {
        try {
            val json = JSONObject()
            // The position the game started from, so an endgame study resumes on its own
            // board instead of having its moves replayed onto the opening.
            json.put("initialFen", Fen.format(initialBoard))
            endgame?.let { json.put("endgameId", it.id) }
            json.put("gameMode", gameMode.name)
            json.put("aiColor", aiColor.name)
            json.put("difficulty", difficulty.name)
            json.put("elapsedMs", System.currentTimeMillis() - gameStartTime)

            val movesArray = JSONArray()
            for (move in moveHistory) {
                val moveJson = JSONObject()
                moveJson.put("fromRow", move.from.row)
                moveJson.put("fromCol", move.from.col)
                moveJson.put("toRow", move.to.row)
                moveJson.put("toCol", move.to.col)
                moveJson.put("pieceType", move.piece.type.name)
                moveJson.put("pieceColor", move.piece.color.name)
                if (move.capturedPiece != null) {
                    moveJson.put("capturedType", move.capturedPiece.type.name)
                    moveJson.put("capturedColor", move.capturedPiece.color.name)
                }
                movesArray.put(moveJson)
            }
            json.put("moves", movesArray)

            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            prefs.edit().putString("saved_game", json.toString()).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun loadGame(context: Context): Boolean {
        gameGeneration++
        gameOver = false
        try {
            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_game", null) ?: return false
            val json = JSONObject(jsonStr)

            // Restore game mode
            gameMode = GameMode.valueOf(json.getString("gameMode"))
            aiColor = PieceColor.valueOf(json.getString("aiColor"))

            // Replay all moves from where the game started
            board = json.optString("initialFen").takeIf { it.isNotEmpty() }?.let { Fen.parse(it) }
                ?: Board.createInitialBoard()
            initialBoard = board.copy()
            endgame = json.optString("endgameId").takeIf { it.isNotEmpty() }?.let { EndgameStudies.byId(context, it) }
            replayMode = false
            moveHistory.clear()
            positionHashes.clear()
            positionHashes.add(board.getPositionHash())
            redScore = 0
            blackScore = 0
            redCapturedPieces.clear()
            blackCapturedPieces.clear()

            val movesArray = json.getJSONArray("moves")
            for (i in 0 until movesArray.length()) {
                val moveJson = movesArray.getJSONObject(i)
                val from = Position(moveJson.getInt("fromRow"), moveJson.getInt("fromCol"))
                val to = Position(moveJson.getInt("toRow"), moveJson.getInt("toCol"))
                val piece = board.getPiece(from) ?: continue
                val capturedPiece = board.getPiece(to)
                val move = Move(from, to, piece, capturedPiece)

                if (capturedPiece != null) {
                    val captureValue = capturedPiece.type.baseValue
                    if (piece.color == PieceColor.RED) {
                        redScore += captureValue
                        redCapturedPieces.add(capturedPiece)
                    } else {
                        blackScore += captureValue
                        blackCapturedPieces.add(capturedPiece)
                    }
                }

                board.makeMoveInPlace(move)
                moveHistory.add(move)
                positionHashes.add(board.getPositionHash())
            }

            // Resume the clock where it stopped rather than from zero.
            gameStartTime = System.currentTimeMillis() - json.optLong("elapsedMs", 0L)
            currentMoveStartTime = System.currentTimeMillis()
            onBoardUpdated?.invoke(board)
            updateStats()
            if (!shouldAIMove()) refreshEvaluation()

            // Trigger AI move if it's AI's turn
            if (shouldAIMove()) {
                makeAIMove()
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    companion object {
        /** Floor on how long an AI move appears to take, so replies never look instant. */
        private const val MIN_THINK_MS = 900L

        /** Depth for scoring each position of a finished game, and for the better move. */
        private const val REVIEW_DEPTH = 8
        private const val REVIEW_BEST_DEPTH = 12
        /** A move that loses less than this is not called a mistake. */
        private const val MISTAKE_THRESHOLD_CP = 120

        /** Search depth for the defending side of an endgame study. */
        private const val ENDGAME_DEFENCE_DEPTH = 12

        /** Difficulty stored with the saved game, so resuming rebuilds the same opponent. */
        fun savedDifficulty(context: Context): AIDifficulty? {
            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_game", null) ?: return null
            return try {
                AIDifficulty.valueOf(JSONObject(jsonStr).getString("difficulty"))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun hasSavedGame(context: Context): Boolean {
        val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
        return prefs.contains("saved_game")
    }

    fun deleteSavedGame(context: Context) {
        val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
        prefs.edit().remove("saved_game").apply()
    }

    /**
     * The move by [player] that cost the most, judged by the engine, with what it should
     * have been. [delta] is how far the position fell in centipawns from the player's side.
     */
    data class Mistake(val index: Int, val played: Move, val better: Move?, val before: Evaluation?, val after: Evaluation?, val delta: Int)

    /**
     * Scores every position of the game at a shallow depth and returns the player's move with
     * the largest drop, or null when no move lost more than [MISTAKE_THRESHOLD_CP] (or the
     * engine is unavailable). Runs off the main thread; [onDone] is called on it.
     */
    fun findBiggestMistake(player: PieceColor, onDone: (Mistake?) -> Unit) {
        val moves = moveHistory.toList()
        val start = initialBoard.copy()
        coroutineScope.launch {
            val mistake = engineMutex.withLock {
                val engine = ensurePikafish() ?: return@withLock null
                // Score of each position for its side to move.
                val positions = mutableListOf(start.copy())
                // makeMove keeps the side to move (it is for trying moves out); these need the turn to pass.
                for (m in moves) positions.add(positions.last().copy().also { it.makeMoveInPlace(m) })
                val scores = positions.map { pos ->
                    if (pos.isCheckmate()) PikafishEngine.Score(cp = null, mate = 0) else engine.evaluate(pos, depth = REVIEW_DEPTH)
                }
                var worst: Pair<Int, Int>? = null  // index, delta
                for (i in moves.indices) {
                    if (moves[i].piece.color != player) continue
                    val before = scores[i] ?: continue
                    val after = scores[i + 1] ?: continue
                    // After the move it is the opponent's turn, so their score is the player's loss.
                    val drop = WeakPlay.comparable(before) + WeakPlay.comparable(after)
                    if (worst == null || drop > worst.second) worst = i to drop
                }
                val (index, delta) = worst ?: return@withLock null
                if (delta < MISTAKE_THRESHOLD_CP) return@withLock null
                val better = engine.findBestMove(positions[index], depth = REVIEW_BEST_DEPTH)
                Mistake(
                    index = index,
                    played = moves[index],
                    better = better?.takeIf { it.from != moves[index].from || it.to != moves[index].to },
                    before = toRedPerspective(scores[index], positions[index].currentPlayer),
                    after = toRedPerspective(scores[index + 1], positions[index + 1].currentPlayer),
                    delta = delta
                )
            }
            onDone(mistake)
        }
    }

    fun getHint(callback: (Move?) -> Unit) {
        if (board.isCheckmate() || board.isStalemate()) {
            callback(null)
            return
        }

        onAIThinking?.invoke(true)
        coroutineScope.launch {
            try {
                val sideToMove = board.currentPlayer
                val fromEngine = engineMutex.withLock {
                    val engine = ensurePikafish() ?: return@withLock null
                    val move = engine.findBestMove(board, moveTimeMs = 2000)
                    if (move != null) move to engine.lastScore else null
                }
                if (fromEngine != null) {
                    onEvaluationUpdated?.invoke(toRedPerspective(fromEngine.second, sideToMove))
                    callback(fromEngine.first)
                } else {
                    callback(fallbackAI.findBestMove(board, moveHistory))
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    fun destroy() {
        coroutineScope.cancel()
        pikafishEngine?.close()
        pikafishEngine = null
    }
}
