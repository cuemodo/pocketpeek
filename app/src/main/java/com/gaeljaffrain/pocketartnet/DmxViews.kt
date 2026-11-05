package com.gaeljaffrain.pocketartnet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** consolelink's DMX tab palette (src/consolelink/static/style.css :root). */
object Palette {
    val BG = Color.parseColor("#17181c")
    val PANEL = Color.parseColor("#1f2126")
    val GRID = Color.parseColor("#2a2d34")
    val TEXT = Color.parseColor("#d9dbe1")
    val DARKTEXT = Color.parseColor("#0b0c0e")
    val WELL = Color.parseColor("#0e0f12")
    val DIM = Color.parseColor("#9498a6")
    val OK = Color.parseColor("#5cff8f")
    val BAD = Color.parseColor("#ff5c5c")
    val WARN = Color.parseColor("#ffb020")
    val GREEN = Color.parseColor("#00ff00")
    /** Selection outline (current page, tapped cell): magenta is opposite green, so it reads on a full-green tab and on an empty one. */
    val SELECT = Color.parseColor("#ff40ff")

    /** Heat map: accent green faded in with the level, over the background (color-mix in the CSS). */
    fun heat(level: Int) = Color.argb(level, 0, 255, 0)
}

enum class DisplayMode(val label: String, format: (Int) -> String) {
    PCT("%", { v -> pct(v).let { if (it == 100) "F" else it.toString() } }),
    DEC("255", { v -> v.toString() }),
    HEX("HEX", { v -> "%02X".format(v) });

    /** Zero is blank, as in consolelink, so the live channels stand out. */
    val labels: Array<String> = Array(256) { v -> if (v == 0) "" else format(v) }

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** 0..255 to 0..100, as consolelink's pct(). Top level so the enum constructors can use it. */
fun pct(v: Int) = (v * 100 / 255.0).roundToInt()

private fun View.dp(v: Float) = v * resources.displayMetrics.density

/** Selection ring: thick magenta with a thin dark line inside it, separating it from any fill. Insets [rect]. */
private fun View.drawSelectRing(canvas: Canvas, rect: RectF, radius: Float, paint: Paint) {
    val sw = dp(3f)
    paint.color = Palette.SELECT
    paint.strokeWidth = sw
    rect.inset(sw / 2 - 1, sw / 2 - 1)
    canvas.drawRoundRect(rect, radius, radius, paint)
    val inner = dp(1f)
    rect.inset(sw / 2 + inner / 2, sw / 2 + inner / 2)
    paint.color = Palette.DARKTEXT
    paint.strokeWidth = inner
    canvas.drawRoundRect(rect, radius - sw / 2, radius - sw / 2, paint)
}

/**
 * One page of channels as a grid, drawn like consolelink's .dmx-cell. The shape adapts to the space
 * ([GridShape.choose]): 4 x 8 on the R1's narrow screen, up to 32 x 16 (a whole universe) on a big one.
 * Wide cells put the channel number and level side by side; squarer cells stack them.
 */
@SuppressLint("ViewConstructor")
class DmxGridView(ctx: Context) : View(ctx) {
    val values = ByteArray(512)
    var firstChannel = 1
    var mode = DisplayMode.PCT
    /** Selected cell on this page, or -1. */
    var selected = -1
    var shape = GridShape.SMALLEST
        private set
    var onCellTap: ((Int) -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null
    /** Called (posted) when the size picks a different [shape]. */
    var onShape: ((GridShape) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val addrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        fontFeatureSettings = "tnum"
    }
    private val lvlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()
    private val addrLabels = Array(513) { it.toString() }
    private val gapDp = 3f
    private val gap = dp(gapDp)
    private val radius = dp(4f)
    private var cellW = 0f
    private var cellH = 0f
    private var wide = true

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val c = (e.x / (cellW + gap)).toInt()
            val r = (e.y / (cellH + gap)).toInt()
            if (c in 0 until shape.cols && r in 0 until shape.rows) onCellTap?.invoke(r * shape.cols + c)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > 300) onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fill the space the layout gives (width and the remaining height).
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val density = resources.displayMetrics.density
        val s = GridShape.choose(w / density, h / density, gapDp)
        val changed = s != shape
        shape = s
        cellW = (w - gap * (s.cols - 1)) / s.cols
        cellH = (h - gap * (s.rows - 1)) / s.rows
        wide = cellW / cellH >= 1.6f
        if (wide) {
            // Channel number left, level right, on one line; capped so "512" + "255" fit side by side.
            addrPaint.textSize = min(cellH * 0.42f, cellW * 0.20f)
            lvlPaint.textSize = min(cellH * 0.62f, cellW * 0.28f)
            lvlPaint.textAlign = Paint.Align.RIGHT
        } else {
            // Channel number small in the corner, level centred below it.
            val c = min(cellW, cellH * 1.25f)
            addrPaint.textSize = c * 0.24f
            lvlPaint.textSize = c * 0.40f
            lvlPaint.textAlign = Paint.Align.CENTER
        }
        if (changed) post { onShape?.invoke(s) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val labels = mode.labels
        val cols = shape.cols
        val padX = if (wide) cellW * 0.08f else min(cellW, cellH) * 0.08f
        val addrBaseline = if (wide) cellH / 2 - (addrPaint.ascent() + addrPaint.descent()) / 2 else padX - addrPaint.ascent()
        val lvlBaseline = if (wide) cellH / 2 - (lvlPaint.ascent() + lvlPaint.descent()) / 2
            else cellH * 0.60f - (lvlPaint.ascent() + lvlPaint.descent()) / 2
        val lvlX = if (wide) cellW - padX else cellW / 2
        for (i in 0 until shape.cells) {
            val x = (i % cols) * (cellW + gap)
            val y = (i / cols) * (cellH + gap)
            val v = values[i].toInt() and 0xff
            val bright = v >= 128
            rect.set(x + 0.5f, y + 0.5f, x + cellW - 0.5f, y + cellH - 0.5f)

            if (v > 0) {
                fill.color = Palette.heat(v)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            if (i == selected) {
                drawSelectRing(canvas, rect, radius, stroke)
            } else {
                stroke.color = if (v > 0) Palette.GREEN else Palette.GRID
                stroke.strokeWidth = dp(1f)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }

            addrPaint.color = when {
                bright -> Palette.DARKTEXT
                v > 0 -> (Palette.TEXT and 0x00ffffff) or (0xB3 shl 24)  // 70% opacity
                else -> Palette.DIM
            }
            canvas.drawText(addrLabels[firstChannel + i], x + padX, y + addrBaseline, addrPaint)

            if (v > 0) {
                lvlPaint.color = if (bright) Palette.DARKTEXT else Palette.TEXT
                canvas.drawText(labels[v], x + lvlX, y + lvlBaseline, lvlPaint)
            }
        }
    }
}

/** One tab per page, at most 8 per row; each tab's fill shows the highest level on that page, the current one is outlined. */
@SuppressLint("ViewConstructor")
class PageBarView(ctx: Context) : View(ctx) {
    val pageMax = IntArray(32)
    var current = 0
    var pages = GridShape.SMALLEST.pages
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var onPage: ((Int) -> Unit)? = null

