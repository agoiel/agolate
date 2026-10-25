package com.manhwatranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.manhwatranslator.core.Bubble
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ==================================================================== плавающая кнопка

/**
 * Круглая кнопка поверх всех приложений.
 * Нажатие — перевести / скрыть перевод. Долгое нажатие — выделить новую область.
 * Перетаскивание — подвинуть кнопку.
 */
@SuppressLint("ViewConstructor")
class FloatingButton(
    ctx: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onMoveEnd: () -> Unit,
) : View(ctx) {
    val sizePx = ctx.dpF(58f).toInt()
    var busy = false
        set(v) { field = v; invalidate() }
    var showingTranslation = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpF(2f)
        color = Brand.CREAM
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 40, 25, 20) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
        textSize = ctx.dpF(17f)
    }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longFired = false
    private val longPress = Runnable { longFired = true; performHapticFeedback(0); onLongPress() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(c: Canvas) {
        val r = sizePx / 2f
        val body = r - context.dpF(4f)
        // мягкая тень
        c.drawCircle(r, r + context.dpF(1.5f), body + context.dpF(1f), shadow)
        fill.color = when {
            busy -> Brand.SAND
            showingTranslation -> Brand.BOW
            else -> Brand.COCOA
        }
        ring.color = Brand.CREAM
        c.drawCircle(r, r, body, fill)
        c.drawCircle(r, r, body, ring)
        val label = when {
            busy -> "…"
            showingTranslation -> "✕"
            else -> "Аа"
        }
        text.color = if (busy) Brand.INK else Color.WHITE
        c.drawText(label, r, r - (text.descent() + text.ascent()) / 2, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                dragging = false; longFired = false
                uiHandler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                    dragging = true
                    uiHandler.removeCallbacks(longPress)
                }
                if (dragging) {
                    onMove((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(longPress)
                if (dragging) onMoveEnd() else if (!longFired) onTap()
            }
            MotionEvent.ACTION_CANCEL -> uiHandler.removeCallbacks(longPress)
        }
        return true
    }
}

// ==================================================================== выделение области

/** Полноэкранный слой: тянете пальцем прямоугольник области чтения. */
@SuppressLint("ViewConstructor")
class RegionSelectView(
    ctx: Context,
    private val onSelected: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : View(ctx) {
    private val dim = Paint().apply { color = Color.argb(150, 33, 24, 22) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpF(2f)
        color = Brand.CREAM
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpF(4f)
        strokeCap = Paint.Cap.ROUND
        color = Brand.SAND
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.INK }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.CREAM
        textAlign = Paint.Align.CENTER
        textSize = ctx.dpF(15f)
        typeface = Fonts.medium
    }
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var active = false
    private val loc = IntArray(2)

    private fun rect() = RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!active) {
            c.drawRect(0f, 0f, w, h, dim)
            drawHint(c, w / 2, h * 0.38f, "Выделите пальцем область чтения")
            drawHint(c, w / 2, h * 0.38f + context.dpF(48f), "Короткое касание — отмена")
            return
        }
        val r = rect()
        c.drawRect(0f, 0f, w, r.top, dim)
        c.drawRect(0f, r.bottom, w, h, dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, w, r.bottom, dim)
        c.drawRect(r, border)
        // уголки-«скобки»
        val k = min(context.dpF(22f), min(r.width(), r.height()) / 3)
        c.drawLine(r.left, r.top, r.left + k, r.top, corner); c.drawLine(r.left, r.top, r.left, r.top + k, corner)
        c.drawLine(r.right, r.top, r.right - k, r.top, corner); c.drawLine(r.right, r.top, r.right, r.top + k, corner)
        c.drawLine(r.left, r.bottom, r.left + k, r.bottom, corner); c.drawLine(r.left, r.bottom, r.left, r.bottom - k, corner)
        c.drawLine(r.right, r.bottom, r.right - k, r.bottom, corner); c.drawLine(r.right, r.bottom, r.right, r.bottom - k, corner)
    }

    private fun drawHint(c: Canvas, cx: Float, cy: Float, msg: String) {
        val tw = hint.measureText(msg)
        val padH = context.dpF(18f)
        val hh = context.dpF(36f)
        val rr = RectF(cx - tw / 2 - padH, cy - hh / 2, cx + tw / 2 + padH, cy + hh / 2)
        c.drawRoundRect(rr, hh / 2, hh / 2, pill)
        c.drawText(msg, cx, cy - (hint.descent() + hint.ascent()) / 2, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; active = true; invalidate() }
            MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y; invalidate() }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y
                val r = rect()
                if (r.width() < context.dpF(40f) || r.height() < context.dpF(40f)) {
                    onCancel()
                } else {
                    // в координаты экрана (слой может начинаться не с 0,0)
                    getLocationOnScreen(loc)
                    onSelected(Rect((r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(),
                        (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt()))
                }
            }
        }
        return true
    }
}

// ==================================================================== перевод поверх облаков

/** Рисует плашки с переводом поверх облаков. Не перехватывает касания. */
class TranslationView(ctx: Context) : View(ctx) {
    private var bubbles: List<Bubble> = emptyList()
    private var region = Rect()
    private var maxFontPx = ctx.dpF(16f)
    private val minFontPx = ctx.dpF(8f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val loc = IntArray(2)

    val hasContent: Boolean get() = bubbles.any { it.translation.isNotBlank() }

    fun show(bubbles: List<Bubble>, region: Rect, maxFontSp: Int) {
        this.bubbles = bubbles
        this.region = Rect(region)
        this.maxFontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxFontSp.toFloat(), context.resources.displayMetrics)
        invalidate()
    }

    fun clear() {
        bubbles = emptyList()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (bubbles.isEmpty()) return
        getLocationOnScreen(loc)
        val ox = region.left - loc[0]
        val oy = region.top - loc[1]
        for (b in bubbles) {
            if (b.translation.isBlank()) continue
            val pad = max(context.dpF(3f), 0.35f * b.lineHeight)
            // русский текст длиннее корейского — даём плашке стать шире
            val w = max(max(b.box.w + 2 * pad, b.box.w * 1.3f), context.dpF(48f))
            var h = b.box.h + 2 * pad
            val cx = ox + b.box.cx
            val cy = oy + b.box.cy
            val textW = max(10, (w - 2 * context.dpF(3f)).toInt())

            var layout = makeLayout(b.translation, textW, maxFontPx)
            var size = maxFontPx
            while (layout.height > h - context.dpF(4f) && size > minFontPx) {
                size -= 1f
                layout = makeLayout(b.translation, textW, size)
            }
            h = max(h, layout.height + context.dpF(6f))

            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val bg = b.bgColor
            val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
            if (b.kind == "free") {
                fill.color = Color.argb(235, 20, 20, 20)
                paint.color = Color.WHITE
            } else {
                fill.color = Color.argb(255, Color.red(bg), Color.green(bg), Color.blue(bg))
                paint.color = if (lum > 140) Color.rgb(15, 15, 15) else Color.rgb(250, 250, 250)
            }
            val radius = context.dpF(8f)
            c.drawRoundRect(rect, radius, radius, fill)
            c.save()
            c.translate(rect.left + (w - textW) / 2, rect.top + (h - layout.height) / 2)
            layout.draw(c)
            c.restore()
        }
    }

    private fun makeLayout(text: String, width: Int, size: Float): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }
}
