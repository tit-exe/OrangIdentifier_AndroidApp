package com.iphc.orangidentifier.ui.box_editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Full-screen image view with interactive bounding box editing.
 *
 * Boxes are maintained in IMAGE coordinates (original bitmap pixels).
 * The view scales the bitmap to fit while preserving aspect ratio.
 *
 * Touch behaviour:
 *   - Tap on handle of selected box → resize
 *   - Tap inside selected box       → move
 *   - Tap inside another box        → select it + move
 *   - Tap on empty area             → deselect
 */
class BoxEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onBoxCountChanged(count: Int)
        /** Called whenever the selected box index changes. -1 means nothing is selected. */
        fun onSelectionChanged(selectedIdx: Int)
    }

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        const val MAX_BOXES = 5
    }

    private val density      = context.resources.displayMetrics.density
    private val handleRadius = density * 9f         // 9 dp touch target
    private val minBoxPx     = density * 60f        // 60 dp minimum box size in view coords

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 76, 175, 80)
        style = Paint.Style.STROKE
        strokeWidth = density * 2.5f
    }
    private val paintBoxSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 76, 175, 80); style = Paint.Style.FILL
    }
    private val paintFillSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 255, 193, 7); style = Paint.Style.FILL
    }
    private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val paintHandleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 33, 150, 243)
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = density * 14f
        isFakeBoldText = true
    }
    private val paintLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 20, 20, 20); style = Paint.Style.FILL
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var bitmap: Bitmap? = null
    private var imgW = 1f
    private var imgH = 1f

    // Transform: image → view
    private var scale   = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** Boxes in IMAGE pixel coordinates. */
    val boxes = mutableListOf<RectF>()

    // Using a property setter so the listener is always notified on selection change,
    // with no risk of a forgotten call at any of the multiple code paths that change it.
    private var selectedIdx: Int = -1
        set(value) {
            if (field != value) {
                field = value
                listener?.onSelectionChanged(value)
            }
        }

    private enum class Handle {
        NONE, MOVE,
        TL, T, TR, L, R, BL, B, BR
    }
    private var activeHandle = Handle.NONE
    private var lastTouchImgX = 0f
    private var lastTouchImgY = 0f

    var listener: Listener? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun setBitmap(bmp: Bitmap, initialBoxes: List<RectF> = emptyList()) {
        bitmap = bmp
        imgW = bmp.width.toFloat()
        imgH = bmp.height.toFloat()
        boxes.clear()
        boxes.addAll(initialBoxes.map { RectF(it) })
        selectedIdx = -1
        recomputeTransform()
        invalidate()
    }

    fun addBox() {
        if (boxes.size >= MAX_BOXES) return
        val w = imgW * 0.25f
        val h = imgH * 0.25f
        boxes.add(RectF((imgW - w) / 2f, (imgH - h) / 2f, (imgW + w) / 2f, (imgH + h) / 2f))
        selectedIdx = boxes.lastIndex
        listener?.onBoxCountChanged(boxes.size)
        invalidate()
    }

    /** Removes the currently selected box. No-op if nothing is selected. */
    fun deleteSelected() {
        if (selectedIdx < 0 || selectedIdx >= boxes.size) return
        boxes.removeAt(selectedIdx)
        selectedIdx = -1                         // setter notifies listener
        listener?.onBoxCountChanged(boxes.size)
        invalidate()
    }

    fun getBoxesInImageCoords(): List<RectF> = boxes.map { RectF(it) }

    /** Releases the held bitmap reference. Call from Fragment.onDestroyView(). */
    fun clear() {
        bitmap = null
        boxes.clear()
        selectedIdx = -1
        invalidate()
    }

    // ── Layout ─────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        recomputeTransform()
    }

    private fun recomputeTransform() {
        if (imgW <= 0f || imgH <= 0f || width <= 0 || height <= 0) return
        val sx = width  / imgW
        val sy = height / imgH
        scale   = minOf(sx, sy)
        offsetX = (width  - imgW * scale) / 2f
        offsetY = (height - imgH * scale) / 2f
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private fun ix(vx: Float) = (vx - offsetX) / scale   // view → image X
    private fun iy(vy: Float) = (vy - offsetY) / scale   // view → image Y
    private fun vx(ix: Float) = ix * scale + offsetX      // image → view X
    private fun vy(iy: Float) = iy * scale + offsetY      // image → view Y

    private fun boxToView(b: RectF) = RectF(vx(b.left), vy(b.top), vx(b.right), vy(b.bottom))

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { bmp ->
            canvas.drawBitmap(
                bmp, null,
                RectF(offsetX, offsetY, offsetX + imgW * scale, offsetY + imgH * scale),
                null
            )
        }
        for ((i, box) in boxes.withIndex()) {
            val v = boxToView(box)
            val sel = i == selectedIdx
            canvas.drawRect(v, if (sel) paintFillSelected else paintFill)
            canvas.drawRect(v, if (sel) paintBoxSelected else paintBox)
            drawLabel(canvas, v, i + 1)
            if (sel) drawHandles(canvas, v)
        }
    }

    private fun drawLabel(canvas: Canvas, v: RectF, num: Int) {
        val txt = "#$num"
        val tw  = paintLabel.measureText(txt)
        val x   = v.left + density * 4f
        val y   = (v.top - density * 4f).coerceAtLeast(paintLabel.textSize + density * 4f)
        canvas.drawRect(x - density * 2f, y - paintLabel.textSize - density * 2f,
            x + tw + density * 4f, y + density * 2f, paintLabelBg)
        canvas.drawText(txt, x, y, paintLabel)
    }

    private fun drawHandles(canvas: Canvas, v: RectF) {
        for ((hx, hy) in handleCenters(v)) {
            canvas.drawCircle(hx, hy, handleRadius * 0.7f, paintHandle)
            canvas.drawCircle(hx, hy, handleRadius * 0.7f, paintHandleStroke)
        }
    }

    /** 8 handle positions: TL T TR L R BL B BR (view coords). */
    private fun handleCenters(v: RectF): List<Pair<Float, Float>> = listOf(
        v.left  to v.top,
        v.centerX() to v.top,
        v.right to v.top,
        v.left  to v.centerY(),
        v.right to v.centerY(),
        v.left  to v.bottom,
        v.centerX() to v.bottom,
        v.right to v.bottom
    )

    private val handleMap = listOf(
        Handle.TL, Handle.T, Handle.TR,
        Handle.L, Handle.R,
        Handle.BL, Handle.B, Handle.BR
    )

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val vx = ev.x; val vy = ev.y
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> handleDown(vx, vy)
            MotionEvent.ACTION_MOVE -> handleMove(vx, vy)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeHandle = Handle.NONE
        }
        return true
    }

    private fun handleDown(vx: Float, vy: Float) {
        // 1. Check handles of selected box
        if (selectedIdx >= 0) {
            val v = boxToView(boxes[selectedIdx])
            for ((i, pt) in handleCenters(v).withIndex()) {
                if (hypot(vx - pt.first, vy - pt.second) <= handleRadius) {
                    activeHandle = handleMap[i]
                    lastTouchImgX = ix(vx); lastTouchImgY = iy(vy)
                    return
                }
            }
            // Inside selected box → move
            if (v.contains(vx, vy)) {
                activeHandle = Handle.MOVE
                lastTouchImgX = ix(vx); lastTouchImgY = iy(vy)
                return
            }
        }
        // 2. Tap on another box → select + move
        for (i in boxes.indices.reversed()) {
            val v = boxToView(boxes[i])
            if (v.contains(vx, vy)) {
                selectedIdx = i
                activeHandle = Handle.MOVE
                lastTouchImgX = ix(vx); lastTouchImgY = iy(vy)
                invalidate()
                return
            }
        }
        // 3. Tap on empty → deselect
        selectedIdx = -1
        activeHandle = Handle.NONE
        invalidate()
    }

    private fun handleMove(vx: Float, vy: Float) {
        if (activeHandle == Handle.NONE || selectedIdx < 0) return
        val curIx = ix(vx); val curIy = iy(vy)
        val dx = curIx - lastTouchImgX
        val dy = curIy - lastTouchImgY
        val box = boxes[selectedIdx]
        val minImg = minBoxPx / scale   // min box size in image coords

        when (activeHandle) {
            Handle.MOVE -> {
                box.offset(dx, dy)
                if (box.left < 0f)    box.offset(-box.left, 0f)
                if (box.top  < 0f)    box.offset(0f, -box.top)
                if (box.right  > imgW) box.offset(imgW - box.right, 0f)
                if (box.bottom > imgH) box.offset(0f, imgH - box.bottom)
            }
            Handle.TL -> { box.left = (box.left + dx).coerceIn(0f, box.right - minImg)
                           box.top  = (box.top  + dy).coerceIn(0f, box.bottom - minImg) }
            Handle.T  -> { box.top  = (box.top  + dy).coerceIn(0f, box.bottom - minImg) }
            Handle.TR -> { box.right = (box.right + dx).coerceIn(box.left + minImg, imgW)
                           box.top   = (box.top   + dy).coerceIn(0f, box.bottom - minImg) }
            Handle.L  -> { box.left  = (box.left  + dx).coerceIn(0f, box.right - minImg) }
            Handle.R  -> { box.right = (box.right + dx).coerceIn(box.left + minImg, imgW) }
            Handle.BL -> { box.left   = (box.left   + dx).coerceIn(0f, box.right - minImg)
                           box.bottom = (box.bottom + dy).coerceIn(box.top + minImg, imgH) }
            Handle.B  -> { box.bottom = (box.bottom + dy).coerceIn(box.top + minImg, imgH) }
            Handle.BR -> { box.right  = (box.right  + dx).coerceIn(box.left + minImg, imgW)
                           box.bottom = (box.bottom + dy).coerceIn(box.top + minImg, imgH) }
            Handle.NONE -> {}
        }
        lastTouchImgX = curIx; lastTouchImgY = curIy
        invalidate()
    }
}
