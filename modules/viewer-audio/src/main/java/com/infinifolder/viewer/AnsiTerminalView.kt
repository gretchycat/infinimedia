package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import io.github.gretchycat.infiniscribe.DosTextDocument

/** Fixed 80-column CP437/Unicode terminal output for Infinimedia's visual-media pane. */
internal class AnsiTerminalView(context: Context) : View(context) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = loadTerminalTypeface(context)
    }
    private var document: DosTextDocument? = null
    private var rowStarts = IntArray(0)
    private var rowEnds = IntArray(0)
    private var elapsedBeforePause = 0L
    private var startedAt = 0L
    private var playing = false
    private var lastCompletedCells = -1
    private val frame = object : Runnable {
        override fun run() {
            if (!playing) return
            invalidate()
            if (completedCells() < totalCells()) postDelayed(this, 16L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        contentDescription = "ANSI terminal output"
    }

    fun setDocument(value: DosTextDocument?) {
        if (document === value) return
        playing = false
        removeCallbacks(frame)
        document = value
        elapsedBeforePause = 0L
        startedAt = 0L
        lastCompletedCells = -1
        if (value == null) {
            rowStarts = IntArray(0)
            rowEnds = IntArray(0)
            contentDescription = "ANSI terminal output"
        } else {
            val starts = IntArray(value.rows)
            val ends = IntArray(value.rows)
            var row = 0
            var start = 0
            value.text.forEachIndexed { index, c ->
                if (c == '\n' && row < value.rows) {
                    starts[row] = start
                    ends[row] = index
                    row++
                    start = index + 1
                }
            }
            if (row < value.rows) {
                starts[row] = start
                ends[row] = value.text.length
                row++
            }
            while (row < value.rows) {
                starts[row] = value.text.length
                ends[row] = value.text.length
                row++
            }
            rowStarts = starts
            rowEnds = ends
            contentDescription = "ANSI terminal output, ${value.columns} columns by ${value.rows} rows"
        }
        invalidate()
    }

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        if (value) {
            playing = true
            startedAt = android.os.SystemClock.elapsedRealtime()
            post(frame)
        } else {
            elapsedBeforePause += (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            playing = false
            removeCallbacks(frame)
            invalidate()
        }
    }

    fun resetOutput() {
        elapsedBeforePause = 0L
        startedAt = if (playing) android.os.SystemClock.elapsedRealtime() else 0L
        lastCompletedCells = -1
        invalidate()
    }

    fun setPlaybackPosition(positionMs: Long) {
        elapsedBeforePause = positionMs.coerceAtLeast(0L)
        startedAt = if (playing) android.os.SystemClock.elapsedRealtime() else 0L
        lastCompletedCells = -1
        invalidate()
    }

    private fun totalCells(): Int = document?.let { (it.rows.toLong() * it.columns).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0
    private fun elapsedOutputMs(): Long = elapsedBeforePause +
        if (playing) (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) else 0L

    private fun completedCells(): Int = AnsiPlaybackSupport.displayedCells(elapsedOutputMs(), totalCells())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val doc = document ?: return
        if (width <= 0 || height <= 0) return
        val cellWidth = width.toFloat() / doc.columns
        val cellHeight = cellWidth * 1.28f
        val visibleRows = (height / cellHeight).toInt().coerceAtLeast(1)
        val cells = completedCells()
        if (cells != lastCompletedCells) lastCompletedCells = cells
        val firstRow = AnsiPlaybackSupport.firstVisibleRow(cells, doc.rows, doc.columns, visibleRows)
        val lastRow = minOf(doc.rows, firstRow + visibleRows)
        val fontHeight = cellHeight * 0.82f
        textPaint.textSize = fontHeight
        textPaint.textScaleX = 1f
        textPaint.textScaleX = cellWidth / textPaint.measureText("M").coerceAtLeast(0.01f)
        val metrics = textPaint.fontMetrics

        for (row in firstRow until lastRow) {
            val visibleCells = (cells - row * doc.columns).coerceIn(0, doc.columns)
            if (visibleCells == 0) continue
            val offsets = cellOffsets(doc.text, rowStarts[row], rowEnds[row], visibleCells, doc.columns)
            val top = (row - firstRow) * cellHeight
            val baseline = top + (cellHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            var cell = 0
            while (cell < visibleCells) {
                val style = styleAt(doc, offsets[cell])
                var stop = cell + 1
                while (stop < visibleCells && sameStyle(style, styleAt(doc, offsets[stop]))) stop++
                val x = cell * cellWidth
                if (style?.background != null && style.background != Color.BLACK) {
                    textPaint.color = style.background
                    canvas.drawRect(x, top, stop * cellWidth, top + cellHeight, textPaint)
                }
                textPaint.color = style?.foreground ?: Color.WHITE
                canvas.drawText(doc.text, offsets[cell], offsets[stop], x, baseline, textPaint)
                cell = stop
            }
        }
    }

    private fun cellOffsets(text: String, start: Int, end: Int, count: Int, columns: Int): IntArray {
        val offsets = IntArray(columns + 1)
        var at = start
        for (cell in 0 until columns) {
            offsets[cell] = at
            if (cell >= count || at >= end) {
                offsets[cell + 1] = at
                continue
            }
            val codePoint = text.codePointAt(at)
            at += Character.charCount(codePoint)
            while (at < end) {
                val next = text.codePointAt(at)
                val type = Character.getType(next)
                if (type != Character.NON_SPACING_MARK.toInt() &&
                    type != Character.COMBINING_SPACING_MARK.toInt() &&
                    type != Character.ENCLOSING_MARK.toInt()) break
                at += Character.charCount(next)
            }
            offsets[cell + 1] = at
        }
        offsets[columns] = at
        return offsets
    }

    private fun styleAt(doc: DosTextDocument, offset: Int) = doc.runs.binarySearchBy(offset) { it.start }
        .let { index ->
            val candidate = doc.runs.getOrNull(if (index >= 0) index else -index - 2)
            candidate?.takeIf { offset in it.start until it.end }
        }

    private fun sameStyle(a: io.github.gretchycat.infiniscribe.DosTextRun?, b: io.github.gretchycat.infiniscribe.DosTextRun?): Boolean =
        a?.foreground == b?.foreground && a?.background == b?.background

    private fun loadTerminalTypeface(context: Context): Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/cp437.ttf")
    } catch (_: Exception) {
        Typeface.MONOSPACE
    }
}
