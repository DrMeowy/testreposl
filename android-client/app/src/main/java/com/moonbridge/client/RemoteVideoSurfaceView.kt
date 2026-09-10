package com.moonbridge.client

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RemoteVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var lastY = 0f
    private var multiTouch = false
    var sendEvent: ((String) -> Unit)? = null
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        setBackgroundColor(android.graphics.Color.BLACK)
        keepScreenOn = true
    }

    fun setScreenSize(width: Int, height: Int) {
        screenWidth = max(1, width)
        screenHeight = max(1, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        onSurfaceReady?.invoke(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurfaceDestroyed?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = normalizedPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                lastY = event.y
                sendPointer(point)
                sendButton("left", true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch || event.pointerCount > 1) {
                    val delta = ((lastY - event.y) * 5).toInt()
                    if (abs(delta) > 1) sendEvent?.invoke("{\"type\":\"scroll\",\"delta\":$delta}")
                    lastY = event.y
                } else {
                    sendPointer(point)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sendButton("left", false)
                multiTouch = false
                return true
            }
        }
        return true
    }

    private fun sendPointer(point: Pair<Float, Float>) {
        sendEvent?.invoke("{\"type\":\"pointer\",\"x\":${point.first},\"y\":${point.second}}")
    }

    private fun sendButton(button: String, down: Boolean) {
        sendEvent?.invoke("{\"type\":\"button\",\"button\":\"$button\",\"down\":$down}")
    }

    private fun normalizedPoint(x: Float, y: Float): Pair<Float, Float> {
        val scale = min(width.toFloat() / screenWidth, height.toFloat() / screenHeight)
        val drawWidth = screenWidth * scale
        val drawHeight = screenHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        return Pair(
            ((x - left) / drawWidth).coerceIn(0f, 1f),
            ((y - top) / drawHeight).coerceIn(0f, 1f)
        )
    }
}
