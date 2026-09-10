package com.moonbridge.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RemoteSurfaceView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var frame: Bitmap? = null
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var pendingFrame: Bitmap? = null
    private var framePostPending = false
    private val frameLock = Any()
    private var lastY = 0f
    private var multiTouch = false
    var sendEvent: ((String) -> Unit)? = null

    fun setScreenSize(width: Int, height: Int) { screenWidth = max(1, width); screenHeight = max(1, height); invalidate() }
    fun setFrame(bitmap: Bitmap) { frame?.recycle(); frame = bitmap; invalidate() }
    fun offerFrame(bitmap: Bitmap) {
        synchronized(frameLock) {
            pendingFrame?.recycle()
            pendingFrame = bitmap
            if (framePostPending) return
            framePostPending = true
        }
        postOnAnimation { renderLatestFrame() }
    }

    private fun renderLatestFrame() {
        val next = synchronized(frameLock) {
            val value = pendingFrame
            pendingFrame = null
            framePostPending = false
            value
        }
        if (next != null) setFrame(next)
        synchronized(frameLock) {
            if (pendingFrame != null && !framePostPending) {
                framePostPending = true
                postOnAnimation { renderLatestFrame() }
            }
        }
    }

    fun clearFrame() { synchronized(frameLock) { pendingFrame?.recycle(); pendingFrame = null }; frame?.recycle(); frame = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val bitmap = frame ?: return
        val scale = min(width.toFloat() / screenWidth, height.toFloat() / screenHeight)
        val drawWidth = screenWidth * scale
        val drawHeight = screenHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = normalizedPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false; lastY = event.y
                sendPointer(point); sendButton("left", true); return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch || event.pointerCount > 1) {
                    val delta = ((lastY - event.y) * 5).toInt()
                    if (abs(delta) > 1) sendEvent?.invoke("{\"type\":\"scroll\",\"delta\":$delta}")
                    lastY = event.y
                } else sendPointer(point)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sendButton("left", false); multiTouch = false; return true
            }
        }
        return true
    }

    private fun sendPointer(point: Pair<Float, Float>) { sendEvent?.invoke("{\"type\":\"pointer\",\"x\":${point.first},\"y\":${point.second}}") }
    private fun sendButton(button: String, down: Boolean) { sendEvent?.invoke("{\"type\":\"button\",\"button\":\"$button\",\"down\":$down}") }

    private fun normalizedPoint(x: Float, y: Float): Pair<Float, Float> {
        val scale = min(width.toFloat() / screenWidth, height.toFloat() / screenHeight)
        val drawWidth = screenWidth * scale; val drawHeight = screenHeight * scale
        val left = (width - drawWidth) / 2f; val top = (height - drawHeight) / 2f
        return Pair(((x - left) / drawWidth).coerceIn(0f, 1f), ((y - top) / drawHeight).coerceIn(0f, 1f))
    }
}
