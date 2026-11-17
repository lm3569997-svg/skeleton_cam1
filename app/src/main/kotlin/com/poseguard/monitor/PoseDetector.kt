package com.poseguard.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8n-pose 的 ncnn 推理封装。
 *
 * 模型：assets/yolov8n_pose.ncnn.param + .bin（由 ci_build_model.sh 导出）
 * 输入：in0   [1, 3, 640, 640]  RGB 归一化
 * 输出：out0  [1, 56, 8400]     4 box + 1 conf + 51 keypoint，通道优先
 */
class PoseDetector(private val ctx: Context) {

    companion object {
        private const val TAG = "PoseDetector"

        const val IN_W = 640
        const val IN_H = 640
        const val CONF = 0.35f
        const val KP_CONF = 0.30f
        const val NMS_IOU = 0.45f

        private const val PARAM = "yolov8n_pose.ncnn.param"
        private const val BIN = "yolov8n_pose.ncnn.bin"
        private const val IN_NAME = "in0"
        private const val OUT_NAME = "out0"

        /** 8400 个候选 × 56 通道 */
        private const val NUM_ANCHOR = 8400
        private const val CHANNEL = 56

        init {
            System.loadLibrary("ncnn")
            System.loadLibrary("poseguard")
        }
    }

    private var ready = false
    private var loadError: String? = null
    private val outBuf = FloatArray(CHANNEL * NUM_ANCHOR)

    fun init(): Boolean {
        return try {
            val code = nativeInit(ctx.assets, PARAM, BIN)
            if (code != 0) {
                loadError = when (code) {
                    -1 -> "AssetManager 无效"
                    -2 -> "缺少 $PARAM"
                    -3 -> "缺少 $BIN"
                    -4 -> "param 解析失败，可能与 libncnn 版本不匹配"
                    -5 -> "bin 读取失败，文件可能损坏"
                    else -> "ncnn 初始化失败 (code=$code)"
                }
                ready = false
                Log.e(TAG, loadError!!)
                false
            } else {
                loadError = null
                ready = true
                true
            }
        } catch (t: Throwable) {
            loadError = "so 加载失败: ${t.javaClass.simpleName} ${t.message}"
            ready = false
            Log.e(TAG, "init 异常", t)
            false
        }
    }

    fun error(): String? = loadError

    /**
     * @param src 任意尺寸的位图
     * @return 坐标已换算回 [src] 的像素空间
     */
    fun detect(src: Bitmap): List<Person> {
        if (!ready) return emptyList()

        var letterboxed: Bitmap? = null
        try {
            val scale = min(IN_W.toFloat() / src.width, IN_H.toFloat() / src.height)
            val nw = max(1, (src.width * scale).toInt())
            val nh = max(1, (src.height * scale).toInt())
            val padX = (IN_W - nw) / 2
            val padY = (IN_H - nh) / 2

            letterboxed = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(letterboxed)
            canvas.drawColor(Color.BLACK)
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            canvas.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
            if (scaled !== src) scaled.recycle()

            val n = nativeDetect(letterboxed, outBuf, IN_NAME, OUT_NAME)
            if (n <= 0) {
                loadError = when (n) {
                    -10 -> "输入名 $IN_NAME 与模型不符"
                    -11 -> "输出名 $OUT_NAME 与模型不符"
                    -12 -> "输出尺寸超出缓冲区"
                    else -> "推理失败 (code=$n)"
                }
                Log.e(TAG, loadError!!)
                return emptyList()
            }

            val raw = PosePost.parse(outBuf, NUM_ANCHOR, IN_W, IN_H, CONF, NMS_IOU)
            if (raw.isEmpty()) return emptyList()

            // 反 letterbox：先减黑边，再除以缩放系数
            return raw.map { p ->
                Person(
                    (p.left - padX) / scale,
                    (p.top - padY) / scale,
                    (p.right - padX) / scale,
                    (p.bottom - padY) / scale,
                    p.conf,
                    p.kps.map { Keypoint((it.x - padX) / scale, (it.y - padY) / scale, it.conf) }
                )
            }
        } catch (t: Throwable) {
            loadError = "推理异常: ${t.javaClass.simpleName} ${t.message}"
            Log.e(TAG, "detect 异常", t)
            return emptyList()
        } finally {
            letterboxed?.recycle()
        }
    }

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
