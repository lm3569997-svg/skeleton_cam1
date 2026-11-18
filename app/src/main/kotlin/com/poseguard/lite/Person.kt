package com.poseguard.lite

import android.graphics.PointF

/**
 * 一帧里检测到的一个人。
 * 坐标全部归一化到 [0,1]，原点左上角，乘屏幕宽高就能画。
 */
data class Person(
    val id: Int,
    val kps: List<PointF>,      // 17 个关键点，COCO 顺序
    val kpConf: FloatArray,
    val score: Float,
    val box: FloatArray         // left, top, right, bottom（归一化）
) {
    fun has(i: Int): Boolean = i in kps.indices && kpConf[i] >= 0.25f
}

/** YOLO-pose 的 17 点 COCO 顺序 */
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

    /** 17 点骨架连线 */
    val SKELETON: Array<IntArray> = arrayOf(
        // 头
        intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2, 4),
        // 肩
        intArrayOf(5, 6), intArrayOf(5, 11), intArrayOf(6, 12), intArrayOf(11, 12),
        // 左臂
        intArrayOf(5, 7), intArrayOf(7, 9),
        // 右臂
        intArrayOf(6, 8), intArrayOf(8, 10),
        // 左腿
        intArrayOf(11, 13), intArrayOf(13, 15),
        // 右腿
        intArrayOf(12, 14), intArrayOf(14, 16)
    )

    /** 每人一个颜色 */
    val COLORS: IntArray = intArrayOf(
        0xFF00FF66.toInt(),
        0xFF00C8FF.toInt(),
        0xFFFFCC00.toInt(),
        0xFFFF55AA.toInt(),
        0xFFAA66FF.toInt()
    )
}
