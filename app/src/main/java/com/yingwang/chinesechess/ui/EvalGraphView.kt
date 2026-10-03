package com.yingwang.chinesechess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.yingwang.chinesechess.R
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * How the game went: one point per position, red's share of the game on the same logistic
 * curve as the evaluation bar (so +100 cp is about 59%, a mate pins it to the edge). Above
 * the middle line red is better, below it black. The costliest move is marked in amber and
 * the position on the board, if any, by a vertical line. Touching picks a position, and
 * dragging scrubs through the game, picking each position the finger passes.
 */
class EvalGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var shares: List<Float?> = emptyList()
    private var mistakeIndex: Int? = null
    private var currentIndex: Int? = null
    private var onPick: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val redColor = ContextCompat.getColor(context, R.color.chess_red_side)
    private val blackColor = ContextCompat.getColor(context, R.color.chess_black_side)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel)
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel_stroke)
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, Color.red(redColor), Color.green(redColor), Color.blue(redColor))
    }
    private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, Color.red(blackColor), Color.green(blackColor), Color.blue(blackColor))
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_text)
        strokeWidth = 2f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val mistakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 150, 40)
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
    }
    private val mistakeBand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 230, 150, 40)
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_accent)
        strokeWidth = 2f * dp
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_text_secondary)
        textSize = 11f * dp
    }
    private val redLabel = context.getString(R.string.analysis_red)
    private val blackLabel = context.getString(R.string.analysis_black)

    /** @param evals red-side readings per position, as (centipawns, moves to mate); nulls are gaps. */
    fun setData(evals: List<Pair<Int?, Int?>?>, mistake: Int?, current: Int?) {
        shares = evals.map { e ->
            when {
                e == null -> null
                e.second != null -> if (e.second!! > 0) 0.98f else 0.02f
                e.first != null -> (1f / (1f + exp(-0.00368208f * e.first!!))).coerceIn(0.02f, 0.98f)
                else -> null
            }
        }
        mistakeIndex = mistake
        currentIndex = current
        invalidate()
    }

    fun setCurrent(index: Int?) {
        currentIndex = index
        invalidate()
    }

    fun setOnPickListener(listener: (Int) -> Unit) {
        onPick = listener
    }

    private val pad get() = 8f * dp
    private fun xOf(i: Int): Float {
        val n = (shares.size - 1).coerceAtLeast(1)
        return pad + (width - 2 * pad) * i / n
    }
    private fun yOf(share: Float): Float = pad + (height - 2 * pad) * (1f - share)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize((160 * dp).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 10f * dp, 10f * dp, panelPaint)
        val mid = yOf(0.5f)
        canvas.drawLine(pad, mid, w - pad, mid, midPaint)
        canvas.drawText(redLabel, pad + 2 * dp, pad + labelPaint.textSize, labelPaint)
        canvas.drawText(blackLabel, pad + 2 * dp, h - pad - 2 * dp, labelPaint)
        if (shares.size < 2) return

        mistakeIndex?.let { i ->
            if (i + 1 < shares.size) canvas.drawRect(xOf(i), pad, xOf(i + 1), h - pad, mistakeBand)
        }

        // Fill each side of the middle line, then the line itself, skipping gaps.
        val above = Path()
        val below = Path()
        val line = Path()
        var started = false
        for ((i, s) in shares.withIndex()) {
            if (s == null) {
                started = false
                continue
            }
            val x = xOf(i)
            val y = yOf(s)
            if (!started) line.moveTo(x, y) else line.lineTo(x, y)
            started = true
        }
        for ((i, s) in shares.withIndex()) {
            val next = shares.getOrNull(i + 1) ?: continue
            s ?: continue
            val x0 = xOf(i)
            val x1 = xOf(i + 1)
            val y0 = yOf(s)
            val y1 = yOf(next)
            val segment = Path().apply {
                moveTo(x0, mid)
                lineTo(x0, y0)
                lineTo(x1, y1)
                lineTo(x1, mid)
                close()
            }
            canvas.save()
            canvas.clipRect(0f, 0f, w, mid)
            canvas.drawPath(segment, redFill)
            canvas.restore()
            canvas.save()
            canvas.clipRect(0f, mid, w, h)
            canvas.drawPath(segment, blackFill)
            canvas.restore()
        }
        canvas.drawPath(line, linePaint)

        mistakeIndex?.let { i ->
            val a = shares.getOrNull(i)
            val b = shares.getOrNull(i + 1)
            if (a != null && b != null) canvas.drawLine(xOf(i), yOf(a), xOf(i + 1), yOf(b), mistakePaint)
        }
        currentIndex?.let { i ->
            if (i in shares.indices) canvas.drawLine(xOf(i), pad, xOf(i), h - pad, currentPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (shares.size < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A sideways drag is a scrub, not a scroll of whatever holds the graph.
                parent?.requestDisallowInterceptTouchEvent(true)
                pickAt(event.x)
            }
            MotionEvent.ACTION_MOVE -> pickAt(event.x)
            MotionEvent.ACTION_UP -> {
                pickAt(event.x)
                performClick()
            }
        }
        return true
    }

    private fun pickAt(x: Float) {
        val n = shares.size - 1
        val i = (((x - pad) / (width - 2 * pad)) * n).roundToInt().coerceIn(0, n)
        if (i == currentIndex) return
        currentIndex = i
        invalidate()
        onPick?.invoke(i)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
