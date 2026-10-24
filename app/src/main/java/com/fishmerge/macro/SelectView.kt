package com.fishmerge.macro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 캡처해 둔 정지 화면을 그대로 보여 주고, 손가락으로 사각형을 그려 영역을 고르는 뷰.
 * onRect 로 넘기는 좌표는 비트맵 픽셀 기준이다.
 */
class SelectView(
    ctx: Context,
    private val bmp: Bitmap,
    private val onRect: (Rect) -> Unit
) : View(ctx) {

    private val live = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFFFF4D4D.toInt()
    }
    private val done = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF3DDC84.toInt()
    }
    private val dst = Rect()
    private val saved = ArrayList<RectF>()
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var active = false

    override fun onDraw(c: Canvas) {
        dst.set(0, 0, width, height)
        c.drawBitmap(bmp, null, dst, null)
        for (r in saved) c.drawRect(r, done)
        if (active) c.drawRect(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey), live)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; ex = e.x; ey = e.y
                active = true
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x; ey = e.y
            }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y
                active = false
                val l = min(sx, ex)
                val t = min(sy, ey)
                val r = max(sx, ex)
                val b = max(sy, ey)
                if (r - l > 20 && b - t > 20 && width > 0 && height > 0) {
                    val kx = bmp.width / width.toFloat()
                    val ky = bmp.height / height.toFloat()
                    val left = (l * kx).toInt().coerceIn(0, bmp.width - 2)
                    val top = (t * ky).toInt().coerceIn(0, bmp.height - 2)
                    val right = (r * kx).toInt().coerceIn(left + 1, bmp.width)
                    val bottom = (b * ky).toInt().coerceIn(top + 1, bmp.height)
                    saved.add(RectF(l, t, r, b))
                    onRect(Rect(left, top, right, bottom))
                }
            }
            MotionEvent.ACTION_CANCEL -> active = false
        }
        invalidate()
        return true
    }
}
