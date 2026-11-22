package com.jarvis.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
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
    private var spkModel: SpeakerModel? = null
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
    private var savedVolume = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var friendMode = false
    private var enrolling = false
    private val enrollVecs = mutableListOf<DoubleArray>()
    private var lastSpk: DoubleArray? = null
    private var lastFrames = 0

    private val aiMark = "@@AI@@"

    private val sysAssistant =
        "Sen Jarvis'sin, kullanıcının telefondaki sesli asistanısın. Kısa, net, doğal Türkçe konuş. " +
            "Cevapların sesli okunacak, bu yüzden madde işareti, markdown ve emoji kullanma. " +
            "Normalde en fazla 3-4 cümle yaz. Kullanıcı makale, rapor, plan, program gibi uzun bir şey isterse uzun yaz. " +
            "Sağlık sorularında genel bilgi ver, teşhis koyma, ciddi belirtilerde doktora veya 112'ye yönlendir. " +
            "Spor ve borsa konularında olasılıklarla konuş, kesin sonuç veya garanti verme, yatırım tavsiyesi verme. " +
            "Bilmediğin şeyi uydurma."

    private val sysFriend =
        "Sen Jarvis'sin ve kullanıcının sohbet arkadaşısın. Sıcak, samimi, dinleyen biri gibi 1-3 cümleyle konuş, arada soru sor. " +
            "Cevapların sesli okunacak, markdown ve emoji kullanma. " +
            "Kullanıcı kendine zarar vermekten veya yaşamak istemediğinden söz ederse sakin ve şefkatli ol, yalnız olmadığını söyle " +
            "ve 112'yi ya da güvendiği birini aramasını öner. Sağlık konusunda teşhis koyma."

    private val finalizeRunnable = Runnable { gotText(buffer) }

    private val nums = mapOf(
        "sifir" to 0, "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5,
        "alti" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10,
        "yirmi" to 20, "otuz" to 30, "kirk" to 40, "elli" to 50,
        "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90, "yuz" to 100
    )
    private val sfx = listOf("", "da", "de", "ta", "te", "a", "e", "ya", "ye")

    private val appAliases = mapOf(
        "votsap" to "whatsapp", "vatsap" to "whatsapp", "vatsapp" to "whatsapp",
        "whatsap" to "whatsapp", "yutub" to "youtube", "yutup" to "youtube",
        "insta" to "instagram", "feysbuk" to "facebook", "fesbuk" to "facebook",
        "cimeyl" to "gmail", "jimeyl" to "gmail", "krom" to "chrome",
        "tarayici" to "chrome", "internet" to "chrome", "harita" to "haritalar",
        "rehber" to "kisiler", "ayar" to "ayarlar", "hesap" to "hesapmakinesi",
        "makine" to "hesapmakinesi", "fotograf" to "galeri"
    )

    private val keywords = listOf(
        "alarm", "sayac", "fener", "muzik", "saat", "tarih", "sarj", "mesaj",
        "rota", "hoparlor", "sessiz", "whatsapp", "youtube", "kamera", "ayarlar"
    )

    private val localKeys = listOf(
        "alarm", "sayac", "zamanlayici", "geri sayim", "mesaj", "sms", "hoparlor", "rota",
        "yol tarifi", "navigasyon", "muzi", "sarki", "fener", "sessiz", "titresim", "zil",
        "saat", "pil", "sarj", "tarih", "merhaba", "selam"
    )

    private fun prefs(): SharedPreferences = getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification("Hazırlanıyor...")
        val wantFriend = intent?.getBooleanExtra("friend", false) == true
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake")
                wakeLock?.acquire()
            } catch (e: Throwable) {
            }
        }
        if (tts == null) initTts()
        if (wantFriend) {
            if (enModel != null && trModel != null) {
                handler.post { enterFriend() }
            } else {
                friendMode = true
            }
        }
        if (enModel == null && !loading) {
            loading = true
            Thread {
                try {
                    val en = Model(prepare("model").absolutePath)
                    val tr = Model(prepare("model-tr").absolutePath)
                    enModel = en
                    trModel = tr
                    try {
                        spkModel = SpeakerModel(prepare("model-spk").absolutePath)
                    } catch (e: Throwable) {
                        spkModel = null
                    }
                    handler.post { if (friendMode) enterFriend() else startWake() }
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
        if (id == "wake" || id == "friend" || followUp != null || friendMode) startCommand() else resumeWake()
    }

    private fun enterFriend() {
        friendMode = true
        followUp = null
        Ai.clear()
        busy = true
        stopCmd()
        stopWake()
        say("Merhaba, seni dinliyorum. Nasılsın?", "friend")
    }

    private val wakeListener = object : RecognitionListener {
        override fun onPartialResult(h: String?) { checkWake(h, true) }
        override fun onResult(h: String?) { checkWake(h, false) }
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
                handler.postDelayed(finalizeRunnable, 2500)
                readSpeaker(h)
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
                    if (friendMode) {
                        friendMode = false
                        Ai.clear()
                        stopCmd()
                        say("Görüşürüz, ihtiyacın olursa Jarvis de", "reply")
                    } else {
                        resumeWake()
                    }
                }
            } else {
                gotText(txt)
            }
        }
    }

    private fun readSpeaker(h: String?) {
        try {
            val o = JSONObject(h ?: "")
            val arr = o.optJSONArray("spk") ?: return
            val fr = o.optInt("spk_frames", 0)
            if (fr >= lastFrames) {
                lastSpk = DoubleArray(arr.length()) { arr.getDouble(it) }
                lastFrames = fr
            }
        } catch (e: Throwable) {
        }
    }

    private fun startWake() {
        try {
            val grammar = "[\"jarvis\", \"travis\", \"service\", \"jarvas\", \"davis\", " +
                "\"the\", \"you\", \"me\", \"my\", \"is\", \"it\", \"oh\", \"no\", \"go\", \"[unk]\"]"
            val rec = Recognizer(enModel!!, 16000.0f, grammar)
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

    private fun isWake(text: String): Boolean {
        val tokens = text.trim().split(" ").filter { it.isNotBlank() }
        if (tokens.count { it == "jarvis" } != 1) return false
        if (tokens.size > 2) return false
        return tokens.all { it == "jarvis" || it == "[unk]" }
    }

    private fun checkWake(h: String?, partial: Boolean) {
        if (busy) return
        val t = if (partial) field(h, "partial") else field(h, "text")
        if (!isWake(t)) return
        busy = true
        duck()
        stopWake()
        say("Buyurun efendim", "wake")
    }

    private fun startCommand() {
        handled = false
        lastPartial = ""
        buffer = ""
        lastSpk = null
        lastFrames = 0
        handler.removeCallbacks(finalizeRunnable)
        try {
            val rec = Recognizer(trModel!!, 16000.0f)
            val sm = spkModel
            if (sm != null) rec.setSpeakerModel(sm)
            cmdSpeech = SpeechService(rec, 16000.0f)
            cmdSpeech?.startListening(cmdListener, 20000)
            showNotification(if (friendMode) "Seni dinliyorum..." else "Dinliyorum, komutu söyle...")
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

    private fun cosDist(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 1.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 1.0
        return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun loadRef(): DoubleArray? {
        val s = prefs().getString("spk_ref", null) ?: return null
        return try {
            s.split(",").map { it.toDouble() }.toDoubleArray()
        } catch (e: Throwable) {
            null
        }
    }

    private fun speakerDistance(): Double {
        val ref = loadRef() ?: return -1.0
        val v = lastSpk ?: return -1.0
        if (lastFrames < 150) return -1.0
        return cosDist(ref, v)
    }

    private fun speakerAllowed(d: Double): Boolean {
        if (d < 0) return true
        if (!prefs().getBoolean("spk_on", true)) return true
        return d <= prefs().getFloat("spk_thr", 0.6f)
    }

    private fun gotText(text: String) {
        if (handled) return
        if (text.isBlank()) return
        handled = true
        stopCmd()
        val low = text.lowercase(trLocale)
        val dist = speakerDistance()
        val distText = if (dist >= 0) " (ses mesafesi " + String.format(Locale.US, "%.2f", dist) + ")" else ""
        showNotification("Duyduğum: $text$distText")
        if (!enrolling && !speakerAllowed(dist)) {
            followUp = null
            say("Sesinizi tanıyamadım", "reply")
            return
        }
        if (friendMode) {
            val x = norm(low)
            if (x.contains("gorusuruz") || x.contains("hosca kal") || x.contains("konusmayi bitir") ||
                x.contains("tamam bu kadar") || x.contains("sohbeti bitir")
            ) {
                friendMode = false
                followUp = null
                Ai.clear()
                say("Görüşürüz, kendine iyi bak", "reply")
                return
            }
            askAi(low, true)
            return
        }
        val f = followUp
        followUp = null
        val reply = try {
            if (f != null) f(low) else runAll(low)
        } catch (e: Throwable) {
            "Bir hata oldu"
        }
        if (reply == aiMark) return
        say(reply, "reply")
    }

    private fun resumeWake() {
        followUp = null
        enrolling = false
        enrollVecs.clear()
        stopCmd()
        stopWake()
        unduck()
        startWake()
    }

    private fun hasKey(): Boolean {
        return (prefs().getString("api_key", "") ?: "").isNotBlank()
    }

    private fun needsSearch(nt: String): Boolean {
        val keys = listOf(
            "arastir", "haber", "guncel", "mac", "borsa", "dolar", "euro", "altin",
            "son dakika", "fiyat", "skor", "kimdir", "puan durumu", "hava durumu"
        )
        return keys.any { nt.contains(it) }
    }

    private fun isLong(nt: String): Boolean {
        val keys = listOf("makale", "rapor", "ders programi", "calisma programi", "plan", "analiz", "yaz", "ozet", "hikaye")
        return keys.any { nt.contains(it) }
    }

    private fun askAi(text: String, friend: Boolean) {
        val key = prefs().getString("api_key", "") ?: ""
        if (key.isBlank()) {
            say("Yapay zeka için anahtar girilmemiş. Uygulamadan anahtarı gir", "reply")
            return
        }
        val nt = norm(text)
        val date = SimpleDateFormat("d MMMM yyyy EEEE HH:mm", trLocale).format(Date())
        val sys = (if (friend) sysFriend else sysAssistant) + " Bugünün tarihi ve saati: " + date + "."
        val search = !friend && needsSearch(nt)
        val max = if (!friend && isLong(nt)) 1500 else 400
        showNotification("Düşünüyorum...")
        Thread {
            val ans = try {
                Ai.ask(key, text, sys, search, max)
            } catch (e: ApiError) {
                if (e.code == 401) {
                    "Yapay zeka anahtarı geçersiz görünüyor. Uygulamadan anahtarı kontrol et"
                } else {
                    "Yapay zeka hatası. " + (e.message ?: "")
                }
            } catch (e: Throwable) {
                "Yapay zekaya ulaşamadım. İnternet bağlantısını kontrol edin"
            }
            handler.post { deliver(ans) }
        }.start()
    }

    private fun deliver(ans: String) {
        prefs().edit().putString("last_answer", ans).apply()
        var s = ans.replace(Regex("[*#_`>]"), "").replace(Regex("\\s+"), " ").trim()
        if (s.isBlank()) s = "Cevap alamadım"
        if (s.length > 600) {
            val cut = s.lastIndexOf('.', 600)
            s = s.substring(0, if (cut > 200) cut + 1 else 600) +
                " Devamını uygulamadaki son cevap bölümünde okuyabilirsiniz."
        }
        showNotification("Cevap hazır")
        say(s, "reply")
    }

    private fun audio(): AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun duck() {
        val am = audio()
        if (savedVolume < 0) {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val low = maxOf(2, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3)
            if (cur > low) {
                savedVolume = cur
                am.setStreamVolume(AudioManager.STREAM_MUSIC, low, 0)
            }
        }
    }

    private fun unduck() {
        if (savedVolume >= 0) {
            audio().setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            savedVolume = -1
        }
    }

    private fun setLevel(level: Int) {
        if (savedVolume >= 0) {
            savedVolume = level
        } else {
            audio().setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        }
    }

    private fun percentReply(p: Int): String {
        val max = audio().getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        setLevel(((max * p + 50) / 100).coerceIn(0, max))
        return "Ses yüzde $p"
    }

    private fun volumeFromSpeech(nt: String, words: List<String>): String? {
        if (nt.contains("full") || nt.contains("maksimum") || nt.contains("sonuna") || nt.contains("en yuksek")) {
            return percentReply(100)
        }
        if (nt.contains("yarim") || nt.contains("yari")) return percentReply(50)
        val n = numbersIn(words)
        if (n.isNotEmpty()) return percentReply(n[0].coerceIn(0, 100))
        return null
    }

    private fun volume(up: Boolean): String {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val base = if (savedVolume >= 0) savedVolume else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 5)
        setLevel((base + if (up) step else -step).coerceIn(0, max))
        return if (up) "Ses artırıldı" else "Ses azaltıldı"
    }

    private fun norm(s: String): String {
        return s.lowercase(trLocale)
            .replace('ı', 'i').replace('ö', 'o').replace('ü', 'u')
            .replace('ş', 's').replace('ç', 'c').replace('ğ', 'g')
            .replace('â', 'a').replace('î', 'i').replace('û', 'u')
    }

    private fun yes(a: String): Boolean {
        val x = norm(a)
        return x.contains("evet") || x.contains("tamam") || x.contains("olur") ||
            x.contains("aynen") || x.contains("dogru")
    }

    private fun splitCommands(t: String): List<String> {
        if (t.contains("mesaj") || t.contains("sms")) return listOf(t)
        return t.split(Regex("\\s+(ve|sonra|ardindan|ayrica)\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun isLocal(p: String): Boolean {
        val words = p.split(" ").filter { it.isNotBlank() }
        val tokens = setOf("ara", "arar", "arayin", "arasana", "ac", "baslat", "calistir", "ses", "sesi", "cal", "oynat")
        if (words.any { it in tokens }) return true
        return localKeys.any { p.contains(it) }
    }

    private fun runAll(low: String): String {
        val parts = splitCommands(norm(low))
        if (parts.size <= 1 || !parts.all { isLocal(it) }) return execute(low)
        val replies = mutableListOf<String>()
        for (p in parts) {
            val r = execute(p)
            if (r == aiMark) return aiMark
            replies.add(r)
            if (followUp != null) break
        }
        return replies.joinToString(". ")
    }

    private fun execute(low: String): String {
        val nt = norm(low)
        val words = nt.split(" ").filter { it.isNotBlank() }
        val raw = low.split(" ").filter { it.isNotBlank() }
        val openWords = setOf("ac", "baslat", "calistir")
        val callWords = setOf("ara", "arar", "arayin", "arasana")
        val isOpen = words.any { it in openWords }
        val hasVol = words.any { it == "ses" || it == "sesi" }
        val isMusic = nt.contains("muzi") || nt.contains("sarki") || words.any { it == "cal" || it == "oynat" }
        return when {
            nt.contains("sesimi") && (nt.contains("kaydet") || nt.contains("tani") || nt.contains("ogren")) -> startEnroll()
            nt.contains("ses kaydini sil") || nt.contains("sesimi unut") -> {
                prefs().edit().remove("spk_ref").apply()
                "Ses kaydı silindi"
            }
            nt.contains("ses kilid") || nt.contains("ses tanima") -> {
                val on = !nt.contains("kapat")
                prefs().edit().putBoolean("spk_on", on).apply()
                if (on) "Ses kilidi açıldı" else "Ses kilidi kapatıldı"
            }
            nt.contains("hassasiyet") -> {
                var t = prefs().getFloat("spk_thr", 0.6f)
                if (nt.contains("artir") || nt.contains("yukselt")) t -= 0.05f
                if (nt.contains("azalt") || nt.contains("dusur")) t += 0.05f
                t = t.coerceIn(0.2f, 0.9f)
                prefs().edit().putFloat("spk_thr", t).apply()
                "Ses hassasiyeti ayarlandı, eşik " + String.format(Locale.US, "%.2f", t)
            }
            nt.contains("alarm") -> setAlarm(nt, words)
            nt.contains("sayac") || nt.contains("zamanlayici") || nt.contains("geri sayim") -> setTimer(nt, words)
            words.any { it in callWords } -> callName(words)
            words.any { it == "mesaj" || it == "sms" } -> prepareSms(words, raw)
            nt.contains("hoparlor") -> speaker(!nt.contains("kapat"))
            nt.contains("rota") || nt.contains("yol tarifi") || nt.contains("navigasyon") ||
                words.any { it == "gotur" } -> routeCmd(words)
            isMusic -> musicRouter(nt, words, raw)
            nt.contains("fener") -> setTorch(!(nt.contains("kapat") || nt.contains("sondur")))
            nt.contains("sessiz") || nt.contains("titresim") ->
                ringer(AudioManager.RINGER_MODE_VIBRATE, "Telefon titreşime alındı")
            nt.contains("zil") && (isOpen || nt.contains("normal")) ->
                ringer(AudioManager.RINGER_MODE_NORMAL, "Zil sesi açıldı")
            hasVol && volumeFromSpeech(nt, words) != null -> volumeFromSpeech(nt, words) ?: "Ses ayarlandı"
            hasVol && (nt.contains("artir") || nt.contains("yuksel") || isOpen) -> volume(true)
            hasVol && (nt.contains("azalt") || nt.contains("kis") || nt.contains("dusur")) -> volume(false)
            nt.contains("saat") && !isOpen && !nt.contains("saatte") -> {
                val c = Calendar.getInstance()
                "Saat " + String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            nt.contains("tarih") || nt.contains("ayin kaci") || nt.contains("hangi gun") ->
                SimpleDateFormat("d MMMM EEEE", trLocale).format(Date())
            nt.contains("pil") || nt.contains("sarj") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                "Pil yüzde " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            isOpen -> {
                val fillers = setOf("uygulama", "uygulamayi", "uygulamasini", "lutfen", "bana", "bir", "su", "sunu", "programi")
                val rest = words.filter { it !in openWords && it !in fillers }
                if (rest.isEmpty()) {
                    followUp = { a -> execute(norm(a) + " ac") }
                    "Neyi açayım?"
                } else {
                    openApp(rest)
                }
            }
            nt.contains("merhaba") || nt.contains("selam") -> "Merhaba, size nasıl yardımcı olabilirim"
            else -> {
                if (hasKey()) {
                    askAi(low, false)
                    aiMark
                } else {
                    suggest(nt, words) ?: ("Tam anlayamadım. Şunu duydum: " + nt)
                }
            }
        }
    }

    private fun startEnroll(): String {
        if (spkModel == null) return "Ses tanıma modeli yüklenemedi"
        enrolling = true
        enrollVecs.clear()
        followUp = { a -> enrollStep(a) }
        return "Sesinizi tanımam için üç uzun cümle söyleyin. Birinci cümle. " +
            "Bugün hava çok güzel ve ben telefonumu sadece sesimle kontrol etmek istiyorum"
    }

    private fun enrollStep(a: String): String {
        val v = lastSpk
        if (v == null || lastFrames < 250) {
            followUp = { x -> enrollStep(x) }
            return "Sesiniz yeterince uzun alınamadı. Biraz daha uzun bir cümle söyleyin"
        }
        enrollVecs.add(v)
        if (enrollVecs.size < 3) {
            followUp = { x -> enrollStep(x) }
            return if (enrollVecs.size == 1) {
                "İkinci cümle. Bu akşam ne yapacağımı henüz bilmiyorum ama yarın erken kalkmam gerekiyor"
            } else {
                "Son cümle. Müzik dinlemeyi, yeni yerler keşfetmeyi ve arkadaşlarımla vakit geçirmeyi çok severim"
            }
        }
        val n = v.size
        val avg = DoubleArray(n)
        for (e in enrollVecs) {
            if (e.size == n) {
                for (i in 0 until n) avg[i] += e[i] / enrollVecs.size
            }
        }
        prefs().edit().putString("spk_ref", avg.joinToString(",")).putBoolean("spk_on", true).apply()
        enrolling = false
        enrollVecs.clear()
        return "Sesiniz kaydedildi. Artık komutları sadece sizden kabul edeceğim"
    }

    private fun suggest(nt: String, words: List<String>): String? {
        for (w in words) {
            if (w.length < 3) continue
            for (k in keywords) {
                val d = lev(w, k)
                if (d in 1..2 && d <= k.length / 2) {
                    val fixed = nt.replace(w, k)
                    followUp = { a -> if (yes(a)) execute(fixed) else "Peki" }
                    return "$fixed mi demek istediniz"
                }
            }
        }
        return null
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

    private fun speaker(on: Boolean): String {
        val am = audio()
        val apply = Runnable {
            try {
                am.isSpeakerphoneOn = on
            } catch (e: Throwable) {
            }
        }
        handler.postDelayed(apply, 3000)
        handler.postDelayed(apply, 7000)
        return if (on) "Hoparlör açılıyor" else "Hoparlör kapatılıyor"
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
            when {
                x.contains("whatsapp") || x.contains("votsap") || x.contains("vatsap") -> sendWhatsApp(c.second, body)
                x.contains("mesaj") || x.contains("sms") -> sendSms(c.second, body)
                else -> "Vazgeçtim"
            }
        }
        return c.first + " kişisine şu mesajı göndereyim mi: " + body +
            ". WhatsApp'tan mı, normal mesajdan mı? Vazgeçmek için iptal de"
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

    private fun sendWhatsApp(number: String, body: String): String {
        val d = number.filter { it.isDigit() }
        val full = if (number.startsWith("+")) d else if (d.startsWith("0")) "90" + d.drop(1) else d
        val uri = Uri.parse("https://wa.me/" + full + "?text=" + Uri.encode(body))
        return try {
            val i = Intent(Intent.ACTION_VIEW, uri)
            i.setPackage("com.whatsapp")
            launch(i)
            "WhatsApp açıldı, mesaj yazıldı. Göndermek için gönder tuşuna basmanız gerekiyor"
        } catch (e: Throwable) {
            try {
                launch(Intent(Intent.ACTION_VIEW, uri))
                "WhatsApp açıldı, mesaj yazıldı. Göndermek için gönder tuşuna basmanız gerekiyor"
            } catch (e2: Throwable) {
                "WhatsApp'ı açamadım"
            }
        }
    }

    private fun routeCmd(words: List<String>): String {
        val stop = setOf(
            "rota", "olustur", "cizer", "cizin", "yol", "tarifi", "git", "gotur",
            "navigasyon", "icin", "bana", "bir", "cikar", "ac", "baslat", "lutfen", "bul", "hemen"
        )
        val dest = words.filter { it !in stop }.joinToString(" ")
        if (dest.isBlank()) {
            followUp = { a -> routeTo(norm(a)) }
            return "Nereye gitmek istiyorsun?"
        }
        return routeTo(dest)
    }

    private fun routeTo(dest: String): String {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
            i.setPackage("com.google.android.apps.maps")
            launch(i)
            "$dest için rota Haritalar'da açıldı. Trafik ve alternatif yollar haritada görünür"
        } catch (e: Throwable) {
            try {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))
                "$dest haritada açıldı"
            } catch (e2: Throwable) {
                "Harita uygulamasını açamadım"
            }
        }
    }

    private fun mediaKey(code: Int) {
        val am = audio()
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
    }

    private fun stopMusic() {
        mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        try {
            val am = audio()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { _ -> }
                .build()
            am.requestAudioFocus(req)
            handler.postDelayed({ am.abandonAudioFocusRequest(req) }, 2000)
        } catch (e: Throwable) {
        }
    }

    private fun musicRouter(nt: String, words: List<String>, raw: List<String>): String {
        if (nt.contains("durdur") || nt.contains("duraklat") || nt.contains("kapat") ||
            nt.contains("sustur") || words.contains("dur")
        ) {
            stopMusic()
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
        val stop = setOf(
            "muzik", "muzigi", "muzikleri", "sarki", "sarkiyi", "sarkilari", "ac", "cal",
            "oynat", "baslat", "bir", "bana", "calar", "misin", "lutfen", "dinlemek", "istiyorum"
        )
        val query = raw.filter { norm(it) !in stop }.joinToString(" ")
        if (query.isBlank()) {
            followUp = { a -> musicPlay(a) }
            return "Hangi müziği açayım?"
        }
        return musicPlay(query)
    }

    private fun musicPlay(query: String): String {
        val q = query.trim()
        val nq = norm(q)
        val generic = nq.contains("farketmez") || nq.contains("herhangi") || nq.contains("rastgele") ||
            nq.contains("listem") || nq.contains("favori")
        try {
            if (generic) {
                launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
                handler.postDelayed({ mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 2500)
            } else {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                i.putExtra(SearchManager.QUERY, q)
                launch(i)
            }
        } catch (e: Throwable) {
            return "Müzik uygulaması bulamadım"
        }
        followUp = { v ->
            val x = norm(v)
            val r = volumeFromSpeech(x, x.split(" ").filter { it.isNotBlank() })
            r ?: "Ses seviyesini anlayamadım"
        }
        return "Müzik açılıyor. Ses seviyesi yüzde kaç olsun?"
    }

    private fun ringer(mode: Int, ok: String): String {
        return try {
            audio().ringerMode = mode
            ok
        } catch (e: Throwable) {
            "Zil modunu değiştiremedim"
        }
    }

    private fun openApp(rest: List<String>): String {
        val q = rest.joinToString("") { appAliases[it] ?: it }
        if (q.isBlank()) return "Hangi uygulamayı açacağımı anlayamadım"
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        var bestPkg: String? = null
        var bestLabel = ""
        var bestScore = 9.0
        var bestLen = 999
        for (a in apps) {
            val label = norm(a.loadLabel(pm).toString()).replace(" ", "")
            val pkg = a.activityInfo.packageName.lowercase()
            if (label.isEmpty()) continue
            val exact = label.contains(q) || (label.length >= 3 && q.contains(label)) || (q.length >= 4 && pkg.contains(q))
            val score = if (exact) 0.0 else lev(label, q).toDouble() / maxOf(label.length, q.length)
            if (score < bestScore || (score == bestScore && label.length < bestLen)) {
                bestScore = score
                bestPkg = pkg
                bestLabel = a.loadLabel(pm).toString()
                bestLen = label.length
            }
        }
        val found = bestPkg ?: return "Uygulamayı bulamadım. Şunu duydum: $q"
        if (bestScore == 0.0) return launchPackage(found, bestLabel)
        if (bestScore <= 0.4) {
            followUp = { a -> if (yes(a)) launchPackage(found, bestLabel) else "Peki" }
            return "$bestLabel uygulamasını mı açayım"
        }
        return "Uygulamayı bulamadım. Şunu duydum: $q"
    }

    private fun launchPackage(pkg: String, label: String): String {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return "Uygulamayı açamadım"
        return try {
            launch(i)
            "$label açılıyor"
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
        unduck()
        try {
            enModel?.close()
            trModel?.close()
            spkModel?.close()
        } catch (e: Throwable) {
        }
        try {
            wakeLock?.release()
        } catch (e: Throwable) {
        }
        tts?.shutdown()
        super.onDestroy()
    }
}
