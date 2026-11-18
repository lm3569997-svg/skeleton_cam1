package com.poseguard.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class MonitorOverlay(ctx: Context) : View(ctx) {

    @Volatile
    var persons: List<Person> = emptyList()

    @Volatile
    var fps: Int = 0

    /** 推理帧的尺寸，用来把像素坐标换算到屏幕 */
    @Volatile
    private var srcW: Int = 0

    @Volatile
    private var srcH: Int = 0

    @Volatile
    var message: String? = null

    private var scanY = 0f

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        color = Color.WHITE
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val scanPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x5500FF66
        isAntiAlias = true
    }

    fun update(list: List<Person>, fpsNow: Int, srcWidth: Int, srcHeight: Int) {
        persons = list
        fps = fpsNow
        srcW = srcWidth
        srcH = srcHeight
        invalidate()
    }

    fun showMessage(m: String?) {
        message = m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 扫描线
        scanY += h / 90f
        if (scanY > h) scanY = 0f
        canvas.drawLine(0f, scanY, w, scanY, scanPaint)

        // 画面到屏幕的缩放（保持比例，居中裁切）
        val sx = if (srcW > 0) w / srcW else 1f
        val sy = if (srcH > 0) h / srcH else 1f

        persons.forEach { p ->
            val color = Landmarks.COLORS[p.id % Landmarks.COLORS.size]

            // 连线
            linePaint.color = color
            for (pair in Landmarks.SKELETON) {
                val a = pair[0]
                val b = pair[1]
                if (!p.has(a) || !p.has(b)) continue
                val pa = p.kps[a]
                val pb = p.kps[b]
                canvas.drawLine(pa.x * sx, pa.y * sy, pb.x * sx, pb.y * sy, linePaint)
            }

            // 关节点
            dotPaint.color = color
            for (i in 0 until Landmarks.COUNT) {
                if (!p.has(i)) continue
                val k = p.kps[i]
                canvas.drawCircle(k.x * sx, k.y * sy, 7f, dotPaint)
            }

            // 外框
            boxPaint.color = color
            canvas.drawRect(
                p.box[0] * sx, p.box[1] * sy,
                p.box[2] * sx, p.box[3] * sy,
                boxPaint
            )

            // 标签
            textPaint.color = color
            canvas.drawText(
                "#${p.id} ${(p.score * 100).toInt()}%",
                p.box[0] * sx,
                (p.box[1] * sy - 10f).coerceAtLeast(30f),
                textPaint
            )
        }

        // 左上角 HUD
        textPaint.color = Color.WHITE
        canvas.drawText("人数 ${persons.size}   FPS $fps", 24f, 52f, textPaint)

        message?.let {
            textPaint.color = 0xFFFF6666.toInt()
            canvas.drawText(it.take(60), 24f, 96f, textPaint)
        }
    }
}

class OverlayController(private val ctx: Context) {
    private var view: MonitorOverlay? = null

    fun attach(): MonitorOverlay? {
        view?.let { if (it.isAttachedToWindow) return it }
        return try {
            val v = MonitorOverlay(ctx)
            val dm = ctx.resources.displayMetrics

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val lp = WindowManager.LayoutParams(
                dm.widthPixels, dm.heightPixels,
                type, flags, PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START

            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            view = v
            v
        } catch (t: Throwable) {
            android.util.Log.e("Overlay", "attach 失败", t)
            null
        }
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
