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
    private var buffer = ""
    private var followUp: ((String) -> String)? = null

    private val finalizeRunnable = Runnable { gotText(buffer) }

    private val nums = mapOf(
        "sifir" to 0, "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5,
        "alti" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10,
        "yirmi" to 20, "otuz" to 30, "kirk" to 40, "elli" to 50,
        "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90
    )
    private val sfx = listOf("", "da", "de", "ta", "te", "a", "e", "ya", "ye")

    private val aliases = linkedMapOf(
        "votsap" to "whatsapp", "vatsap" to "whatsapp", "whatsapp" to "whatsapp",
        "yutub" to "youtube", "youtube" to "youtube",
        "instagram" to "instagram", "telegram" to "telegram", "spotify" to "spotify",
        "rehber" to "kisiler", "kisiler" to "kisiler",
        "harita" to "harita", "kamera" to "kamera", "galeri" to "galeri",
        "ayar" to "ayarlar", "telefon" to "telefon", "takvim" to "takvim",
        "chrome" to "chrome", "krom" to "chrome", "tarayici" to "chrome",
        "hesap" to "hesap"
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
        if (id == "wake" || followUp != null) startCommand() else resumeWake()
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
            if (p.isNotBlank()) {
                lastPartial = p
                handler.removeCallbacks(finalizeRunnable)
            }
        }
        override fun onResult(h: String?) {
            val t = field(h, "text")
            if (t.isNotBlank()) {
                buffer = (buffer + " " + t).trim()
                handler.removeCallbacks(finalizeRunnable)
                handler.postDelayed(finalizeRunnable, 1200)
            }
        }
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { resumeWake() }
        override fun onTimeout() {
            handler.removeCallbacks(finalizeRunnable)
            val txt = if (buffer.isNotBlank()) buffer else lastPartial
            if (txt.isBlank()) {
                if (!handled) {
                    handled = true
                    resumeWake()
                }
            } else {
                gotText(txt)
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
        buffer = ""
        handler.removeCallbacks(finalizeRunnable)
        try {
            val rec = Recognizer(trModel!!, 16000.0f)
            cmdSpeech = SpeechService(rec, 16000.0f)
            cmdSpeech?.startListening(cmdListener, 9000)
            showNotification("Dinliyorum, komutu söyle...")
        } catch (e: Throwable) {
            showNotification("Hata: ${e.message}")
            resumeWake()
        }
    }

    private fun stopCmd() {
        handler.removeCallbacks(finalizeRunnable)
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
        val f = followUp
        followUp = null
        val reply = if (f != null) f(low) else execute(low)
        say(reply, "reply")
    }

    private fun resumeWake() {
        followUp = null
        stopCmd()
        stopWake()
        startWake()
    }

    private fun norm(s: String): String {
        return s.lowercase(trLocale)
            .replace('ı', 'i').replace('ö', 'o').replace('ü', 'u')
            .replace('ş', 's').replace('ç', 'c').replace('ğ', 'g')
            .replace('â', 'a').replace('î', 'i').replace('û', 'u')
    }

    private fun execute(low: String): String {
        val nt = norm(low)
        val words = nt.split(" ").filter { it.isNotBlank() }
        val raw = low.split(" ").filter { it.isNotBlank() }
        val openWords = setOf("ac", "baslat", "calistir", "git")
        val callWords = setOf("ara", "arar", "arayin", "arasana")
        val isOpen = words.any { it in openWords }
        return when {
            nt.contains("alarm") -> setAlarm(nt, words)
            nt.contains("sayac") || nt.contains("zamanlayici") || nt.contains("geri sayim") -> setTimer(nt, words)
            words.any { it in callWords } -> callName(words)
            words.any { it == "mesaj" || it == "sms" } -> prepareSms(words, raw)
            nt.contains("muzi") || nt.contains("sarki") -> musicControl(nt, words)
            nt.contains("fener") -> setTorch(!(nt.contains("kapat") || nt.contains("sondur")))
            nt.contains("sessiz") || nt.contains("titresim") ->
                ringer(AudioManager.RINGER_MODE_VIBRATE, "Telefon titreşime alındı")
            nt.contains("zil") && (isOpen || nt.contains("normal")) ->
                ringer(AudioManager.RINGER_MODE_NORMAL, "Zil sesi açıldı")
            words.any { it == "ses" || it == "sesi" } &&
                (nt.contains("artir") || nt.contains("yuksel") || nt.contains("yukselt") || isOpen) -> volume(true)
            words.any { it == "ses" || it == "sesi" } &&
                (nt.contains("azalt") || nt.contains("kis") || nt.contains("dusur")) -> volume(false)
            nt.contains("saat") && !isOpen -> {
                val c = Calendar.getInstance()
                "Saat " + String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            nt.contains("tarih") || nt.contains("bugun") ->
                SimpleDateFormat("d MMMM EEEE", trLocale).format(Date())
            nt.contains("pil") || nt.contains("sarj") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                "Pil yüzde " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            isOpen -> {
                val rest = words.filter { it !in openWords }
                if (rest.isEmpty()) {
                    followUp = { a -> execute("$a aç") }
                    "Neyi açayım?"
                } else {
                    openAppReply(nt)
                }
            }
            nt.contains("merhaba") || nt.contains("selam") -> "Merhaba, size nasıl yardımcı olabilirim"
            else -> "Anlayamadım. Şunu duydum: $low"
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
            if (w.startsWith("bucuk")) {
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

    private fun setAlarm(nt: String, words: List<String>): String {
        val n = numbersIn(words)
        if (n.isEmpty()) return "Saati anlayamadım"
        var hour = n[0]
        val minute = if (n.size > 1) n[1] else 0
        if ((nt.contains("aksam") || nt.contains("ogleden sonra")) && hour < 12) hour += 12
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

    private fun setTimer(nt: String, words: List<String>): String {
        val n = numbersIn(words)
        if (n.isEmpty()) return "Süreyi anlayamadım"
        val sec = when {
            nt.contains("saniye") -> n[0]
            nt.contains("saat") -> n[0] * 3600
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

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun hasContactsPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun findContact(nameWords: List<String>): Pair<String, String>? {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        var best: Pair<String, String>? = null
        var bestScore = 99
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val parts = norm(name).split(" ").filter { p -> p.length >= 2 }
                for (p in parts) {
                    for (w in nameWords) {
                        val nw = norm(w)
                        if (nw.length < 2) continue
                        val d = lev(nw, p)
                        val allowed = if (p.length <= 4) 1 else 2
                        val s = when {
                            nw == p -> 0
                            nw.startsWith(p) && p.length >= 3 -> 1
                            p.startsWith(nw) && nw.length >= 3 -> 1
                            d <= allowed -> 2 + d
                            else -> 99
                        }
                        if (s < bestScore) {
                            bestScore = s
                            best = Pair(name, number)
                        }
                    }
                }
            }
        }
        return best
    }

    private fun callName(words: List<String>): String {
        val callWords = setOf("ara", "arar", "arayin", "arasana")
        val idx = words.indexOfFirst { it in callWords }
        val nameWords = if (idx > 0) words.take(idx) else words.drop(idx + 1)
        if (nameWords.isEmpty()) return "Kimi arayacağımı anlayamadım"
        if (!hasContactsPermission()) return "Rehber izni verilmemiş. Uygulamadan izinleri ver"
        val c = findContact(nameWords)
            ?: return "Rehberde " + nameWords.joinToString(" ") + " adında kimse bulamadım"
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Arama izni verilmemiş"
        }
        return try {
            launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.second))))
            c.first + " aranıyor"
        } catch (e: Throwable) {
            "Arayamadım"
        }
    }

    private fun prepareSms(words: List<String>, raw: List<String>): String {
        val idx = words.indexOfFirst { it == "mesaj" || it == "sms" }
        if (idx <= 0) return "Kime mesaj göndereceğimi anlayamadım"
        val verbs = setOf("at", "gonder", "yaz", "yolla")
        var start = idx + 1
        while (start < words.size && words[start] in verbs) start++
        val body = raw.drop(start).joinToString(" ")
        if (body.isBlank()) return "Mesajın ne olacağını anlayamadım"
        if (!hasContactsPermission()) return "Rehber izni verilmemiş. Uygulamadan izinleri ver"
        val nameWords = words.take(idx)
        val c = findContact(nameWords)
            ?: return "Rehberde " + nameWords.joinToString(" ") + " adında kimse bulamadım"
        followUp = { a ->
            val x = norm(a)
            if (x.contains("evet") || x.contains("gonder") || x.contains("tamam") || x.contains("olur")) {
                sendSms(c.second, body)
            } else {
                "Vazgeçtim"
            }
        }
        return c.first + " kişisine şu mesajı göndereyim mi: " + body
    }

    private fun sendSms(number: String, body: String): String {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "Mesaj izni verilmemiş"
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

    private fun mediaKey(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun musicControl(nt: String, words: List<String>): String {
        if (nt.contains("durdur") || nt.contains("duraklat") || nt.contains("kapat") ||
            nt.contains("sustur") || words.contains("dur")
        ) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            return "Müzik durduruldu"
        }
        if (nt.contains("sonraki") || nt.contains("ileri") || nt.contains("gec")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            return "Sonraki şarkı"
        }
        if (nt.contains("onceki") || nt.contains("geri")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            return "Önceki şarkı"
        }
        return try {
            val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            launch(i)
            handler.postDelayed({ mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 2500)
            "Müzik açılıyor"
        } catch (e: Throwable) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            "Müzik başlatıldı"
        }
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

    private fun openAppReply(nt: String): String {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        var target: String? = null
        for ((k, v) in aliases) {
            if (nt.contains(k)) {
                target = v
                break
            }
        }
        for (a in apps) {
            val l = norm(a.loadLabel(pm).toString())
            val hit = if (target != null) l.contains(target) else (l.length >= 3 && nt.contains(l))
            if (hit) {
                val i = pm.getLaunchIntentForPackage(a.activityInfo.packageName)
                if (i != null) {
                    return try {
                        launch(i)
                        l + " açılıyor"
                    } catch (e: Throwable) {
                        "Açamadım"
                    }
                }
            }
        }
        return "Uygulamayı bulamadım"
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
