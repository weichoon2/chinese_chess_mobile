package com.yingwang.chinesechess

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.yingwang.chinesechess.GameController.AIDifficulty
import com.yingwang.chinesechess.GameController.GameMode
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.model.Piece
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.ui.BoardView
import com.yingwang.chinesechess.ui.EvalBarView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Mates farther away than this are shown as a plain "winning"/"losing". */
        private const val MATE_HINT_LIMIT = 3
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_CHALLENGE = "challenge"
        private const val KEY_STREAK_LEVEL = "streak_level"
        private const val KEY_STREAK = "streak"
    }

    private lateinit var boardView: BoardView

    // Header
    private lateinit var redCard: View
    private lateinit var blackCard: View
    private lateinit var redTurnDot: View
    private lateinit var blackTurnDot: View
    private lateinit var redRoleText: TextView
    private lateinit var blackRoleText: TextView
    private lateinit var redScoreText: TextView
    private lateinit var blackScoreText: TextView
    private lateinit var redCapturedLayout: LinearLayout
    private lateinit var blackCapturedLayout: LinearLayout
    private lateinit var gameTimeText: TextView
    private lateinit var moveCountText: TextView
    private lateinit var gameModeText: TextView
    private lateinit var evalBar: EvalBarView
    private lateinit var evalText: TextView
    private var evaluation: GameController.Evaluation? = null
    private var lastStats: GameController.GameStats? = null

    // Status pill
    private lateinit var statusText: TextView
    private lateinit var aiThinkingIndicator: LinearLayout
    private lateinit var thinkingDot1: View
    private lateinit var thinkingDot2: View
    private lateinit var thinkingDot3: View

    private lateinit var moveHistoryText: TextView
    private lateinit var moveStrip: View
    private lateinit var replayBar: View
    private lateinit var replayProgressText: TextView
    private lateinit var newGameButton: Button
    private lateinit var hintButton: Button
    private lateinit var undoButton: Button
    private lateinit var moreButton: Button

    private var isMuted = false
    private val settings by lazy { getSharedPreferences("chess_settings", MODE_PRIVATE) }
    private lateinit var audioManager: GameAudioManager
    private lateinit var gameController: GameController
    private var thinkingAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The ground colour comes from the palette, not from day/night mode, so the bar
        // icons follow a palette flag rather than the system setting.
        if (resources.getBoolean(R.bool.chess_light_system_bars)) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        audioManager = GameAudioManager(this)
        // Mute used to reset on every launch, so the guqin came back each time.
        isMuted = settings.getBoolean("muted", false)
        audioManager.setMuted(isMuted)
        // A saved game remembers its difficulty; start the controller with it so resuming
        // faces the same opponent. Otherwise the level last chosen, and on the very first
        // launch 初级 until the player says otherwise: starting everyone at 专业 meant a
        // newcomer's first game was a rout.
        val startDifficulty = GameController.savedDifficulty(this) ?: preferredDifficulty() ?: AIDifficulty.BEGINNER
        gameController = GameController(this, startDifficulty, audioManager)
        initViews()
        setupGameControllerCallbacks()
        gameController.startNewGame()
        startTimerUpdates()

        if (!gameController.hasSavedGame(this) && preferredDifficulty() == null) {
            showDifficultyDialog(firstRun = true)
        }

        if (gameController.hasSavedGame(this)) {
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle(R.string.resume_title)
                .setMessage(R.string.resume_message)
                .setPositiveButton(R.string.resume_continue) { _, _ ->
                    gameController.loadGame(this)
                    updateGameModeDisplay()
                }
                .setNegativeButton(R.string.new_game) { _, _ ->
                    gameController.deleteSavedGame(this)
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isMuted) audioManager.startBackgroundMusic()
    }

    override fun onPause() {
        super.onPause()
        audioManager.pauseBackgroundMusic()
        if (gameController.getMoveHistory().isNotEmpty()) {
            gameController.saveGame(this)
        }
    }

    private fun initViews() {
        boardView = findViewById(R.id.boardView)
        redCard = findViewById(R.id.redCard)
        blackCard = findViewById(R.id.blackCard)
        redTurnDot = findViewById(R.id.redTurnDot)
        blackTurnDot = findViewById(R.id.blackTurnDot)
        redRoleText = findViewById(R.id.redRoleText)
        blackRoleText = findViewById(R.id.blackRoleText)
        redScoreText = findViewById(R.id.redScoreText)
        blackScoreText = findViewById(R.id.blackScoreText)
        redCapturedLayout = findViewById(R.id.redCapturedPieces)
        blackCapturedLayout = findViewById(R.id.blackCapturedPieces)
        gameTimeText = findViewById(R.id.gameTimeText)
        moveCountText = findViewById(R.id.moveCountText)
        gameModeText = findViewById(R.id.gameModeText)
        evalBar = findViewById(R.id.evalBar)
        evalText = findViewById(R.id.evalText)
        statusText = findViewById(R.id.statusText)
        aiThinkingIndicator = findViewById(R.id.aiThinkingIndicator)
        thinkingDot1 = findViewById(R.id.thinkingDot1)
        thinkingDot2 = findViewById(R.id.thinkingDot2)
        thinkingDot3 = findViewById(R.id.thinkingDot3)
        moveHistoryText = findViewById(R.id.moveHistoryText)
        moveStrip = findViewById(R.id.moveStrip)
        replayBar = findViewById(R.id.replayBar)
        replayProgressText = findViewById(R.id.replayProgressText)
        moveStrip.setOnClickListener { showFullHistory() }
        findViewById<View>(R.id.replayStartButton).setOnClickListener { gameController.replayToStart(); updateReplayBar() }
        findViewById<View>(R.id.replayPrevButton).setOnClickListener {
            if (!gameController.replayStepBack()) toast(R.string.replay_at_start)
            updateReplayBar()
        }
        findViewById<View>(R.id.replayNextButton).setOnClickListener {
            if (!gameController.replayStepForward()) toast(R.string.replay_at_end)
            updateReplayBar()
        }
        findViewById<View>(R.id.replayEndButton).setOnClickListener { gameController.replayToEnd(); updateReplayBar() }
        findViewById<View>(R.id.replayExitButton).setOnClickListener { exitReplay() }
        newGameButton = findViewById(R.id.newGameButton)
        hintButton = findViewById(R.id.hintButton)
        undoButton = findViewById(R.id.undoButton)
        moreButton = findViewById(R.id.moreButton)

        newGameButton.setOnClickListener { confirmAbandonThen { showNewGameDialog() } }
        moreButton.setOnClickListener { showMoreDialog() }

        undoButton.setOnClickListener {
            if (isRatedGame()) {
                toast(R.string.challenge_no_undo)
                return@setOnClickListener
            }
            if (gameController.getMoveHistory().isEmpty()) {
                toast(R.string.undo_none)
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle(R.string.undo_confirm_title)
                .setMessage(R.string.undo_confirm_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (gameController.undoLastMove()) {
                        boardView.clearSelection()
                        toast(R.string.undo_done)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        hintButton.setOnClickListener {
            if (isRatedGame()) {
                toast(R.string.challenge_no_hint)
                return@setOnClickListener
            }
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                return@setOnClickListener
            }
            gameController.getHint { move ->
                runOnUiThread {
                    if (move != null) {
                        boardView.highlightMove(move)
                        val text = notation(move, gameController.getCurrentBoard())
                        Snackbar.make(boardView, getString(R.string.hint_suggest, text), 4000).show()
                    } else {
                        Snackbar.make(boardView, R.string.hint_unavailable, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }

        updateScoreLines()
        moveCountText.text = getString(R.string.round_label, 0)
    }

    private fun setupGameControllerCallbacks() {
        gameController.onBoardUpdated = { board ->
            runOnUiThread {
                boardView.setBoard(board)
                updateStatus()
                if (gameController.getMoveHistory().isEmpty()) {
                    boardView.highlightMove(null)
                }
            }
        }

        gameController.onGameOver = { result ->
            runOnUiThread { showGameOver(result) }
        }

        gameController.onAIThinking = { isThinking ->
            runOnUiThread {
                aiThinkingIndicator.visibility = if (isThinking) View.VISIBLE else View.GONE
                if (isThinking) startThinkingAnimation() else stopThinkingAnimation()
                undoButton.isEnabled = !isThinking
                hintButton.isEnabled = !isThinking
                statusText.text = if (isThinking) getString(R.string.ai_thinking) else getStatusText()
            }
        }

        gameController.onMoveCompleted = { move ->
            runOnUiThread {
                boardView.highlightMove(move)
                boardView.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
        }

        gameController.onStatsUpdated = { stats ->
            runOnUiThread { updateGameStats(stats) }
        }

        gameController.onEvaluationUpdated = { eval ->
            runOnUiThread {
                evaluation = eval
                evalBar.setEvaluation(eval?.cpRed, eval?.mateRed)
                updateScoreLines()
            }
        }

        gameController.onMoveAnimationRequested = { move, preBoard ->
            runOnUiThread {
                boardView.animateMove(move, preBoard) {
                    boardView.setBoard(gameController.getCurrentBoard())
                }
            }
        }

        boardView.setOnMoveListener { move ->
            if (gameController.isInReplayMode()) {
                toast(R.string.replay_blocked)
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (gameController.makePlayerMove(move)) {
                boardView.clearSelection()
            } else {
                toast(R.string.illegal_move)
            }
        }

        updateGameModeDisplay()
    }

    // ── Header and status ──

    private fun sideName(color: PieceColor): String =
        getString(if (color == PieceColor.RED) R.string.red_side else R.string.black_side)

    private fun updateStatus() {
        statusText.text = getStatusText()
        val board = gameController.getCurrentBoard()
        val over = board.isCheckmate() || board.isStalemate()
        val redActive = !over && board.currentPlayer == PieceColor.RED
        val blackActive = !over && board.currentPlayer == PieceColor.BLACK
        redCard.setBackgroundResource(if (redActive) R.drawable.player_card_active else R.drawable.player_card)
        blackCard.setBackgroundResource(if (blackActive) R.drawable.player_card_active else R.drawable.player_card)
        redTurnDot.visibility = if (redActive) View.VISIBLE else View.INVISIBLE
        blackTurnDot.visibility = if (blackActive) View.VISIBLE else View.INVISIBLE
    }

    private fun getStatusText(): String {
        val board = gameController.getCurrentBoard()
        val side = sideName(board.currentPlayer)
        return when {
            board.isCheckmate() -> getString(R.string.wins, sideName(board.currentPlayer.opposite()))
            board.isStalemate() -> getString(R.string.draw_short)
            board.isInCheck(board.currentPlayer) -> getString(R.string.in_check, side)
            else -> getString(R.string.side_to_move, side)
        }
    }

    /**
     * One reading of the position, from red's side, at the end of the evaluation bar; the
     * cards say what each side has taken. They used to show +0.2 and -0.2, the same number
     * twice, above a bar that showed it a third time.
     */
    private fun updateScoreLines() {
        val stats = lastStats
        redScoreText.setText(if (stats?.redCapturedPieces.isNullOrEmpty()) R.string.captured_none else R.string.captured_label)
        blackScoreText.setText(if (stats?.blackCapturedPieces.isNullOrEmpty()) R.string.captured_none else R.string.captured_label)
        val eval = evaluation
        evalText.text = if (eval == null) "" else evalSummary(eval)
    }

    private fun evalSummary(eval: GameController.Evaluation): String {
        eval.mateRed?.let { mate ->
            // A mate count is a spoiler, so only a short one is spelled out.
            return when {
                mate > 0 && mate <= MATE_HINT_LIMIT -> getString(R.string.eval_red_mates, mate)
                mate < 0 && -mate <= MATE_HINT_LIMIT -> getString(R.string.eval_black_mates, -mate)
                mate > 0 -> getString(R.string.eval_red_winning)
                else -> getString(R.string.eval_black_winning)
            }
        }
        val pawns = (eval.cpRed ?: 0) / 100.0
        return when {
            pawns >= 0.3 -> getString(R.string.eval_red_better, String.format(Locale.US, "%.1f", pawns))
            pawns <= -0.3 -> getString(R.string.eval_black_better, String.format(Locale.US, "%.1f", -pawns))
            else -> getString(R.string.eval_even)
        }
    }

    private fun updateGameStats(stats: GameController.GameStats) {
        lastStats = stats
        updateScoreLines()
        gameTimeText.text = formatTime(stats.gameTime)
        moveCountText.text = getString(R.string.round_label, stats.moveNumber)
        updateMoveHistory()
        // Each card shows the pieces its side has taken.
        updateCapturedRow(redCapturedLayout, stats.redCapturedPieces)
        updateCapturedRow(blackCapturedLayout, stats.blackCapturedPieces)
    }

    private fun updateCapturedRow(container: LinearLayout, pieces: List<Piece>) {
        container.removeAllViews()
        val sorted = pieces.sortedByDescending { it.type.baseValue }
        val dp = resources.displayMetrics.density
        // Shrink the chips to fit the card instead of letting the last ones scroll out of sight.
        val available = (container.parent as? View)?.width ?: 0
        val full = (22 * dp).toInt()
        val size = if (available > 0 && sorted.isNotEmpty()) {
            minOf(full, available / sorted.size - (2 * dp).toInt()).coerceAtLeast((12 * dp).toInt())
        } else full

        for (piece in sorted) {
            val tv = TextView(this).apply {
                text = piece.type.getDisplayName(piece.color)
                textSize = 11f * size / full
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (piece.color == PieceColor.RED) R.color.chess_piece_red_ink else R.color.chess_piece_black_ink
                    )
                )
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (2 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.chess_captured_bg))
                    setStroke((1 * dp).toInt(), ContextCompat.getColor(this@MainActivity, R.color.chess_captured_stroke))
                }
            }
            container.addView(tv)
        }
    }

    private fun startThinkingAnimation() {
        val dots = listOf(thinkingDot1, thinkingDot2, thinkingDot3)
        val animators = dots.mapIndexed { index, dot ->
            ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f, 0.3f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 200L
            }
        }
        thinkingAnimator = AnimatorSet().apply {
            playTogether(animators.map { it as android.animation.Animator })
            start()
        }
    }

    private fun stopThinkingAnimation() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
    }

    private fun formatTime(timeInMillis: Long): String {
        val totalSeconds = timeInMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** The strip under the board shows only the latest round; the whole game is a tap away. */
    private fun updateMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            moveHistoryText.text = getString(R.string.history_empty)
            return
        }
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard(), westernNotation)
        val lastRoundStart = (moves.size - 1) / 2 * 2
        val round = notations.subList(lastRoundStart, moves.size).joinToString("  ")
        moveHistoryText.text = getString(R.string.history_round, lastRoundStart / 2 + 1, round)
    }

    private fun fullHistoryText(): String {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) return getString(R.string.history_empty)
        val history = StringBuilder()
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard(), westernNotation)
        moves.forEachIndexed { index, _ ->
            val moveNum = index / 2 + 1
            if (index % 2 == 0) {
                history.append(String.format(Locale.US, "%2d. %s", moveNum, notations[index]))
            } else {
                history.append("    ").append(notations[index]).append('\n')
            }
        }
        return history.toString().trimEnd()
    }

    private fun showFullHistory() {
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            text = fullHistoryText()
            textSize = 15f
            typeface = Typeface.SERIF
            setLineSpacing(4 * dp, 1f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_history_text))
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(text) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.history_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.replay_title) { _, _ -> toggleReplay() }
            .setNegativeButton(R.string.export) { _, _ -> exportMoveHistory() }
            .show()
    }

    private fun startTimerUpdates() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val gameTime = System.currentTimeMillis() - gameController.getGameStartTime()
                gameTimeText.text = formatTime(gameTime)
            }
        }
    }

    private fun difficultyShortName(): String =
        resources.getStringArray(R.array.difficulty_short)[gameController.getDifficulty().ordinal]

    /** Short caption for the header. */
    private fun modeCaption(): String = when {
        gameController.isInReplayMode() -> getString(R.string.mode_replay)
        gameController.isEndgameMode() -> getString(R.string.mode_endgame)
        else -> when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> getString(R.string.mode_pvp)
            GameMode.PLAYER_VS_AI -> getString(
                if (isRatedGame()) R.string.mode_challenge else R.string.mode_pvai, difficultyShortName()
            )
            GameMode.AI_VS_AI -> getString(R.string.mode_aivai)
        }
    }

    /** Full description for the exported record. */
    private fun modeDescription(): String = when {
        gameController.isInReplayMode() -> getString(R.string.mode_replay_long)
        gameController.isEndgameMode() -> getString(R.string.mode_endgame_long)
        else -> when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> getString(R.string.mode_pvp_long)
            GameMode.PLAYER_VS_AI -> {
                val playerColor = getString(
                    if (gameController.getAIColor() == PieceColor.RED) R.string.black_short else R.string.red_short
                )
                getString(R.string.mode_pvai_long, playerColor, difficultyShortName())
            }
            GameMode.AI_VS_AI -> getString(R.string.mode_aivai_long)
        }
    }

    private fun updateGameModeDisplay() {
        gameModeText.text = modeCaption()
        val (redRole, blackRole) = when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> R.string.role_player to R.string.role_player
            GameMode.AI_VS_AI -> R.string.role_ai to R.string.role_ai
            GameMode.PLAYER_VS_AI ->
                if (gameController.getAIColor() == PieceColor.RED) R.string.role_ai to R.string.role_player
                else R.string.role_player to R.string.role_ai
        }
        redRoleText.setText(redRole)
        blackRoleText.setText(blackRole)
        // Challenge games keep the buttons in place but greyed, so the layout does not jump.
        val locked = isRatedGame()
        hintButton.alpha = if (locked) 0.4f else 1f
        undoButton.alpha = if (locked) 0.4f else 1f
        updateStatus()
    }

    // ── End of a game ──

    /**
     * The result, then something to do next: play again, look at the move that cost the most,
     * or, after a run of wins or losses at one level, move to the next level.
     */
    private fun showGameOver(result: GameController.GameResult) {
        val playerColor = gameController.getAIColor().opposite()
        val vsAI = gameController.getGameMode() == GameMode.PLAYER_VS_AI && !gameController.isEndgameMode()
        val playerScore = when (result) {
            is GameController.GameResult.Checkmate -> if (result.winner == playerColor) 1.0 else 0.0
            is GameController.GameResult.PerpetualCheck -> if (result.winner == playerColor) 1.0 else 0.0
            GameController.GameResult.Stalemate, GameController.GameResult.RepetitionDraw -> 0.5
        }
        val headline = when (result) {
            is GameController.GameResult.Checkmate -> getString(R.string.wins, sideName(result.winner))
            is GameController.GameResult.PerpetualCheck -> getString(R.string.perpetual_check_loss, sideName(result.winner))
            GameController.GameResult.Stalemate -> getString(R.string.draw)
            GameController.GameResult.RepetitionDraw -> getString(R.string.repetition_draw)
        }
        val message = StringBuilder(headline)
        if (vsAI && isRatedGame()) {
            val change = RatingSystem.recordGame(this, gameController.getDifficulty(), playerScore)
            val stats = RatingSystem.getStats(this)
            val sign = if (change >= 0) "+" else ""
            message.append(getString(R.string.rating_summary, stats.rating.toString(), "$sign$change", stats.rankTitle))
        }
        val suggestion = if (vsAI) levelSuggestion(playerScore) else null
        if (suggestion != null) {
            val name = resources.getStringArray(R.array.difficulty_short)[suggestion.ordinal]
            val up = suggestion.ordinal > gameController.getDifficulty().ordinal
            message.append("\n\n").append(getString(if (up) R.string.level_up_suggest else R.string.level_down_suggest, name))
        }

        val builder = AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.game_over)
            .setMessage(message)
            .setPositiveButton(R.string.play_again) { _, _ ->
                gameController.startNewGame()
                updateGameModeDisplay()
            }
        if (vsAI && gameController.getMoveHistory().any { it.piece.color == playerColor }) {
            builder.setNegativeButton(R.string.review_mistake) { _, _ -> reviewMistake(playerColor) }
        }
        if (suggestion != null) {
            val name = resources.getStringArray(R.array.difficulty_short)[suggestion.ordinal]
            builder.setNeutralButton(getString(R.string.level_switch, name)) { _, _ -> switchLevel(suggestion) }
        } else {
            builder.setNeutralButton(R.string.close, null)
        }
        builder.show()
    }

    /**
     * Keeps a running streak at the current level: two wins in a row suggest the level above,
     * three losses in a row the level below. A draw or a change of level starts it again.
     */
    private fun levelSuggestion(score: Double): AIDifficulty? {
        val level = gameController.getDifficulty()
        var streak = if (settings.getString(KEY_STREAK_LEVEL, null) == level.name) settings.getInt(KEY_STREAK, 0) else 0
        streak = when {
            score >= 1.0 -> maxOf(streak, 0) + 1
            score <= 0.0 -> minOf(streak, 0) - 1
            else -> 0
        }
        settings.edit().putString(KEY_STREAK_LEVEL, level.name).putInt(KEY_STREAK, streak).apply()
        val levels = AIDifficulty.values()
        return when {
            streak >= 2 && level.ordinal < levels.size - 1 -> levels[level.ordinal + 1]
            streak <= -3 && level.ordinal > 0 -> levels[level.ordinal - 1]
            else -> null
        }
    }

    private fun switchLevel(level: AIDifficulty) {
        val mode = gameController.getGameMode()
        val aiColor = gameController.getAIColor()
        settings.edit().putString(KEY_DIFFICULTY, level.name).putInt(KEY_STREAK, 0).apply()
        gameController.destroy()
        gameController = GameController(this, level, audioManager)
        setupGameControllerCallbacks()
        gameController.setGameMode(mode, aiColor)
        gameController.startNewGame()
        updateGameModeDisplay()
    }

    /** Finds the player's costliest move, opens the replay just before it and draws the better move. */
    private fun reviewMistake(player: PieceColor) {
        val working = Snackbar.make(boardView, R.string.review_running, Snackbar.LENGTH_INDEFINITE)
        working.show()
        val moves = gameController.getMoveHistory()
        gameController.findBiggestMistake(player) { mistake ->
            runOnUiThread {
                working.dismiss()
                if (mistake == null) {
                    Snackbar.make(boardView, R.string.review_none, Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                var before = gameController.getInitialBoard().copy()
                for (i in 0 until mistake.index) before = before.makeMove(moves[i])
                if (!gameController.isInReplayMode()) gameController.enterReplayMode()
                gameController.replayGoTo(mistake.index)
                updateGameModeDisplay()
                updateReplayBar()
                boardView.showSuggestion(mistake.better)

                val round = mistake.index / 2 + 1
                val played = notation(mistake.played, before)
                val from = mistake.before?.let { evalSummary(it) } ?: "?"
                val to = mistake.after?.let { evalSummary(it) } ?: "?"
                val text = mistake.better?.let {
                    getString(R.string.review_result, round, played, from, to, notation(it, before))
                } ?: getString(R.string.review_result_plain, round, played, from, to)
                Snackbar.make(boardView, text, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.review_ok) { boardView.showSuggestion(null) }
                    .setTextMaxLines(5)
                    .show()
            }
        }
    }

    // ── Dialogs ──

    /** A game with moves on the board and no result yet. */
    private fun gameInProgress(): Boolean {
        return gameController.getMoveHistory().isNotEmpty() && !gameController.isGameOver()
    }

    /** Runs [action] at once, or after the player agrees to give up the game in progress. */
    private fun confirmAbandonThen(action: () -> Unit) {
        if (!gameInProgress()) {
            action()
            return
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.abandon_title)
            .setMessage(R.string.abandon_message)
            .setPositiveButton(R.string.abandon_confirm) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNewGameDialog() {
        val modes = arrayOf(
            getString(R.string.play_red_vs_ai),
            getString(R.string.play_black_vs_ai),
            getString(R.string.two_players),
            getString(R.string.watch_ai),
            getString(R.string.endgame_practice)
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.mode_select_title)
            .setAdapter(styledListAdapter(modes)) { _, which ->
                when (which) {
                    0 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_AI, PieceColor.BLACK)
                        showDifficultyDialog()
                    }
                    1 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_AI, PieceColor.RED)
                        showDifficultyDialog()
                    }
                    2 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_PLAYER)
                        gameController.startNewGame()
                        updateGameModeDisplay()
                    }
                    3 -> {
                        gameController.setGameMode(GameMode.AI_VS_AI)
                        showDifficultyDialog()
                    }
                    4 -> showEndgameDialog()
                }
            }
            .show()
    }

    private fun showEndgameDialog() {
        val names = EndgamePositions.positions
            .map { getString(R.string.endgame_item, it.name, it.description) }
            .toTypedArray()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.endgame_select_title)
            .setAdapter(styledListAdapter(names)) { _, which ->
                gameController.startEndgamePosition(EndgamePositions.positions[which])
                updateGameModeDisplay()
            }
            .show()
    }

    private fun updateReplayBar() {
        val replaying = gameController.isInReplayMode()
        replayBar.visibility = if (replaying) View.VISIBLE else View.GONE
        moveStrip.visibility = if (replaying) View.GONE else View.VISIBLE
        if (replaying) {
            replayProgressText.text = getString(
                R.string.replay_counter, gameController.getReplayIndex(), gameController.getReplayLength()
            )
        }
    }

    private fun exitReplay() {
        boardView.showSuggestion(null)
        gameController.exitReplayMode()
        updateGameModeDisplay()
        updateReplayBar()
    }

    /** English uses the WXF letters (C2=5); Chinese the four-character notation (炮二平五). */
    private val westernNotation by lazy { resources.getBoolean(R.bool.western_notation) }

    private fun notation(move: com.yingwang.chinesechess.model.Move, boardBefore: com.yingwang.chinesechess.model.Board): String =
        if (westernNotation) MoveNotation.formatWestern(move, boardBefore) else MoveNotation.format(move, boardBefore)

    private fun preferredDifficulty(): AIDifficulty? =
        settings.getString(KEY_DIFFICULTY, null)?.let { name -> AIDifficulty.values().firstOrNull { it.name == name } }

    /** Challenge games count towards the rating and allow no hints or take-backs; practice games are the opposite. */
    private val challengeMode: Boolean get() = settings.getBoolean(KEY_CHALLENGE, false)

    /** Whether hints and undo are withheld in the game on the board. */
    private fun isRatedGame(): Boolean =
        challengeMode && gameController.getGameMode() == GameMode.PLAYER_VS_AI && !gameController.isEndgameMode()

    /**
     * Picks a level (each with a line on who it suits) and practice or challenge, rebuilds the
     * controller with them and starts a fresh game in the current mode. On the first launch the
     * same dialog asks the player's level; leaving it keeps 初级.
     */
    private fun showDifficultyDialog(firstRun: Boolean = false) {
        val names = resources.getStringArray(R.array.difficulty_short)
        val notes = resources.getStringArray(R.array.difficulty_notes)
        val items = names.indices.map { i ->
            SpannableStringBuilder(names[i]).apply {
                append("\n")
                val start = length
                append(notes[i])
                setSpan(RelativeSizeSpan(0.8f), start, length, 0)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary)),
                    start, length, 0
                )
            }
        }.toTypedArray<CharSequence>()
        var chosen = (preferredDifficulty() ?: gameController.getDifficulty()).ordinal
        val currentMode = gameController.getGameMode()
        val currentAIColor = gameController.getAIColor()

        val dp = resources.displayMetrics.density
        val challengeSwitch = SwitchMaterial(this).apply {
            setText(R.string.challenge_mode)
            isChecked = challengeMode
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
        }
        val challengeNote = TextView(this).apply {
            setText(R.string.challenge_mode_note)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary))
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (4 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(challengeSwitch)
            addView(challengeNote)
        }

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(if (firstRun) R.string.difficulty_first_title else R.string.difficulty_title)
            .setSingleChoiceItems(items, chosen) { _, which -> chosen = which }
            .setView(footer)
            .setPositiveButton(R.string.ok) { _, _ ->
                val difficulty = AIDifficulty.values()[chosen]
                settings.edit()
                    .putString(KEY_DIFFICULTY, difficulty.name)
                    .putBoolean(KEY_CHALLENGE, challengeSwitch.isChecked)
                    .apply()

                gameController.destroy()
                gameController = GameController(this@MainActivity, difficulty, audioManager)
                setupGameControllerCallbacks()

                gameController.setGameMode(currentMode, currentAIColor)
                gameController.startNewGame()
                updateGameModeDisplay()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (firstRun) settings.edit().putString(KEY_DIFFICULTY, gameController.getDifficulty().name).apply()
            }
            .setOnCancelListener {
                if (firstRun) settings.edit().putString(KEY_DIFFICULTY, gameController.getDifficulty().name).apply()
            }
            .show()
    }

    private fun changeDifficulty() {
        if (gameController.getMoveHistory().isEmpty()) {
            showDifficultyDialog()
            return
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.difficulty_restart_title)
            .setMessage(R.string.difficulty_restart_message)
            .setPositiveButton(R.string.ok) { _, _ -> showDifficultyDialog() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun styledListAdapter(items: Array<String>): ListAdapter {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
                    textSize = 16f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    setBackgroundColor(Color.TRANSPARENT)
                }
                return view
            }
        }
    }

    private fun showMoreDialog() {
        val items = arrayOf(
            getString(if (isMuted) R.string.unmute else R.string.mute),
            getString(R.string.change_difficulty),
            getString(R.string.export),
            getString(R.string.my_stats),
            getString(R.string.replay_title),
            getString(R.string.about)
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.more)
            .setAdapter(styledListAdapter(items)) { _, which ->
                when (which) {
                    0 -> toggleMute()
                    1 -> changeDifficulty()
                    2 -> exportMoveHistory()
                    3 -> showStatsDialog()
                    4 -> toggleReplay()
                    5 -> showAboutDialog()
                }
            }
            .show()
    }

    private fun toggleReplay() {
        if (gameController.isInReplayMode()) {
            exitReplay()
            toast(R.string.replay_exited)
        } else if (gameController.enterReplayMode()) {
            updateGameModeDisplay()
            updateReplayBar()
        } else {
            toast(R.string.replay_none)
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        settings.edit().putBoolean("muted", isMuted).apply()
        audioManager.setMuted(isMuted)
        toast(if (isMuted) R.string.muted_toast else R.string.unmuted_toast)
    }

    private fun showStatsDialog() {
        val stats = RatingSystem.getStats(this)
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.my_stats)
            .setMessage(
                getString(
                    R.string.stats_message,
                    stats.rankTitle, stats.rating, stats.games,
                    stats.wins, stats.losses, stats.draws, stats.winRate
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun exportMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            toast(R.string.export_none)
            return
        }

        val sb = StringBuilder()
        sb.appendLine(getString(R.string.export_title))
        sb.appendLine(getString(R.string.export_date, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())))
        sb.appendLine(getString(R.string.export_mode, modeDescription()))
        sb.appendLine(getString(R.string.export_moves, moves.size))
        sb.appendLine("─".repeat(30))
        sb.appendLine()

        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard(), westernNotation)
        moves.forEachIndexed { index, _ ->
            val moveNum = index / 2 + 1
            if (index % 2 == 0) {
                sb.append(String.format(Locale.US, "%2d. %-10s", moveNum, notations[index]))
            } else {
                sb.appendLine(String.format(Locale.US, "%-10s", notations[index]))
            }
        }
        if (moves.size % 2 == 1) sb.appendLine()

        sb.appendLine()
        sb.appendLine("─".repeat(30))

        val board = gameController.getCurrentBoard()
        val result = when {
            board.isCheckmate() -> getString(R.string.wins_short, sideName(board.currentPlayer.opposite()))
            board.isStalemate() -> getString(R.string.draw_short)
            else -> getString(R.string.result_unfinished)
        }
        sb.appendLine(getString(R.string.export_result, result))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_title))
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export)))
    }

    private fun showAboutDialog() {
        val verName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, verName, gameController.getAIStats()))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThinkingAnimation()
        gameController.destroy()
        audioManager.release()
    }
}
