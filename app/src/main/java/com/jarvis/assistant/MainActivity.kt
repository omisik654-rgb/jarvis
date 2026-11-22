package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)
        scroll.addView(root)

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

        val battery = Button(this)
        battery.text = "3) Pil kisitlamasini kaldir"
        battery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + packageName)
                    )
                )
            } catch (e: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        val keyField = EditText(this)
        val saved = (prefs.getString("api_key", "") ?: "").isNotBlank()
        keyField.hint = if (saved) "Anahtar kayitli (degistirmek icin yenisini yaz)" else "Yapay zeka API anahtari"
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val saveKey = Button(this)
        saveKey.text = "4) Anahtari kaydet"
        saveKey.setOnClickListener {
            val k = keyField.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("api_key", k).apply()
                keyField.setText("")
                Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            }
        }

        val start = Button(this)
        start.text = "5) Jarvis'i Baslat"
        start.setOnClickListener {
            if (hasMic()) {
                startForegroundService(Intent(this, JarvisService::class.java))
            } else {
                askPermissions()
            }
        }

        val friend = Button(this)
        friend.text = "Arkadas modu (sohbet)"
        friend.setOnClickListener {
            if (hasMic()) {
                startForegroundService(Intent(this, JarvisService::class.java).putExtra("friend", true))
            } else {
                askPermissions()
            }
        }

        val stop = Button(this)
        stop.text = "Durdur"
        stop.setOnClickListener { stopService(Intent(this, JarvisService::class.java)) }

        val info = TextView(this)
        info.setTextIsSelectable(true)
        info.setPadding(0, 32, 0, 32)

        val last = Button(this)
        last.text = "Son cevabi goster"
        last.setOnClickListener {
            info.text = prefs.getString("last_answer", "Henuz cevap yok")
        }

        val delVoice = Button(this)
        delVoice.text = "Ses kaydimi sil"
        delVoice.setOnClickListener {
            prefs.edit().remove("spk_ref").apply()
            Toast.makeText(this, "Ses kaydi silindi", Toast.LENGTH_SHORT).show()
        }

        root.addView(perm)
        root.addView(overlay)
        root.addView(battery)
        root.addView(keyField)
        root.addView(saveKey)
        root.addView(start)
        root.addView(friend)
        root.addView(stop)
        root.addView(last)
        root.addView(delVoice)
        root.addView(info)
        setContentView(scroll)
    }

    private fun hasMic(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
