package com.jarvis.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val PICK_MODEL = 2
        private const val REQ_MIC = 10
        const val EXTRA_LISTEN = "listen"
    }

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var pageReady = false
    private var listenWhenReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#05080f")
        window.navigationBarColor = Color.parseColor("#05080f")
        Jarvis.init(this)

        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(Color.parseColor("#05080f"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "J")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                if (listenWhenReady) { listenWhenReady = false; listen() }
            }
        }
        Jarvis.listener = { ev -> runOnUiThread { js("onJ(" + JSONObject.quote(ev.toString()) + ")") } }
        web.loadUrl("file:///android_asset/www/index.html")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    /** Tile, headset button or "assist" gesture: start listening right away. */
    private fun handleIntent(i: Intent?) {
        val wantListen = i?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND
        if (!wantListen) return
        if (pageReady) listen() else listenWhenReady = true
    }

    override fun onDestroy() {
        recognizer?.destroy(); recognizer = null
        Jarvis.listener = null
        super.onDestroy()
    }

    private fun js(code: String) { if (pageReady) web.evaluateJavascript(code, null) }

    private fun event(type: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("type", type); for ((k, v) in kv) o.put(k, v)
        js("onJ(" + JSONObject.quote(o.toString()) + ")")
    }

    // ---------- voice ----------

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            event("error", "text" to "На телефоне нет распознавания речи. Поставь «Google» или пиши текстом."); return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listenerObj) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // offline if the Russian voice pack is installed
        recognizer?.startListening(i)
        event("listening", "on" to true)
    }

    private val listenerObj = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { event("rms", "v" to rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { event("listening", "on" to false) }
        override fun onError(error: Int) {
            event("listening", "on" to false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания. Скачай русский офлайн-пакет: Настройки Google → Голосовой ввод."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону."
                else -> "Ошибка распознавания ($error)."
            }
            event("hint", "text" to msg)
        }
        override fun onResults(results: Bundle?) {
            event("listening", "on" to false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            Jarvis.handle(text)
        }
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { event("partial", "text" to it) }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        event("perms")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_MODEL && resultCode == RESULT_OK && uri != null) Jarvis.models.import(contentResolver, uri)
    }

    override fun onResume() { super.onResume(); event("perms") }

    // ---------- page bridge ----------

    private fun perms(): JSONObject {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return JSONObject()
            .put("access", JarvisAccess.enabled)
            .put("mic", has(Manifest.permission.RECORD_AUDIO))
            .put("contacts", has(Manifest.permission.READ_CONTACTS))
            .put("dnd", nm.isNotificationPolicyAccessGranted)
            .put("write", Settings.System.canWrite(this))
            .put("notif", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
    }

    inner class Bridge {
        @JavascriptInterface fun send(text: String) = Jarvis.handle(text)
        @JavascriptInterface fun hands(text: String) = Jarvis.handle(text, handsMode = true)
        @JavascriptInterface fun listen() = runOnUiThread { this@MainActivity.listen() }
        @JavascriptInterface fun stopListen() = runOnUiThread { recognizer?.stopListening() }
        @JavascriptInterface fun stop() = Jarvis.stop()
        @JavascriptInterface fun busy() = Jarvis.busy

        @JavascriptInterface fun commands(): String = Jarvis.store.toJson().toString()
        @JavascriptInterface fun saveCommand(json: String): String = runCatching {
            val c = UserCommand.of(JSONObject(json))
            if (c.name.isBlank()) return "нужно имя"
            if (c.steps.isEmpty()) return "нужен хотя бы один шаг"
            Jarvis.store.put(c); ""
        }.getOrElse { "ошибка: ${it.message}" }
        @JavascriptInterface fun deleteCommand(name: String) = Jarvis.store.remove(name)
        @JavascriptInterface fun runCommand(name: String) = Jarvis.runCommand(name)
        @JavascriptInterface fun confirm(json: String) = Jarvis.confirm(json)
        @JavascriptInterface fun discard() = Jarvis.discard()
        @JavascriptInterface fun actions(): String = org.json.JSONArray(Brain.ACTIONS.map {
            JSONObject().put("name", it.name).put("arg", it.arg).put("about", it.about) }).toString()
        @JavascriptInterface fun notes(): String = Jarvis.actions.notesJson().toString()

        @JavascriptInterface fun prefs(): String = Jarvis.prefs.toJson().toString()
        @JavascriptInterface fun setPref(key: String, value: String) = Jarvis.prefs.set(key, value)
        @JavascriptInterface fun models(): String = Jarvis.models.state().toString()
        @JavascriptInterface fun download(id: String): String = Jarvis.models.download(id) ?: ""
        @JavascriptInterface fun cancelDownload() = Jarvis.models.cancel()
        @JavascriptInterface fun deleteModel(path: String) = Jarvis.models.delete(path)
        @JavascriptInterface fun useModel(path: String) { LlamaBridge.unload(); Jarvis.prefs.set("localModel", path); Jarvis.prefs.set("backend", "local") }
        @JavascriptInterface fun pickModel() = runOnUiThread {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_MODEL)
        }

        @JavascriptInterface fun perms(): String = this@MainActivity.perms().toString()
        @JavascriptInterface fun perm(name: String) = runOnUiThread {
            when (name) {
                "access" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                "restricted" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                "mic" -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                "contacts" -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 11)
                "notif" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
                "dnd" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "write" -> startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }
}

/** Quick Settings tile: pull down the shade, tap «Джарвис», speak. */
class JarvisTile : TileService() {
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
