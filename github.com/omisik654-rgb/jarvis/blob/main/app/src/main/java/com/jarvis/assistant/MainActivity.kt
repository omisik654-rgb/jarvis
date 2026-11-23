package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class HudView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blur = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    private val blurText = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    private val cyan = Color.parseColor("#00E5FF")
    private var t = 0f
    private var amp = 0.1f

    private val tick = object : Runnable {
        override fun run() {
            t += 1f
            invalidate()
            postDelayed(this, 33)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.40f
        val r = minOf(w / 2f, cy) * 0.88f

        val target = when {
            JarvisState.speaking -> 0.75f + 0.25f * Math.sin((t * 0.7f).toDouble()).toFloat()
            JarvisState.thinking -> 0.35f
            JarvisState.listening -> 0.5f
            JarvisState.running -> 0.2f
            else -> 0.1f
        }
        amp += (target - amp) * 0.15f
        val speed = if (JarvisState.thinking) 3f else 1f

        g.style = Paint.Style.STROKE
        g.strokeWidth = 14f
        g.color = Color.argb(150, 0, 229, 255)
        g.maskFilter = blur
        c.drawCircle(cx, cy, r, g)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = cyan
        p.strokeCap = Paint.Cap.ROUND
        c.drawCircle(cx, cy, r, p)

        c.save()
        c.rotate(t * 0.3f * speed, cx, cy)
        for (i in 0 until 72) {
            val big = i % 6 == 0
            val len = if (big) r * 0.07f else r * 0.035f
            p.strokeWidth = if (big) 4f else 2f
            p.color = Color.argb(170, 0, 229, 255)
            c.drawLine(cx, cy - r * 0.97f, cx, cy - r * 0.97f + len, p)
            c.rotate(5f, cx, cy)
        }
        c.restore()

        c.save()
        c.translate(cx, cy)
        p.strokeWidth = 6f
        for (i in 0 until 64) {
            val a = i * 360f / 64f
            val wave = 0.5f + 0.5f * Math.sin((t * 0.25f + i * 0.7f).toDouble()).toFloat()
            val wave2 = 0.6f + 0.4f * Math.sin((i * 1.9f + t * 0.11f).toDouble()).toFloat()
            val len = r * (0.03f + 0.2f * amp * wave * wave2)
            p.color = Color.argb((90 + 150 * amp).toInt().coerceIn(0, 255), 0, 229, 255)
            c.save()
            c.rotate(a)
            c.drawLine(0f, -r * 0.66f, 0f, -r * 0.66f - len, p)
            c.restore()
        }
        c.restore()

        val r1 = RectF(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f)
        p.strokeWidth = 10f
        p.color = Color.parseColor("#4DD0E1")
        for (k in 0 until 3) c.drawArc(r1, t * 1.2f * speed + k * 120f, 70f, false, p)

        val r2 = RectF(cx - r * 0.56f, cy - r * 0.56f, cx + r * 0.56f, cy + r * 0.56f)
        p.strokeWidth = 7f
        p.color = Color.parseColor("#00B8D4")
        for (k in 0 until 4) c.drawArc(r2, -t * 1.6f * speed + k * 90f, 45f, false, p)

        p.strokeWidth = 2f
        p.color = Color.argb(150, 0, 229, 255)
        p.pathEffect = DashPathEffect(floatArrayOf(10f, 14f), t * 2f)
        c.drawCircle(cx, cy, r * 0.46f, p)
        p.pathEffect = null

        if (JarvisState.speaking) {
            for (k in 0 until 3) {
                val ph = (t * 0.02f + k / 3f) % 1f
                p.strokeWidth = 3f
                p.color = Color.argb((200 * (1f - ph)).toInt().coerceIn(0, 255), 0, 229, 255)
                c.drawCircle(cx, cy, r * (0.5f + ph * 0.55f), p)
            }
        }

        val cr = r * (0.2f + 0.05f * amp * Math.sin((t * 0.4f).toDouble()).toFloat())
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, cr * 1.8f,
            intArrayOf(Color.WHITE, Color.parseColor("#80DEEA"), Color.argb(0, 0, 229, 255)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, cr * 1.8f, p)
        p.shader = null

        val word = "J.A.R.V.I.S"
        var size = r * 0.30f
        tp.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        tp.letterSpacing = 0.1f
        tp.textAlign = Paint.Align.CENTER
        tp.style = Paint.Style.FILL
        tp.textSize = size
        while (tp.measureText(word) > w * 0.9f && size > 20f) {
            size -= 2f
            tp.textSize = size
        }
        val ty = cy + r + size * 1.25f
        for (k in 0 until 3) {
            tp.shader = null
            tp.maskFilter = blurText
            tp.color = Color.argb(110, 255, 90 + k * 30, 0)
            val jx = Math.sin((t * 0.9f + k * 2f).toDouble()).toFloat() * 4f
            val jy = -k * size * 0.07f - (2f + 2f * Math.sin((t * 0.5f + k).toDouble()).toFloat())
            c.drawText(word, cx + jx, ty + jy, tp)
        }
        tp.maskFilter = null
        tp.shader = LinearGradient(
            0f, ty - size, 0f, ty,
            intArrayOf(Color.parseColor("#FFF59D"), Color.parseColor("#FFB300"), Color.parseColor("#E53935")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawText(word, cx, ty, tp)
        tp.shader = null
    }
}

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var access: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            status.text = if (JarvisState.line.isBlank()) "Emrinizdeyim efendim" else JarvisState.line
            access.text = "Ekran erişimi: " + (if (JarvisAccess.instance != null) "AÇIK" else "kapalı")
            ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val bg = Color.parseColor("#050B16")
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val d = resources.displayMetrics.density

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 48, 40, 64)
        scroll.addView(root)

        val hud = HudView(this)
        hud.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (460 * d).toInt())
        root.addView(hud)

        status = TextView(this)
        status.textSize = 15f
        status.setTextColor(Color.parseColor("#7FDBFF"))
        status.gravity = Gravity.CENTER
        status.minLines = 2
        root.addView(status)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("▶ Başlat") {
            if (hasMic()) startForegroundService(Intent(this, JarvisService::class.java)) else askPermissions()
        }, weighted())
        row.addView(btn("🎤 Konuş") {
            if (hasMic()) {
                startForegroundService(Intent(this, JarvisService::class.java).setAction("LISTEN"))
            } else {
                askPermissions()
            }
        }, weighted())
        row.addView(btn("■ Durdur") {
            stopService(Intent(this, JarvisService::class.java))
            JarvisState.running = false
            JarvisState.speaking = false
            JarvisState.listening = false
        }, weighted())
        root.addView(row)

        root.addView(btn("Arkadaş modu (sohbet)") {
            if (hasMic()) {
                startForegroundService(Intent(this, JarvisService::class.java).putExtra("friend", true))
            } else {
                askPermissions()
            }
        })

        val settings = LinearLayout(this)
        settings.orientation = LinearLayout.VERTICAL
        settings.visibility = View.GONE

        root.addView(btn("Kurulum ve ayarlar ▾") {
            settings.visibility = if (settings.visibility == View.GONE) View.VISIBLE else View.GONE
        })

        access = TextView(this)
        access.setTextColor(Color.parseColor("#6FA8B5"))
        access.gravity = Gravity.CENTER
        access.setPadding(0, 16, 0, 8)
        settings.addView(access)

        settings.addView(btn("1) İzinleri ver") { askPermissions() })
        settings.addView(btn("2) Üstte gösterme izni") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        })
        settings.addView(btn("3) Pil kısıtlamasını kaldır") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))
                )
            } catch (e: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        settings.addView(btn("4) Xiaomi: arka planda açılır pencere izni") {
            try {
                val i = Intent("miui.intent.action.APP_PERM_EDITOR")
                i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                i.putExtra("extra_pkgname", packageName)
                startActivity(i)
            } catch (e: Throwable) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            }
        })
        settings.addView(btn("5) Uygulama bilgisi (kısıtlı ayarlara izin ver)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        })
        settings.addView(btn("6) Ekran erişimini aç (Erişilebilirlik)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        val keyField = EditText(this)
        keyField.hint = "Yapay zeka API anahtarı"
        keyField.setHintTextColor(Color.parseColor("#4A6670"))
        keyField.setTextColor(Color.WHITE)
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        settings.addView(keyField)
        settings.addView(btn("7) Anahtarı kaydet") {
            val k = keyField.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("api_key", k).apply()
                keyField.setText("")
                Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            }
        })

        val info = TextView(this)
        info.setTextIsSelectable(true)
        info.setTextColor(Color.WHITE)
        info.setPadding(0, 24, 0, 24)
        settings.addView(btn("Son cevabı göster") {
            info.text = prefs.getString("last_answer", "Henüz cevap yok")
        })
        settings.addView(btn("Ses kaydımı sil") {
            prefs.edit().remove("spk_ref").apply()
            Toast.makeText(this, "Ses kaydı silindi", Toast.LENGTH_SHORT).show()
        })
        settings.addView(info)

        root.addView(settings)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun weighted(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(4, 0, 4, 0)
        return lp
    }

    private fun btn(text: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.setTextColor(Color.parseColor("#00E5FF"))
        val gd = GradientDrawable()
        gd.setColor(Color.parseColor("#1200E5FF"))
        gd.setStroke(3, Color.parseColor("#00E5FF"))
        gd.cornerRadius = 48f
        b.background = gd
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
