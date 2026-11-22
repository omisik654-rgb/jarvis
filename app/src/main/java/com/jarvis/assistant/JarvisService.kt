package com.jarvis.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.view.KeyEvent
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
    private var pending: (() -> String)? = null
    private var awaitConfirm = false

    private val nums = mapOf(
        "sıfır" to 0, "bir" to 1, "iki" to 2, "üç" to 3, "dört" to 4, "beş" to 5,
        "altı" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10,
        "yirmi" to 20, "otuz" to 30, "kırk" to 40, "elli" to 50,
        "altmış" to 60, "yetmiş" to 70, "seksen" to 80, "doksan" to 90
    )
    private val sfx = listOf("", "da", "de", "ta", "te", "a", "e", "ya", "ye")

    private val aliases = mapOf(
        "votsap" to "whatsapp", "vatsap" to "whatsapp", "vatsapp" to "whatsapp",
        "whatsapp" to "whatsapp", "yutub" to "youtube", "youtube" to "youtube",
        "instagram" to "instagram", "telegram" to "telegram",
        "rehber" to "kişiler", "kişiler" to "kişiler", "mesaj" to "mesaj",
        "hesap" to "hesap", "harita" to "harita", "kamera" to "kamera",
        "galeri" to "galeri", "ayar" to "ayarlar", "telefon" to "telefon",
        "saat" to "saat", "takvim" to "takvim", "chrome" to "chrome",
        "krom" to "chrome", "tarayıcı" to "chrome"
    )

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
        if (id == "wake" || awaitConfirm) startCommand() else resumeWake()
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
        val low = text.lowercase(trLocale)
        val reply = if (awaitConfirm) confirm(low) else execute(low)
        say(reply, "reply")
    }

    private fun resumeWake() {
        pending = null
        awaitConfirm = false
        stopCmd()
        stopWake()
        startWake()
    }

    private fun confirm(t: String): String {
        awaitConfirm = false
        val action = pending
        pending = null
        val yes = t.contains("evet") || t.contains("gönder") || t.contains("tamam") || t.contains("olur")
        return if (action != null && yes) action() else "Vazgeçtim"
    }

    private fun execute(t: String): String {
        val words = t.split(" ").filter { it.isNotBlank() }
        val isOpen = words.any { it == "aç" || it == "başlat" || it == "çalıştır" || it == "git" }
        return when {
            t.contains("alarm") -> setAlarm(t, words)
            t.contains("sayaç") || t.contains("sayac") || t.contains("zamanlayıcı") || t.contains("geri sayım") -> setTimer(t, words)
            words.any { it == "ara" || it == "arar" || it == "arayın" } -> callName(words)
            words.any { it == "mesaj" || it == "sms" } -> prepareSms(words)
            t.contains("müzi") || t.contains("şarkı") -> musicControl(t)
            t.contains("fener") -> setTorch(!(t.contains("kapat") || t.contains("söndür")))
            t.contains("sessiz") || t.contains("titreşim") -> ringer(AudioManager.RINGER_MODE_VIBRATE, "Telefon titreşime alındı")
            t.contains("zil") && (t.contains("aç") || t.contains("normal")) -> ringer(AudioManager.RINGER_MODE_NORMAL, "Zil sesi açıldı")
            t.contains("ses") && (t.contains("artır") || t.contains("yükselt") || t.contains("aç")) -> volume(true)
            t.contains("ses") && (t.contains("azalt") || t.contains("kıs") || t.contains("düşür")) -> volume(false)
            t.contains("saat") && !isOpen -> {
                val c = Calendar.getInstance()
                "Saat " + String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            t.contains("tarih") || t.contains("bugün") -> SimpleDateFormat("d MMMM EEEE", trLocale).format(Date())
            t.contains("pil") || t.contains("şarj") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                "Pil yüzde " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            isOpen -> openApp(t)
            t.contains("merhaba") || t.contains("selam") -> "Merhaba, size nasıl yardımcı olabilirim"
            else -> "Anlayamadım. Şunu duydum: $t"
        }
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun numberOf(w: String): Int? {
        val direct = w.toIntOrNull()
        if (direct != null) return direct
        for ((k, v) in nums) {
            for (s in sfx) {
                if (w == k + s) return v
            }
        }
        return null
    }

    private fun numbersIn(words: List<String>): List<Int> {
        val out = mutableListOf<Int>()
        for (w in words) {
            if (w.startsWith("buçuk")) {
                out.add(30)
                continue
            }
            val n = numberOf(w) ?: continue
            if (out.isNotEmpty() && out.last() in 10..90 && out.last() % 10 == 0 && n in 1..9) {
                out[out.lastIndex] = out.last() + n
            } else {
                out.add(n)
            }
        }
        return out
    }

    private fun setAlarm(t: String, words: List<String>): String {
        val n = numbersIn(words)
        if (n.isEmpty()) return "Saati anlayamadım"
        var hour = n[0]
        val minute = if (n.size > 1) n[1] else 0
        if ((t.contains("akşam") || t.contains("öğleden sonra")) && hour < 12) hour += 12
        if (hour > 23 || minute > 59) return "Saati anlayamadım"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return try {
            launch(i)
            "Alarm " + hour + " " + String.format("%02d", minute) + " için kuruldu"
        } catch (e: Throwable) {
            "Alarm kuramadım"
        }
    }

    private fun setTimer(t: String, words: List<String>): String {
        val n = numbersIn(words)
        if (n.isEmpty()) return "Süreyi anlayamadım"
        val sec = when {
            t.contains("saniye") -> n[0]
            t.contains("saat") -> n[0] * 3600
            else -> n[0] * 60
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return try {
            launch(i)
            "Sayaç kuruldu"
        } catch (e: Throwable) {
            "Sayaç kuramadım"
        }
    }

    private fun findContact(words: List<String>): Pair<String, String>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val parts = name.lowercase(trLocale).split(" ").filter { p -> p.length >= 3 }
                for (w in words) {
                    if (parts.any { p -> w.startsWith(p) }) return Pair(name, number)
                }
            }
        }
        return null
    }

    private fun callName(words: List<String>): String {
        val idx = words.indexOfFirst { it == "ara" || it == "arar" || it == "arayın" }
        val nameWords = if (idx > 0) words.take(idx) else words
        val c = findContact(nameWords) ?: return "Rehberde böyle bir kişi bulamadım"
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Arama izni yok"
        }
        return try {
            launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.second))))
            c.first + " aranıyor"
        } catch (e: Throwable) {
            "Arayamadım"
        }
    }

    private fun prepareSms(words: List<String>): String {
        val idx = words.indexOfFirst { it == "mesaj" || it == "sms" }
        if (idx <= 0) return "Kime mesaj göndereceğimi anlayamadım"
        val verbs = setOf("at", "gönder", "yaz", "yolla")
        val body = words.drop(idx + 1).dropWhile { it in verbs }.joinToString(" ")
        if (body.isBlank()) return "Mesajın ne olacağını anlayamadım"
        val c = findContact(words.take(idx)) ?: return "Rehberde böyle bir kişi bulamadım"
        pending = { sendSms(c.second, body) }
        awaitConfirm = true
        return c.first + " kişisine şu mesajı göndereyim mi: " + body
    }

    private fun sendSms(number: String, body: String): String {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "Mesaj izni yok"
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
            "Mesaj gönderildi"
        } catch (e: Throwable) {
            "Mesaj gönderilemedi"
        }
    }

    private fun musicControl(t: String): String {
        val code: Int
        val ok: String
        if (t.contains("durdur") || t.contains("duraklat") || t.contains("kapat")) {
            code = KeyEvent.KEYCODE_MEDIA_PAUSE
            ok = "Müzik durduruldu"
        } else if (t.contains("sonraki") || t.contains("ileri") || t.contains("geç")) {
            code = KeyEvent.KEYCODE_MEDIA_NEXT
            ok = "Sonraki şarkı"
        } else if (t.contains("önceki") || t.contains("geri")) {
            code = KeyEvent.KEYCODE_MEDIA_PREVIOUS
            ok = "Önceki şarkı"
        } else {
            code = KeyEvent.KEYCODE_MEDIA_PLAY
            ok = "Müzik başlatıldı"
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok
    }

    private fun ringer(mode: Int, ok: String): String {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = mode
            ok
        } catch (e: Throwable) {
            "Zil modunu değiştiremedim"
        }
    }

    private fun openApp(t: String): String {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        var target: String? = null
        for ((k, v) in aliases) {
            if (t.contains(k)) {
                target = v
                break
            }
        }
        var pkg: String? = null
        var label = ""
        for (a in apps) {
            val l = a.loadLabel(pm).toString().lowercase(trLocale)
            val hit = if (target != null) l.contains(target) else (l.length >= 3 && t.contains(l))
            if (hit) {
                pkg = a.activityInfo.packageName
                label = l
                break
            }
        }
        if (pkg == null) return "Uygulamayı bulamadım"
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "Uygulamayı açamadım"
        return try {
            launch(i)
            label + " açılıyor"
        } catch (e: Throwable) {
            "Açamadım"
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
