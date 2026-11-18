package com.poseguard.lite

import android.graphics.PointF

/**
 * 一帧里检测到的一个人。
 * 坐标单位是原始位图的像素，画到悬浮窗时按屏幕尺寸缩放即可。
 */
data class Person(
    val id: Int,
    val kps: List<PointF>,      // 17 个关键点，COCO 顺序
    val kpConf: FloatArray,
    val score: Float,           // 整人置信度
    val box: FloatArray         // left, top, right, bottom（像素）
) {
    fun has(i: Int): Boolean =
        i in kps.indices && kpConf[i] >= Landmarks.KP_TH

    fun w(): Float = box[2] - box[0]
    fun h(): Float = box[3] - box[1]
}

/** YOLO11-pose 用的是 COCO 17 点 */
object Landmarks {

    const val NOSE = 0
    const val LEFT_EYE = 1
    const val RIGHT_EYE = 2
    const val LEFT_EAR = 3
    const val RIGHT_EAR = 4
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16

    const val COUNT = 17

    /** 关键点可见度低于此值就不画 */
    const val KP_TH = 0.30f

    /** COCO 骨架连线 */
    val SKELETON: Array<IntArray> = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2, 4),
        intArrayOf(5, 6), intArrayOf(5, 11), intArrayOf(6, 12), intArrayOf(11, 12),
        intArrayOf(5, 7), intArrayOf(7, 9), intArrayOf(6, 8), intArrayOf(8, 10),
        intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(12, 14), intArrayOf(14, 16)
    )

    /** 每个人用不同颜色 */
    val COLORS: IntArray = intArrayOf(
        0xFF00FF66.toInt(),
        0xFF00C8FF.toInt(),
        0xFFFFCC00.toInt(),
        0xFFFF55AA.toInt(),
        0xFFAA66FF.toInt()
    )
}
