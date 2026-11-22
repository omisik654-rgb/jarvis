package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class JarvisService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val trLocale = Locale("tr", "TR")

    private var enModel: Model? = null
    private var trModel: Model? = null
    private var wakeSpeech: SpeechService? = null
    private var cmdSpeech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var loading = false
    private var busy = false
    private var handled = false
    private var lastPartial = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification("Hazırlanıyor...")
        if (tts == null) initTts()
        if (enModel == null && !loading) {
            loading = true
            Thread {
                try {
                    val en = Model(prepare("model").absolutePath)
                    val tr = Model(prepare("model-tr").absolutePath)
                    enModel = en
                    trModel = tr
                    handler.post { startWake() }
                } catch (e: Throwable) {
                    showNotification("Hata: ${e.message}")
                } finally {
                    loading = false
                }
            }.start()
        }
        return START_STICKY
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = trLocale
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { spoken(id) } }
                    override fun onError(id: String?) { handler.post { spoken(id) } }
                })
                ttsReady = true
            }
        }
    }

    private fun say(text: String, id: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) else spoken(id)
    }

    private fun spoken(id: String?) {
        if (id == "wake") startCommand() else resumeWake()
    }

    private val wakeListener = object : RecognitionListener {
        override fun onPartialResult(h: String?) { checkWake(h) }
        override fun onResult(h: String?) { checkWake(h) }
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { showNotification("Hata: ${e?.message}") }
        override fun onTimeout() {}
    }

    private val cmdListener = object : RecognitionListener {
        override fun onPartialResult(h: String?) {
            val p = field(h, "partial")
            if (p.isNotBlank()) lastPartial = p
        }
        override fun onResult(h: String?) { gotText(field(h, "text")) }
        override fun onFinalResult(h: String?) { gotText(field(h, "text")) }
        override fun onError(e: Exception?) { resumeWake() }
        override fun onTimeout() {
            if (lastPartial.isBlank()) {
                if (!handled) {
                    handled = true
                    resumeWake()
                }
            } else {
                gotText(lastPartial)
            }
        }
    }

    private fun startWake() {
        try {
            val rec = Recognizer(enModel!!, 16000.0f, "[\"jarvis\", \"[unk]\"]")
            wakeSpeech = SpeechService(rec, 16000.0f)
            wakeSpeech?.startListening(wakeListener)
            busy = false
            showNotification("Jarvis dinliyor")
        } catch (e: Throwable) {
            showNotification("Hata: ${e.message}")
        }
    }

    private fun stopWake() {
        try {
            wakeSpeech?.stop()
            wakeSpeech?.shutdown()
        } catch (e: Throwable) {
        }
        wakeSpeech = null
    }

    private fun checkWake(h: String?) {
        if (busy) return
        val t = field(h, "partial") + " " + field(h, "text")
        if (!t.contains("jarvis")) return
        busy = true
        stopWake()
        say("Buyurun", "wake")
    }

    private fun startCommand() {
        handled = false
        lastPartial = ""
        try {
            val rec = Recognizer(trModel!!, 16000.0f)
            cmdSpeech = SpeechService(rec, 16000.0f)
            cmdSpeech?.startListening(cmdListener, 7000)
            showNotification("Dinliyorum, komutu söyle...")
        } catch (e: Throwable) {
            showNotification("Hata: ${e.message}")
            resumeWake()
        }
    }

    private fun stopCmd() {
        try {
            cmdSpeech?.stop()
            cmdSpeech?.shutdown()
        } catch (e: Throwable) {
        }
        cmdSpeech = null
    }

    private fun gotText(text: String) {
        if (handled) return
        if (text.isBlank()) return
        handled = true
        stopCmd()
        showNotification("Duyduğum: $text")
        say(execute(text.lowercase(trLocale)), "reply")
    }

    private fun resumeWake() {
        stopCmd()
        stopWake()
        startWake()
    }

    private fun execute(t: String): String {
        return when {
            t.contains("saat") -> {
                val c = Calendar.getInstance()
                "Saat " + String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            t.contains("tarih") || t.contains("bugün") -> {
                SimpleDateFormat("d MMMM EEEE", trLocale).format(Date())
            }
            t.contains("pil") || t.contains("şarj") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                "Pil yüzde " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            t.contains("fener") -> setTorch(!(t.contains("kapat") || t.contains("söndür")))
            t.contains("ses") && (t.contains("artır") || t.contains("yükselt") || t.contains("aç")) -> volume(true)
            t.contains("ses") && (t.contains("azalt") || t.contains("kıs") || t.contains("düşür")) -> volume(false)
            t.contains("merhaba") || t.contains("selam") -> "Merhaba, size nasıl yardımcı olabilirim"
            else -> "Anlayamadım. Şunu duydum: $t"
        }
    }

    private fun setTorch(on: Boolean): String {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.setTorchMode(id, on)
            if (on) "Fener açıldı" else "Fener kapatıldı"
        } catch (e: Throwable) {
            "Feneri çalıştıramadım"
        }
    }

    private fun volume(up: Boolean): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
        return if (up) "Ses artırıldı" else "Ses azaltıldı"
    }

    private fun field(json: String?, key: String): String {
        return try {
            JSONObject(json ?: "").optString(key, "")
        } catch (e: Throwable) {
            ""
        }
    }

    private fun prepare(name: String): File {
        val out = File(filesDir, name)
        val marker = File(out, ".ok")
        if (!marker.exists()) {
            out.deleteRecursively()
            copyAssets(name, out)
            marker.createNewFile()
        }
        return out
    }

    private fun copyAssets(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (c in children) copyAssets("$assetPath/$c", File(dest, c))
        }
    }

    private fun showNotification(text: String) {
        val channelId = "jarvis"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        stopWake()
        stopCmd()
        try {
            enModel?.close()
            trModel?.close()
        } catch (e: Throwable) {
        }
        tts?.shutdown()
        super.onDestroy()
    }
}
