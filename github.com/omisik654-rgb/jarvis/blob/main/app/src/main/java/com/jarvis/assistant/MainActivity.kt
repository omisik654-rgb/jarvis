package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ArcView(ctx: android.content.Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val tick = object : Runnable {
        override fun run() {
            t += 1.6f
            invalidate()
            postDelayed(this, 30)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.9f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = Color.parseColor("#00E5FF")
        c.drawCircle(cx, cy, r, p)
        val r1 = RectF(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f)
        p.strokeWidth = 9f
        p.color = Color.parseColor("#4DD0E1")
        for (k in 0 until 3) c.drawArc(r1, t + k * 120f, 70f, false, p)
        val r2 = RectF(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f)
        p.strokeWidth = 6f
        p.color = Color.parseColor("#00B8D4")
        for (k in 0 until 4) c.drawArc(r2, -t * 1.5f + k * 90f, 50f, false, p)
        p.style = Paint.Style.FILL
        val glow = 0.25f + 0.08f * Math.sin(t / 10.0).toFloat()
        p.color = Color.parseColor("#B2EBF2")
        c.drawCircle(cx, cy, r * glow, p)
    }
}

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val bg = Color.parseColor("#050B16")
        val cy = Color.parseColor("#00E5FF")
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 80, 48, 64)
        scroll.addView(root)

        val arc = ArcView(this)
        arc.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 560)
        root.addView(arc)

        val title = TextView(this)
        title.text = "J.A.R.V.I.S."
        title.textSize = 30f
        title.setTextColor(cy)
        title.gravity = Gravity.CENTER
        title.letterSpacing = 0.3f
        title.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Emrinizdeyim efendim"
        sub.textSize = 14f
        sub.setTextColor(Color.parseColor("#6FA8B5"))
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, 8, 0, 8)
        root.addView(sub)

        status = TextView(this)
        status.textSize = 13f
        status.setTextColor(Color.parseColor("#6FA8B5"))
        status.gravity = Gravity.CENTER
        status.setPadding(0, 0, 0, 24)
        root.addView(status)

        root.addView(btn("1) İzinleri ver") { askPermissions() })
        root.addView(btn("2) Üstte gösterme izni") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        })
        root.addView(btn("3) Pil kısıtlamasını kaldır") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))
                )
            } catch (e: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        root.addView(btn("4) Uygulama bilgisi (kısıtlı ayarlara izin ver)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        })
        root.addView(btn("5) Ekran erişimini aç (Erişilebilirlik)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        val keyField = EditText(this)
        keyField.hint = "Yapay zeka API anahtarı"
        keyField.setHintTextColor(Color.parseColor("#4A6670"))
        keyField.setTextColor(Color.WHITE)
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(keyField)
        root.addView(btn("6) Anahtarı kaydet") {
            val k = keyField.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("api_key", k).apply()
                keyField.setText("")
                Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        })

        root.addView(btn("▶  Jarvis'i başlat") {
            if (hasMic()) startForegroundService(Intent(this, JarvisService::class.java)) else askPermissions()
        })
        root.addView(btn("Arkadaş modu (sohbet)") {
            if (hasMic()) {
                startForegroundService(Intent(this, JarvisService::class.java).putExtra("friend", true))
            } else {
                askPermissions()
            }
        })
        root.addView(btn("■  Durdur") { stopService(Intent(this, JarvisService::class.java)) })

        val info = TextView(this)
        info.setTextIsSelectable(true)
        info.setTextColor(Color.WHITE)
        info.setPadding(0, 32, 0, 32)
        root.addView(btn("Son cevabı göster") {
            info.text = prefs.getString("last_answer", "Henüz cevap yok")
        })
        root.addView(btn("Ses kaydımı sil") {
            prefs.edit().remove("spk_ref").apply()
            Toast.makeText(this, "Ses kaydı silindi", Toast.LENGTH_SHORT).show()
        })
        root.addView(info)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val key = (prefs.getString("api_key", "") ?: "").isNotBlank()
        val acc = JarvisAccess.instance != null
        status.text = "Ekran erişimi: " + (if (acc) "AÇIK" else "kapalı") +
            "   •   Yapay zeka anahtarı: " + (if (key) "kayıtlı" else "yok")
    }

    private fun btn(text: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(Color.parseColor("#00E5FF"))
        val g = GradientDrawable()
        g.setColor(Color.parseColor("#1200E5FF"))
        g.setStroke(3, Color.parseColor("#00E5FF"))
        g.cornerRadius = 48f
        b.background = g
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 16, 0, 0)
        b.layoutParams = lp
        b.setOnClickListener { action() }
        return b
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
