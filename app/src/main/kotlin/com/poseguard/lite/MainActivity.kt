package com.poseguard.lite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestPerms() }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayHint()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPerms()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(
                this, Intent(this, CameraService::class.java)
            )
            status.postDelayed({ refresh() }, 1200)
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CameraService::class.java))
            status.postDelayed({ refresh() }, 400)
        }

        refresh()
    }

    private fun requestPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 100)
    }

    private fun overlayHint() {
        android.widget.Toast.makeText(
            this, "请先允许「显示在其他应用上层」", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        val sb = StringBuilder()
        sb.append("运行 : ").append(if (CameraService.running) "是" else "否").append('\n')
        sb.append("悬浮窗: ").append(if (ov) "已授权" else "未授权").append('\n')
        sb.append("相机 : ").append(if (cam) "已授权" else "未授权").append('\n')
        sb.append("FPS  : ").append(CameraService.lastFps).append('\n')
        sb.append("目标 : ").append(CameraService.lastCount)
        CameraService.lastError?.let { sb.append("\n错误 : ").append(it) }
        status.text = sb.toString()
    }
}
