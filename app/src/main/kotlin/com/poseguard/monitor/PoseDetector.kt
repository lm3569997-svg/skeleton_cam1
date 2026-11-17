package com.poseguard.monitor

import android.content.Context
import android.graphics.Bitmap
import com.tencent.ncnn.Net
import com.tencent.ncnn.Mat

class PoseDetector(private val ctx: Context) {

    companion object {
        const val IN_W = 640
        const val IN_H = 640
        const val NUM = 8400
        const val CONF = 0.45f
        const val KP_CONF = 0.30f
    }

    private var net: Net? = null
    private var ready = false
    private var loadError: String? = null

    fun init(): Boolean {
        if (ready) return true
        return try {
            val n = Net()
            val param = ctx.assets.open("yolov8n_pose.ncnn.param").readBytes()
            val bin = ctx.assets.open("yolov8n_pose.ncnn.bin").readBytes()
            n.loadParam(param)
            n.loadModel(bin)
            n.opt.use_vulkan_compute = true
            n.opt.num_threads = 4
            net = n
            ready = true
            true
        } catch (e: Exception) {
            loadError = "${e.javaClass.simpleName}: ${e.message}"
            ready = false
            false
        }
    }

    fun error(): String? = loadError

    fun detect(src: Bitmap): List<Person> {
        val n = net ?: return emptyList()
        if (!ready) return emptyList()

        val scaled = Bitmap.createScaledBitmap(src, IN_W, IN_H, true)
        val inMat = Mat()
        Mat.fromBitmap(inMat, scaled)

        val outMat = Mat()
        val ex = n.createExtractor()
        ex.input("images", inMat)
        ex.extract("output0", outMat)

        val w = outMat.w
        val h = outMat.h
        val flat = outMat.toFloatArray()
        if (flat.size < w * h) {
            outMat.release(); inMat.release()
            return emptyList()
        }

        val num = if (h == NUM) NUM else w
        val lst = PosePost.parse(flat, num, IN_W, IN_H, CONF)
        outMat.release(); inMat.release()

        val sx = src.width.toFloat() / IN_W
        val sy = src.height.toFloat() / IN_H
        return lst.map { p ->
            Person(
                p.left * sx, p.top * sy, p.right * sx, p.bottom * sy, p.conf,
                p.kps.map { Keypoint(it.x * sx, it.y * sy, it.conf) }
            )
        }
    }

    fun release() {
        net?.clear()
        net = null
        ready = false
    }
}
