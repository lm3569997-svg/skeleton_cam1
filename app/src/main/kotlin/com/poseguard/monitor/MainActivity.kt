package com.poseguard.monitor

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

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        val start = findViewById<Button>(R.id.btnStart)
        val stop = findViewById<Button>(R.id.btnStop)
        val perm = findViewById<Button>(R.id.btnPerm)
        perm.setOnClickListener { requestPerms() }
        start.setOnClickListener { doStart() }
        stop.setOnClickListener {
            stopService(Intent(this, CameraService::class.java)); refresh()
        }
        refresh()
    }
}
