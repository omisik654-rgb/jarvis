package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "JARVIS"
            textSize = 36f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Başlattıktan sonra ekranı kapat ve \"Jarvis\" de.\nİlk açılışta model hazırlanır (birkaç saniye)."
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        })

        root.addView(Button(this).apply {
            text = "Jarvis'i Başlat"
            setOnClickListener { startJarvis() }
        })

        root.addView(Button(this).apply {
            text = "Durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, JarvisService::class.java)) }
        })

        setContentView(root)
    }

    private fun startJarvis() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
        } else {
            startForegroundService(Intent(this, JarvisService::class.java))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, JarvisService::class.java))
        }
    }
}
