package com.poseguard.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO11n-pose 推理（ONNX Runtime，纯 Kotlin，不需要 JNI / NDK）。
 *
 * 模型：assets/yolo11n-pose.onnx
 * 输入：images   [1, 3, 640, 640]  RGB /255，letterbox 补黑边
 * 输出：output0  [1, 56, 8400]    cx,cy,w,h,conf + 17×3关键点，通道优先
 *
 * 内部全程用「原图像素坐标」，最后 Person 里再除以原图宽高归一化，
 * 这样悬浮窗只要乘屏幕尺寸就能画。
 */
class PoseEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "PoseEngine"
        private const val MODEL = "yolo11n-pose.onnx"

        const val IN_W = 640
        const val IN_H = 640
        private const val NUM_ANCHOR = 8400      // 80×80 + 40×40 + 20×20
        private const val CHANNEL = 56           // 4 + 1 + 17*3
        private const val KP_COUNT = 17

        private const val CONF_TH = 0.28f
        private const val NMS_IOU = 0.45f
        private const val KP_VIS = 0.25f
        private const val MIN_VISIBLE_KP = 4
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loadError: String? = null

    @Volatile
    var ready = false
        private set

    @Volatile var framesRun = 0
    @Volatile var framesWithPose = 0

    private val inputBuf = FloatArray(IN_W * IN_H * 3)
    private val busy = AtomicBoolean(false)

    fun init(): Boolean {
        return try {
            loadError = null
            val bytes = ctx.assets.open(MODEL).use { it.readBytes() }
            Log.i(TAG, "模型 ${bytes.size} 字节")

            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(bytes, opts)

            env = e
            session = s
            ready = true
            Log.i(TAG, "初始化成功 输入=${s.inputNames} 输出=${s.outputNames}")
            true
        } catch (t: Throwable) {
            loadError = "模型加载失败: ${t.javaClass.simpleName} ${t.message}"
            ready = false
            Log.e(TAG, loadError, t)
            false
        }
    }

    fun detect(src: Bitmap): List<Person> {
        val sess = session ?: return emptyList()
        val environment = env ?: return emptyList()
        if (!ready) return emptyList()
        if (!busy.compareAndSet(false, true)) return emptyList()

        var canvas: Bitmap? = null
        try {
            val sw = src.width
            val sh = src.height

            // ---- letterbox ----
            val scale = min(IN_W.toFloat() / sw, IN_H.toFloat() / sh)
            val nw = max(1, (sw * scale).toInt())
            val nh = max(1, (sh * scale).toInt())
            val padX = (IN_W - nw) / 2
            val padY = (IN_H - nh) / 2

            canvas = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(canvas)
            c.drawColor(Color.BLACK)
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
            if (scaled !== src) scaled.recycle()

            // ---- Bitmap → NCHW float ----
            val pixels = IntArray(IN_W * IN_H)
            canvas.getPixels(pixels, 0, IN_W, 0, 0, IN_W, IN_H)
            val plane = IN_W * IN_H
            for (i in pixels.indices) {
                val p = pixels[i]
                inputBuf[i] = ((p shr 16) and 0xFF) / 255f
                inputBuf[plane + i] = ((p shr 8) and 0xFF) / 255f
                inputBuf[plane * 2 + i] = (p and 0xFF) / 255f
            }

            // ---- 推理 ----
            val shape = longArrayOf(1, 3, IN_W.toLong(), IN_H.toLong())
            val outArr: FloatArray
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(inputBuf), shape).use { input ->
                sess.run(mapOf(sess.inputNames.first() to input)).use { result ->
                    val tensor = result.get(0) as OnnxTensor
                    outArr = FloatArray(CHANNEL * NUM_ANCHOR)
                    tensor.floatBuffer.get(outArr)
                }
            }

            framesRun++
            val persons = decode(outArr, sw, sh, scale, padX.toFloat(), padY.toFloat())
            if (persons.isNotEmpty()) framesWithPose++

            if (persons.isEmpty() && framesRun % 30 == 1) {
                Log.w(TAG, "第 $framesRun 帧没检测到人（${sw}x$sh）")
            }
            return persons
        } catch (t: Throwable) {
            loadError = "推理异常: ${t.javaClass.simpleName} ${t.message}"
            Log.e(TAG, "detect 失败", t)
            return emptyList()
        } finally {
            canvas?.recycle()
            busy.set(false)
        }
    }

    /**
     * 解析 [56, 8400]。data 布局是「通道优先」：
     * data[c * 8400 + i] 表示第 c 个通道第 i 个候选。
     */
    private fun decode(
        data: FloatArray,
        srcW: Int, srcH: Int,
        scale: Float, padX: Float, padY: Float
    ): List<Person> {
        val boxes = ArrayList<FloatArray>(32)
        val scores = ArrayList<Float>(32)
        val kpsList = ArrayList<List<PointF>>(32)
        val kpConfs = ArrayList<FloatArray>(32)

        for (i in 0 until NUM_ANCHOR) {
            val score = data[4 * NUM_ANCHOR + i]
            if (score < CONF_TH) continue

            val cx = data[i] * IN_W
            val cy = data[NUM_ANCHOR + i] * IN_H
            val bw = data[2 * NUM_ANCHOR + i] * IN_W
            val bh = data[3 * NUM_ANCHOR + i] * IN_H
            if (bw <= 0f || bh <= 0f) continue

            val kps = ArrayList<PointF>(KP_COUNT)
            val kc = FloatArray(KP_COUNT)
            var visible = 0
            for (k in 0 until KP_COUNT) {
                val base = (5 + k * 3) * NUM_ANCHOR + i
                val kx = data[base] * IN_W
                val ky = data[base + NUM_ANCHOR] * IN_H
                val kv = data[base + 2 * NUM_ANCHOR]
                kc[k] = kv
                // 反 letterbox 回原图像素
                kps.add(PointF((kx - padX) / scale, (ky - padY) / scale))
                if (kv >= KP_VIS) visible++
            }
            if (visible < MIN_VISIBLE_KP) continue

            val l = (cx - bw / 2f - padX) / scale
            val t = (cy - bh / 2f - padY) / scale
            val r = (cx + bw / 2f - padX) / scale
            val b = (cy + bh / 2f - padY) / scale
            if (r <= l || b <= t) continue

            boxes.add(floatArrayOf(l, t, r, b))
            scores.add(score)
            kpsList.add(kps)
            kpConfs.add(kc)
        }

        if (boxes.isEmpty()) return emptyList()

        // ---- NMS ----
        val order = scores.indices.sortedByDescending { scores[it] }
        val keep = ArrayList<Int>()
        val suppressed = BooleanArray(order.size)

        for (a in order.indices) {
            if (suppressed[a]) continue
            val ia = order[a]
            keep.add(ia)
            for (b in a + 1 until order.size) {
                if (suppressed[b]) continue
                if (iou(boxes[ia], boxes[order[b]]) > NMS_IOU) suppressed[b] = true
            }
        }

        return keep.mapIndexed { idx, i ->
            val box = boxes[i]
            val normKps = kpsList[i].map {
                PointF(
                    (it.x / srcW).coerceIn(-0.2f, 1.2f),
                    (it.y / srcH).coerceIn(-0.2f, 1.2f)
                )
            }
            Person(
                id = idx,
                kps = normKps,
                kpConf = kpConfs[i],
                score = scores[i],
                box = floatArrayOf(
                    (box[0] / srcW).coerceIn(0f, 1f),
                    (box[1] / srcH).coerceIn(0f, 1f),
                    (box[2] / srcW).coerceIn(0f, 1f),
                    (box[3] / srcH).coerceIn(0f, 1f)
                )
            )
        }
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val uni = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    fun error(): String? = loadError

    fun stats(): String = "推理 $framesRun 帧 / 有人 $framesWithPose 帧"

    fun release() {
        runCatching { session?.close() }
        session = null
        ready = false
    }
}
