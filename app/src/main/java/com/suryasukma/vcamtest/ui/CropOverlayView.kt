package com.suryasukma.vcamtest.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Draws a draggable crop rectangle over an image rect (view coordinates). */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Drag { NONE, MOVE, TL, TR, BL, BR, L, T, R, B }

    private val density = resources.displayMetrics.density
    private val image = RectF()
    private val crop = RectF()
    private var aspect: Float? = null
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** When false the crop is shown but cannot be edited. */
    var interactive = true
        set(value) { field = value; invalidate() }

    private val touchSlop = 28 * density
    private val minSize = 56 * density

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 1 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }

    /** Sets the displayed image bounds. If [keepCrop], the crop keeps its normalized position. */
    fun setImageRect(rect: RectF, keepCrop: Boolean) {
        val norm = if (keepCrop && !image.isEmpty) normalizedCrop() else null
        image.set(rect)
        if (norm != null) {
            crop.set(
                image.left + norm.left * image.width(), image.top + norm.top * image.height(),
                image.left + norm.right * image.width(), image.top + norm.bottom * image.height(),
            )
        } else {
            crop.set(image)
            aspect?.let { fitAspect(it) }
        }
        invalidate()
    }

    fun resetCrop() {
        crop.set(image)
        aspect?.let { fitAspect(it) }
        invalidate()
    }

    /** null = free aspect. */
    fun setAspect(value: Float?) {
        aspect = value
        if (value != null && !image.isEmpty) fitAspect(value)
        invalidate()
    }

    fun normalizedCrop(): RectF {
        if (image.isEmpty) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            ((crop.left - image.left) / image.width()).coerceIn(0f, 1f),
            ((crop.top - image.top) / image.height()).coerceIn(0f, 1f),
            ((crop.right - image.left) / image.width()).coerceIn(0f, 1f),
            ((crop.bottom - image.top) / image.height()).coerceIn(0f, 1f),
        )
    }

    private fun fitAspect(a: Float) {
        var w = image.width()
        var h = w / a
        if (h > image.height()) { h = image.height(); w = h * a }
        crop.set(image.centerX() - w / 2, image.centerY() - h / 2, image.centerX() + w / 2, image.centerY() + h / 2)
    }

    override fun onDraw(canvas: Canvas) {
        if (image.isEmpty) return
        canvas.drawRect(image.left, image.top, image.right, crop.top, dimPaint)
        canvas.drawRect(image.left, crop.bottom, image.right, image.bottom, dimPaint)
        canvas.drawRect(image.left, crop.top, crop.left, crop.bottom, dimPaint)
        canvas.drawRect(crop.right, crop.top, image.right, crop.bottom, dimPaint)
        canvas.drawRect(crop, borderPaint)
        if (!interactive) return

        val tw = crop.width() / 3f
        val th = crop.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(crop.left + tw * i, crop.top, crop.left + tw * i, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, crop.top + th * i, crop.right, crop.top + th * i, gridPaint)
        }
        val r = 7 * density
        for ((x, y) in listOf(
            crop.left to crop.top, crop.right to crop.top,
            crop.left to crop.bottom, crop.right to crop.bottom,
        )) canvas.drawCircle(x, y, r, handlePaint)
    }

    private fun hit(x: Float, y: Float): Drag {
        fun near(a: Float, b: Float) = abs(a - b) <= touchSlop
        val nl = near(x, crop.left); val nr = near(x, crop.right)
        val nt = near(y, crop.top); val nb = near(y, crop.bottom)
        return when {
            nl && nt -> Drag.TL
            nr && nt -> Drag.TR
            nl && nb -> Drag.BL
            nr && nb -> Drag.BR
            aspect == null && nl && y in crop.top..crop.bottom -> Drag.L
            aspect == null && nr && y in crop.top..crop.bottom -> Drag.R
            aspect == null && nt && x in crop.left..crop.right -> Drag.T
            aspect == null && nb && x in crop.left..crop.right -> Drag.B
            crop.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive || image.isEmpty) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = hit(e.x, e.y)
                lastX = e.x; lastY = e.y
                if (drag != Drag.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                when (drag) {
                    Drag.MOVE -> move(dx, dy)
                    Drag.TL -> resizeCorner(true, true, dx, dy)
                    Drag.TR -> resizeCorner(false, true, dx, dy)
                    Drag.BL -> resizeCorner(true, false, dx, dy)
                    Drag.BR -> resizeCorner(false, false, dx, dy)
                    Drag.L -> crop.left = safeIn(crop.left + dx, image.left, crop.right - minSize)
                    Drag.R -> crop.right = safeIn(crop.right + dx, crop.left + minSize, image.right)
                    Drag.T -> crop.top = safeIn(crop.top + dy, image.top, crop.bottom - minSize)
                    Drag.B -> crop.bottom = safeIn(crop.bottom + dy, crop.top + minSize, image.bottom)
                    Drag.NONE -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                return true
            }
        }
        return true
    }

    private fun safeIn(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else v.coerceIn(lo, hi)

    private fun move(dx: Float, dy: Float) {
        val mx = dx.coerceIn(image.left - crop.left, image.right - crop.right)
        val my = dy.coerceIn(image.top - crop.top, image.bottom - crop.bottom)
        crop.offset(mx, my)
    }

    private fun resizeCorner(movingLeft: Boolean, movingTop: Boolean, dx: Float, dy: Float) {
        var l = crop.left; var t = crop.top; var r = crop.right; var b = crop.bottom
        if (movingLeft) l += dx else r += dx
        if (movingTop) t += dy else b += dy
        l = max(l, image.left); t = max(t, image.top); r = min(r, image.right); b = min(b, image.bottom)
        if (r - l < minSize) { if (movingLeft) l = r - minSize else r = l + minSize }
        if (b - t < minSize) { if (movingTop) t = b - minSize else b = t + minSize }
        val a = aspect
        if (a != null) {
            var w = r - l
            var h = w / a
            val maxH = if (movingTop) b - image.top else image.bottom - t
            if (h > maxH) { h = maxH; w = h * a }
            if (movingLeft) l = r - w else r = l + w
            if (movingTop) t = b - h else b = t + h
            if (r - l < minSize || b - t < minSize) return // would end up smaller than the minimum: ignore this move
        }
        crop.set(l, t, r, b)
    }
}
