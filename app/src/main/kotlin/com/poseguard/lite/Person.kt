package com.poseguard.lite

import android.graphics.PointF

/**
 * 一帧里检测到的一个人。
 * 坐标全部是归一化的 [0,1]，原点左上角，已经是「显示方向」，
 * 画到悬浮窗上时只要乘以屏幕宽高即可。
 */
data class Person(
    val id: Int,
    val kps: List<PointF>,      // 33 个关键点，MediaPipe 顺序
    val kpConf: FloatArray,     // 每个点的可见度
    val score: Float,           // 整人置信度
    val box: FloatArray         // left, top, right, bottom（归一化）
) {
    fun has(i: Int): Boolean = i in kps.indices && kpConf[i] >= 0.5f
}

/** MediaPipe Pose Landmarker 的 33 点顺序 */
object Landmarks {
    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val MOUTH_LEFT = 9
    const val MOUTH_RIGHT = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_THUMB = 21
    const val RIGHT_THUMB = 22
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    const val COUNT = 33

    /** 要连线的点对 */
    val SKELETON: Array<IntArray> = arrayOf(
        // 脸
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 7),
        intArrayOf(0, 4), intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 8),
        intArrayOf(9, 10),
        // 躯干
        intArrayOf(11, 12), intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
        // 左臂
        intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(15, 17), intArrayOf(15, 19),
        intArrayOf(15, 21), intArrayOf(17, 19),
        // 右臂
        intArrayOf(12, 14), intArrayOf(14, 16), intArrayOf(16, 18), intArrayOf(16, 20),
        intArrayOf(16, 22), intArrayOf(18, 20),
        // 左腿
        intArrayOf(23, 25), intArrayOf(25, 27), intArrayOf(27, 29), intArrayOf(27, 31),
        intArrayOf(29, 31),
        // 右腿
        intArrayOf(24, 26), intArrayOf(26, 28), intArrayOf(28, 30), intArrayOf(28, 32),
        intArrayOf(30, 32)
    )

    /** 每个人用不同颜色画 */
    val COLORS: IntArray = intArrayOf(
        0xFF00FF66.toInt(),
        0xFF00C8FF.toInt(),
        0xFFFFCC00.toInt(),
        0xFFFF55AA.toInt(),
        0xFFAA66FF.toInt()
    )
}
