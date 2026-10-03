package com.yingwang.chinesechess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.yingwang.chinesechess.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the game went, in pawns: one point per position, red's lead above the zero line and
 * black's below, on a scale that fits the game (±2 up to ±20 pawns, a mate pinned to the edge).
 * The gutter on the left labels the scale. The costliest move is a band; each mistake (amber)
 * or blunder (red) colours the stretch of curve it caused and puts a dot where it was played,
 * so landing on the dot shows that move. The position on the board is a vertical line.
 * Touching picks a position, and dragging scrubs through the game.
 */
class EvalGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** How bad a move was, for its dot on the curve. */
    enum class Mark { MISTAKE, BLUNDER }

    private var pawns: List<Float?> = emptyList()
    private var range = 2
    private var marks: Map<Int, Mark> = emptyMap()
    private var bandIndex: Int? = null
    private var currentIndex: Int? = null
    private var onPick: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val redColor = ContextCompat.getColor(context, R.color.chess_red_side)
    private val blackColor = ContextCompat.getColor(context, R.color.chess_black_side)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel_stroke)
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel_stroke)
        strokeWidth = 1f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * dp, 4f * dp), 0f)
    }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, Color.red(redColor), Color.green(redColor), Color.blue(redColor))
    }
    private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, Color.red(blackColor), Color.green(blackColor), Color.blue(blackColor))
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_text)
        strokeWidth = 1.75f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val amber = Color.rgb(230, 150, 40)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 230, 150, 40)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val markRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel)
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_accent)
        strokeWidth = 2f * dp
    }
    private val currentDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_accent)
    }
    private val redLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = redColor
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    private val blackLabelPaint = Paint(redLabelPaint).apply {
        color = ContextCompat.getColor(context, R.color.chess_text_secondary)
    }

    /**
     * @param evals red-side readings per position, as (centipawns, moves to mate); nulls are gaps.
     * @param marks the moves to mark, by the index of the position they were played from.
     * @param band the position before the costliest move, shaded up to the next one.
     */
    fun setData(evals: List<Pair<Int?, Int?>?>, marks: Map<Int, Mark>, band: Int?, current: Int?) {
        val readings = evals.map { e -> if (e?.first == null || e.second != null) null else e.first!! / 100f }
        val worst = readings.filterNotNull().maxOfOrNull { abs(it) } ?: 0f
        range = SCALES.firstOrNull { it >= worst } ?: SCALES.last()
        pawns = evals.mapIndexed { i, e ->
            when {
                e == null -> null
                e.second != null -> if (e.second!! > 0) range.toFloat() else -range.toFloat()
                else -> readings[i]?.coerceIn(-range.toFloat(), range.toFloat())
            }
        }
        this.marks = marks
        bandIndex = band
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

    private fun tickLabel(value: Int): String = when {
        value > 0 -> context.getString(R.string.graph_red_tick, value)
        value < 0 -> context.getString(R.string.graph_black_tick, -value)
        else -> "0"
    }

    private val pad get() = 8f * dp
    // The scale is labelled in a gutter of its own, so a curve hugging an edge never runs
    // through the labels.
    private val left get() = pad + redLabelPaint.measureText(tickLabel(SCALES.last())) + 6f * dp
    private val top get() = pad + 4f * dp
    private val bottom get() = height - pad - 4f * dp

    private fun xOf(i: Int): Float {
        val n = (pawns.size - 1).coerceAtLeast(1)
        return left + (width - left - pad) * i / n
    }

    private fun yOf(value: Float): Float = top + (bottom - top) * (range - value) / (2f * range)

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

        // The scale: zero, half and full range each way, the halves dropped when there is
        // no room for five labels.
        val ticks = if (bottom - top > 5 * 1.6f * redLabelPaint.textSize) {
            listOf(range, range / 2, 0, -range / 2, -range).distinct()
        } else listOf(range, 0, -range)
        val textNudge = redLabelPaint.textSize * 0.35f
        for (t in ticks) {
            val y = yOf(t.toFloat())
            canvas.drawLine(left, y, w - pad, y, if (t == 0) zeroPaint else gridPaint)
            canvas.drawText(tickLabel(t), left - 6f * dp, y + textNudge, if (t >= 0) redLabelPaint else blackLabelPaint)
        }
        if (pawns.size < 2) return

        bandIndex?.let { i ->
            if (i + 1 < pawns.size) canvas.drawRect(xOf(i), top, xOf(i + 1), bottom, bandPaint)
        }

        // Fill each side of the zero line, then the line itself, skipping gaps.
        val zero = yOf(0f)
        val line = Path()
        var started = false
        for ((i, v) in pawns.withIndex()) {
            if (v == null) {
                started = false
                continue
            }
            if (!started) line.moveTo(xOf(i), yOf(v)) else line.lineTo(xOf(i), yOf(v))
            started = true
        }
        for ((i, v) in pawns.withIndex()) {
            val next = pawns.getOrNull(i + 1) ?: continue
            v ?: continue
            val segment = Path().apply {
                moveTo(xOf(i), zero)
                lineTo(xOf(i), yOf(v))
                lineTo(xOf(i + 1), yOf(next))
                lineTo(xOf(i + 1), zero)
                close()
            }
            canvas.save()
            canvas.clipRect(0f, 0f, w, zero)
            canvas.drawPath(segment, redFill)
            canvas.restore()
            canvas.save()
            canvas.clipRect(0f, zero, w, h)
            canvas.drawPath(segment, blackFill)
            canvas.restore()
        }
        canvas.drawPath(line, linePaint)

        val radius = 3.5f * dp
        for ((i, mark) in marks) {
            val v = pawns.getOrNull(i) ?: continue
            val colour = if (mark == Mark.BLUNDER) redColor else amber
            pawns.getOrNull(i + 1)?.let { next ->
                markLine.color = colour
                canvas.drawLine(xOf(i), yOf(v), xOf(i + 1), yOf(next), markLine)
            }
            markPaint.color = colour
            canvas.drawCircle(xOf(i), yOf(v), radius, markPaint)
            canvas.drawCircle(xOf(i), yOf(v), radius, markRing)
        }

        currentIndex?.let { i ->
            if (i in pawns.indices) {
                canvas.drawLine(xOf(i), top, xOf(i), bottom, currentPaint)
                pawns[i]?.let { v -> canvas.drawCircle(xOf(i), yOf(v), 3f * dp, currentDot) }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pawns.size < 2) return false
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
        val n = pawns.size - 1
        val i = (((x - left) / (width - left - pad)) * n).roundToInt().coerceIn(0, n)
        if (i == currentIndex) return
        currentIndex = i
        invalidate()
        onPick?.invoke(i)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        /** Scales the graph can take, in pawns each way: the smallest that holds the game. */
        private val SCALES = listOf(2, 4, 6, 10, 16, 20)
    }
}
