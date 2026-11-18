package com.poseguard.lite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    companion object {
        const val CH_ID = "poseguard_lite"
        const val NOTI_ID = 1001

        @Volatile var running = false
        @Volatile var lastFps = 0
        @Volatile var lastCount = 0
        @Volatile var lastError: String? = null
    }

    private val inferExec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var engine: PoseEngine? = null
    private var overlayCtl: OverlayController? = null
    private var overlay: MonitorOverlay? = null

    private var frameCount = 0
    private var fpsMark = 0L

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTI_ID, buildNotification())

        val ctl = OverlayController(this)
        overlay = ctl.attach()
        overlayCtl = ctl

        if (overlay == null) {
            lastError = "悬浮窗未授权：设置 → 应用 → 显示在其他应用上层"
        } else {
            lastError = null
        }

        val e = PoseEngine(this)
        if (!e.init()) {
            lastError = e.error() ?: "模型加载失败"
            overlay?.showMessage(lastError)
            running = false
        } else {
            engine = e
            running = true
            lastError = null
            overlay?.showMessage(null)
            bindCamera()
        }
        return START_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()

                analysis.setAnalyzer(inferExec) { img ->
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsMark >= 1000) {
                        lastFps = (frameCount * 1000 / (now - fpsMark)).toInt()
                        frameCount = 0
                        fpsMark = now
                    }

                    if (!busy.compareAndSet(false, true)) {
                        img.close()
                        return@setAnalyzer
                    }
                    try {
                        val bmp = img.toUprightBitmap()
                        val det = engine
                        if (det != null) {
                            val persons = det.detect(bmp)
                            lastCount = persons.size

                            // 画框前把 MediaPipe 的归一化坐标镜像回来（后置镜像没有，前置才需要）
                            overlay?.post {
                                overlay?.update(persons, lastFps)
                            }
                        }
                        bmp.recycle()
                    } catch (t: Throwable) {
                        lastError = "${t.javaClass.simpleName}: ${t.message}"
                        overlay?.post { overlay?.showMessage(lastError) }
                    } finally {
                        img.close()
                        busy.set(false)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (t: Throwable) {
                lastError = "相机启动失败: ${t.message}"
                overlay?.post { overlay?.showMessage(lastError) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "PoseGuard Lite", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("PoseGuard Lite 运行中")
            .setContentText("MediaPipe 姿态识别 + 悬浮骨架")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        overlayCtl?.detach()
        engine?.release()
        inferExec.shutdownNow()
        super.onDestroy()
    }
}

/** 目前固定用后置，前置需要镜像时把这里改成 true */
private const val IS_FRONT = false

/** 竖屏使用时摄像头帧需要旋转的角度 */
private const val UPRIGHT_DEGREES = 90

/** YUV_420_888 → NV21 → JPEG → Bitmap，并摆正到竖屏 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val y = planes[0]
    val u = planes[1]
    val v = planes[2]

    val nv21 = ByteArray(y.buffer.remaining() + u.buffer.remaining() + v.buffer.remaining())
    y.buffer.get(nv21, 0, y.buffer.remaining())
    v.buffer.get(nv21, y.buffer.remaining(), v.buffer.remaining())
    u.buffer.get(nv21, y.buffer.remaining() + v.buffer.remaining(), u.buffer.remaining())

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
    val bytes = out.toByteArray()
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    // 摄像头传感器方向是横向的，竖屏使用时需要转 90 度
    // ImageAnalysis 的 targetRotation 设为 ROTATION_0 时，出帧就是 90 度转过的
    val m = Matrix().apply {
        postRotate(UPRIGHT_DEGREES.toFloat())
        // 后置不做水平镜像；前置要让画面和悬浮窗对齐，需镜像
        if (IS_FRONT) {
            postScale(-1f, 1f)
        }
    }
    val fixed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (fixed !== raw) raw.recycle()
    return fixed
}
