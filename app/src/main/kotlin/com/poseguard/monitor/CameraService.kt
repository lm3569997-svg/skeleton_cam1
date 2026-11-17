package com.poseguard.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
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
        const val CH_ID = "poseguard"
        const val NOTI_ID = 1001
        @Volatile var running = false
        @Volatile var lastFps = 0
        @Volatile var lastCount = 0
        @Volatile var lastError: String? = null
    }

    private val inferExec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var detector: PoseDetector? = null
    private var overlayCtl: OverlayController? = null
    private var frameCount = 0
    private var fpsMark = 0L

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTI_ID, buildNotification())

        val od = OverlayController(this)
        od.attach()
        overlayCtl = od

        val det = PoseDetector(this)
        if (!det.init()) {
            lastError = det.error() ?: "模型加载失败"
            running = false
        } else {
            lastError = null
            detector = det
        }

        bindCamera()
        running = true
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
                        val bmp = img.toBitmap()
                        val det = detector
                        if (det != null) {
                            val persons = det.detect(bmp)
                            lastCount = persons.size
                            val dm = resources.displayMetrics
                            val sx = dm.widthPixels.toFloat() / img.width
                            val sy = dm.heightPixels.toFloat() / img.height
                            val v = overlayCtl?.attach()
                            if (v != null) {
                                v.post {
                                    v.persons = persons.map { p ->
                                        Person(
                                            p.left * sx, p.top * sy, p.right * sx, p.bottom * sy, p.conf,
                                            p.kps.map { Keypoint(it.x * sx, it.y * sy, it.conf) }
                                        )
                                    }
                                    v.setFps(lastFps)
                                    v.invalidate()
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        lastError = "${t.javaClass.simpleName}: ${t.message}"
                    } finally {
                        img.close()
                        busy.set(false)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (t: Throwable) {
                lastError = "相机启动失败: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "PoseGuard", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("PoseGuard 运行中")
            .setContentText("相机识别 + 骨架标注")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        overlayCtl?.detach()
        detector?.release()
        inferExec.shutdownNow()
        super.onDestroy()
    }
}

fun ImageProxy.toBitmap(): Bitmap {
    val y = planes[0]
    val u = planes[1]
    val v = planes[2]
    val nv21 = ByteArray(y.buffer.remaining() + u.buffer.remaining() + v.buffer.remaining())
    y.buffer.get(nv21, 0, y.buffer.remaining())
    v.buffer.get(nv21, y.buffer.remaining(), v.buffer.remaining())
    u.buffer.get(nv21, y.buffer.remaining() + v.buffer.remaining(), u.buffer.remaining())
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 82, out)
    val b = out.toByteArray()
    return BitmapFactory.decodeByteArray(b, 0, b.size)
}
