package com.poseguard.monitor

import android.content.Context
import android.graphics.Bitmap

class PoseDetector(private val ctx: Context) {

    companion object {
        const val IN_W = 640
        const val IN_H = 640
        const val CONF = 0.45f
        const val KP_CONF = 0.30f
    }

    private var ready = false
    private var loadError: String? = null

    fun init(): Boolean {
        ready = true
        loadError = null
        return true
    }

    fun error(): String? = loadError

    fun detect(src: Bitmap): List<Person> {
        return emptyList()
    }

    fun release() {
        ready = false
    }
}
