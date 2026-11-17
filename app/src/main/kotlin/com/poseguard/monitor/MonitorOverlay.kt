package com.poseguard.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class MonitorOverlay(ctx: Context) : View(ctx) {

    companion object {
        val GREEN = 0xFF00FF66.toInt()
        val RED = 0xFFFF3366.toInt()
    }

    @Volatile
    var persons: List<Person> = emptyList()

    var showSkeleton = true
    var showJoints = true
    var showScan = true
    var showGrid = true
    var showCornerBox = true

    private var scanY = 0f
    private var fps = 0

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = GREEN
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = GREEN
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        color = RED
        isAntiAlias = true
    }
    private val haloPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x88FFFFFF.toInt()
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = GREEN
        textSize = 26f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val smallPaint = Paint().apply {
        color = 0xAA00FF66.toInt()
        textSize = 20f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val gridPaint = Paint().apply {
        color = 0x3300FF66
        strokeWidth = 1f
    }
    private val scanPaint = Paint().apply {
        color = 0x6600FF66.toInt()
        strokeWidth = 3f
    }

    fun setFps(v: Int) { fps = v }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (showGrid) drawGrid(c)

        for (p in persons) {
            val r = RectF(p.left, p.top, p.right, p.bottom)
            if (showCornerBox) drawCorners(c, r, boxPaint) else c.drawRect(r, boxPaint)

            val label = "PERSON ${(p.conf * 100).toInt()}%"
            c.drawText(label, r.left + 6f, r.top - 10f, textPaint)

            if (showSkeleton) {
                for (pair in PosePost.SKELETON) {
                    val ka = p.kps[pair[0]]
                    val kb = p.kps[pair[1]]
                    if (ka.conf < PoseDetector.KP_CONF || kb.conf < PoseDetector.KP_CONF) continue
                    c.drawLine(ka.x, ka.y, kb.x, kb.y, linePaint)
                }
            }

            if (showJoints) {
                for (k in p.kps) {
                    if (k.conf < PoseDetector.KP_CONF) continue
                    c.drawCircle(k.x, k.y, 5f, dotPaint)
                    c.drawCircle(k.x, k.y, 9f, haloPaint)
                }
            }
        }

        if (showScan) {
            scanY = (scanY + 10f) % height.coerceAtLeast(1).toFloat()
            c.drawLine(0f, scanY, width.toFloat(), scanY, scanPaint)
            postInvalidateOnAnimation()
        }

        drawHud(c)
    }

    private fun drawGrid(c: Canvas) {
        val step = 120f
        var x = 0f
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }
        c.drawLine(width / 2f - 40f, height / 2f, width / 2f + 40f, height / 2f, gridPaint)
        c.drawLine(width / 2f, height / 2f - 40f, width / 2f, height / 2f + 40f, gridPaint)
    }

    private fun drawCorners(c: Canvas, r: RectF, paint: Paint, len: Float = 30f) {
        c.drawLine(r.left, r.top, r.left + len, r.top, paint)
        c.drawLine(r.left, r.top, r.left, r.top + len, paint)
        c.drawLine(r.right, r.top, r.right - len, r.top, paint)
        c.drawLine(r.right, r.top, r.right, r.top + len, paint)
        c.drawLine(r.left, r.bottom, r.left + len, r.bottom, paint)
        c.drawLine(r.left, r.bottom, r.left, r.bottom - len, paint)
        c.drawLine(r.right, r.bottom, r.right - len, r.bottom, paint)
        c.drawLine(r.right, r.bottom, r.right, r.bottom - len, paint)
    }

    private fun drawHud(c: Canvas) {
        val pad = 24f
        val y0 = pad + 24f
        c.drawText("POSE-GUARD  ONLINE", pad, y0, textPaint)
        c.drawText("TARGETS: ${persons.size}", pad, y0 + 30f, smallPaint)
        c.drawText("FPS: $fps", pad, y0 + 56f, smallPaint)
    }
}

class OverlayController(private val ctx: Context) {
    private var view: MonitorOverlay? = null

    fun attach(): MonitorOverlay {
        view?.let { if (it.isAttachedToWindow) return it }
        val v = MonitorOverlay(ctx)
        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
        view = v
        return v
    }

    fun detach() {
        view?.let {
            runCatching {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        view = null
    }
}
