package com.poseguard.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * MediaPipe Pose Landmarker 封装。
 *
 * 用 IMAGE 模式同步推理（一次一帧），简单可控；
 * 模型 assets/pose_landmarker_lite.task 自带，不需要任何 JNI。
 */
class PoseEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "PoseEngine"
        private const val MODEL = "pose_landmarker_lite.task"

        /** MediaPipe 输出关键点顺序固定为 33，不加追踪只用单人时 id 永远是 0 */
        private const val MAX_POSES = 3
    }

    private var landmarker: PoseLandmarker? = null
    private var loadError: String? = null

    @Volatile
    var ready = false
        private set

    val lastResult = AtomicReference<PoseLandmarkerResult?>(null)

    fun init(): Boolean {
        return try {
            loadError = null
            ctx.assets.open(MODEL).close()

            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL)
                .setDelegate(Delegate.CPU)
                .build()

            landmarker = PoseLandmarker.createFromOptions(
                ctx,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(MAX_POSES)
                    .setMinPoseDetectionConfidence(0.35f)
                    .setMinPosePresenceConfidence(0.35f)
                    .setMinTrackingConfidence(0.35f)
                    .build()
            )
            ready = true
            Log.i(TAG, "初始化成功")
            true
        } catch (e: Exception) {
            loadError = "模型加载失败: ${e.javaClass.simpleName} ${e.message}"
            ready = false
            Log.e(TAG, loadError, e)
            false
        }
    }

    /** 检测一帧，返回归一化坐标（相对传入的 bmp 尺寸） */
    fun detect(bmp: Bitmap): List<Person> {
        val lm = landmarker ?: return emptyList()
        if (!ready) return emptyList()

        return try {
            val image = BitmapImageBuilder(bmp).build()
            val result = lm.detect(image)
            lastResult.set(result)

            val out = ArrayList<Person>(result.landmarks().size)
            result.landmarks().forEachIndexed { index, p ->
                if (p.size < Landmarks.COUNT) return@forEachIndexed

                val kps = ArrayList<PointF>(Landmarks.COUNT)
                val conf = FloatArray(Landmarks.COUNT)
                var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f

                for (i in 0 until Landmarks.COUNT) {
                    val n = p[i]
                    val x = n.x().coerceIn(0f, 1f)
                    val y = n.y().coerceIn(0f, 1f)
                    kps.add(PointF(x, y))
                    conf[i] = if (n.visibility().isPresent) n.visibility().get() else 0f
                    if (conf[i] >= 0.4f) {
                        minX = min(minX, x); maxX = max(maxX, x)
                        minY = min(minY, y); maxY = max(maxY, y)
                    }
                }

                var score = 0f
                for (i in 0 until Landmarks.COUNT) score += conf[i]
                score /= Landmarks.COUNT

                if (maxX <= minX || maxY <= minY) return@forEachIndexed

                out.add(
                    Person(
                        id = index,
                        kps = kps,
                        kpConf = conf,
                        score = score,
                        box = floatArrayOf(minX, minY, maxX, maxY)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            loadError = "推理异常: ${t.javaClass.simpleName} ${t.message}"
            Log.e(TAG, "detect 失败", t)
            emptyList()
        }
    }

    fun error(): String? = loadError

    fun release() {
        runCatching { landmarker?.close() }
        landmarker = null
        ready = false
    }
}
