package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val perm = Button(this)
        perm.text = "1) Izinleri ver"
        perm.setOnClickListener { askPermissions() }

        val overlay = Button(this)
        overlay.text = "2) Ustte gosterme iznini ac"
        overlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
        }

        val start = Button(this)
        start.text = "3) Jarvis'i Baslat"
        start.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startForegroundService(Intent(this, JarvisService::class.java))
            } else {
                askPermissions()
            }
        }

        val stop = Button(this)
        stop.text = "Durdur"
        stop.setOnClickListener { stopService(Intent(this, JarvisService::class.java)) }

        root.addView(perm)
        root.addView(overlay)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }
}
