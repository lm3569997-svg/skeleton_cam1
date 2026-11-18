package com.poseguard.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO11n-pose 的 ncnn 推理封装。
 *
 * 模型：assets/yolo11n_pose.ncnn.param + .bin（pnnx 从官方 onnx 导出）
 * 输入：in0   [1, 3, 640, 640]  RGB，模型内部已含 /255
 * 输出：out0  [1, 56, 8400]     4 box + 1 conf + 17×3 关键点，通道优先
 *
 * 关键点顺序是 COCO 17 点，比 MediaPipe 的 33 点少但够画骨架。
 */
class PoseEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "PoseEngine"

        const val IN_W = 640
        const val IN_H = 640
        const val NUM_ANCHOR = 8400
        const val CHANNEL = 56

        /** 人整体置信度阈值 */
        const val CONF_TH = 0.35f
        /** 单个关键点置信度阈值 */
        const val KP_TH = 0.30f
        /** NMS IoU 阈值 */
        const val NMS_TH = 0.45f

        private const val PARAM = "yolo11n_pose.ncnn.param"
        private const val BIN = "yolo11n_pose.ncnn.bin"
        private const val IN_NAME = "in0"
        private const val OUT_NAME = "out0"

        init {
            System.loadLibrary("ncnn")
            System.loadLibrary("poseguard")
        }
    }

    private var ready = false
    private var loadError: String? = null
    private val outBuf = FloatArray(CHANNEL * NUM_ANCHOR)

    @Volatile var framesRun = 0
        private set
    @Volatile var framesWithPose = 0
        private set

    fun init(): Boolean {
        return try {
            val code = nativeInit(ctx.assets, PARAM, BIN)
            if (code != 0) {
                loadError = when (code) {
                    -1 -> "AssetManager 无效"
                    -2 -> "缺少 $PARAM"
                    -3 -> "缺少 $BIN"
                    -4 -> "param 解析失败"
                    -5 -> "bin 读取失败"
                    else -> "初始化失败 (code=$code)"
                }
                ready = false
                Log.e(TAG, loadError!!)
                false
            } else {
                loadError = null
                ready = true
                Log.i(TAG, "YOLO11 模型就绪")
                true
            }
        } catch (t: Throwable) {
            loadError = "so 加载失败: ${t.javaClass.simpleName} ${t.message}"
            ready = false
            Log.e(TAG, "init 异常", t)
            false
        }
    }

    /**
     * @param src 任意尺寸的位图，坐标会换算回它的像素空间
     */
    fun detect(src: Bitmap): List<Person> {
        if (!ready || src.width <= 0 || src.height <= 0) return emptyList()

        var letterboxed: Bitmap? = null
        try {
            val scale = min(IN_W.toFloat() / src.width, IN_H.toFloat() / src.height)
            val nw = max(1, (src.width * scale).toInt())
            val nh = max(1, (src.height * scale).toInt())
            val padX = (IN_W - nw) / 2
            val padY = (IN_H - nh) / 2

            val canvasBmp = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBmp)
            canvas.drawColor(Color.BLACK)

            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            canvas.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
            if (scaled !== src) scaled.recycle()
            letterboxed = canvasBmp

            val n = nativeDetect(canvasBmp, outBuf, IN_NAME, OUT_NAME)
            if (n <= 0) {
                loadError = when (n) {
                    -10 -> "输入名 $IN_NAME 不对"
                    -11 -> "输出名 $OUT_NAME 不对"
                    -12 -> "输出超出缓冲区"
                    else -> "推理失败 (code=$n)"
                }
                Log.e(TAG, loadError!!)
                return emptyList()
            }

            framesRun++
            val raw = parse(outBuf)
            if (raw.isNotEmpty()) framesWithPose++

            // 反 letterbox：减黑边再除缩放
            return raw.map { p ->
                Person(
                    id = p.id,
                    kps = p.kps.map {
                        PointF((it.x * IN_W - padX) / scale, (it.y * IN_H - padY) / scale)
                    },
                    kpConf = p.kpConf,
                    score = p.score,
                    box = floatArrayOf(
                        (p.box[0] * IN_W - padX) / scale,
                        (p.box[1] * IN_H - padY) / scale,
                        (p.box[2] * IN_W - padX) / scale,
                        (p.box[3] * IN_H - padY) / scale
                    )
                )
            }
        } catch (t: Throwable) {
            loadError = "推理异常: ${t.javaClass.simpleName} ${t.message}"
            Log.e(TAG, "detect 失败", t)
            return emptyList()
        } finally {
            letterboxed?.recycle()
        }
    }

    /**
     * out0 布局 [56, 8400]，通道优先：
     *   data[c * 8400 + i]  i 是 anchor，c 是通道
     *   c=0..3   → cx, cy, w, h（归一化到 640）
     *   c=4      → 置信度
     *   c=5..    → 17 个关键点的 x, y, conf
     */
    private fun parse(data: FloatArray): List<Person> {
        val raw = ArrayList<Person>(8)
        for (i in 0 until NUM_ANCHOR) {
            val score = data[4 * NUM_ANCHOR + i]
            if (score < CONF_TH) continue

            val cx = data[i] / IN_W
            val cy = data[NUM_ANCHOR + i] / IN_H
            val bw = data[2 * NUM_ANCHOR + i] / IN_W
            val bh = data[3 * NUM_ANCHOR + i] / IN_H

            val kps = ArrayList<PointF>(17)
            val conf = FloatArray(17)
            for (k in 0 until 17) {
                val base = (5 + k * 3) * NUM_ANCHOR + i
                kps.add(PointF(data[base] / IN_W, data[base + NUM_ANCHOR] / IN_H))
                conf[k] = data[base + 2 * NUM_ANCHOR]
            }

            raw.add(
                Person(
                    id = 0,
                    kps = kps,
                    kpConf = conf,
                    score = score,
                    box = floatArrayOf(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
                )
            )
        }
        val kept = nms(raw)
        return kept.mapIndexed { idx, p -> p.copy(id = idx) }
    }

    private fun iou(a: Person, b: Person): Float {
        val ix = max(0f, min(a.box[2], b.box[2]) - max(a.box[0], b.box[0]))
        val iy = max(0f, min(a.box[3], b.box[3]) - max(a.box[1], b.box[1]))
        val inter = ix * iy
        if (inter <= 0f) return 0f
        val areaA = (a.box[2] - a.box[0]) * (a.box[3] - a.box[1])
        val areaB = (b.box[2] - b.box[0]) * (b.box[3] - b.box[1])
        val uni = areaA + areaB - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    private fun nms(list: List<Person>): List<Person> {
        val sorted = list.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Person>()
        while (sorted.isNotEmpty()) {
            val head = sorted.removeAt(0)
            keep.add(head)
            sorted.removeAll { iou(head, it) > NMS_TH }
        }
        return keep
    }

    fun error(): String? = loadError

    fun stats(): String = "推理 $framesRun / 有人 $framesWithPose"

    fun release() {
        if (ready) runCatching { nativeRelease() }
        ready = false
    }

    // ---- native ----

    private external fun nativeInit(
        assetManager: android.content.res.AssetManager,
        param: String,
        bin: String
    ): Int

    private external fun nativeDetect(
        bitmap: Bitmap,
        out: FloatArray,
        inputName: String,
        outputName: String
    ): Int

    private external fun nativeRelease()
}