    private val perRow get() = min(pages, MAX_PER_ROW)
    private val rows get() = (pages + perRow - 1) / perRow

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()
    private val gap = dp(3f)
    private val radius = dp(4f)
    private val rowH = dp(30f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rowH * rows + gap * (rows - 1)).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Tap or drag along the bar to scrub through pages.
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val c = (event.x / (width / perRow.toFloat())).toInt().coerceIn(0, perRow - 1)
            val r = (event.y / (height / rows.toFloat())).toInt().coerceIn(0, rows - 1)
            val p = (r * perRow + c).coerceIn(0, pages - 1)
            if (p != current) onPage?.invoke(p)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val per = perRow
        val w = (width - gap * (per - 1)) / per
        val h = rowH
        text.textSize = min(h * 0.6f, w * 0.45f)
        for (p in 0 until pages) {
            val x = (p % per) * (w + gap)
            val y = (p / per) * (h + gap)
            rect.set(x + 1, y + 1, x + w - 1, y + h - 1)
            fill.color = Palette.PANEL
            canvas.drawRoundRect(rect, radius, radius, fill)
            val m = pageMax[p]
            if (m > 0) {
                fill.color = Palette.heat(m)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            val isCur = p == current
            if (isCur) {
                drawSelectRing(canvas, rect, radius, stroke)
            } else {
                stroke.color = if (m > 0) Palette.GREEN else Palette.GRID
                stroke.strokeWidth = dp(1f)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
            text.color = when {
                m >= 128 -> Palette.DARKTEXT
                isCur -> Palette.TEXT
                else -> Palette.DIM
            }
            canvas.drawText((p + 1).toString(), x + w / 2, y + h / 2 - (text.ascent() + text.descent()) / 2, text)
        }
    }

    companion object {
        const val MAX_PER_ROW = 8
    }
}
