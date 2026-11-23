package com.jarvis.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** The whole assistant in one place; the screen (MainActivity) only shows its events. */
object Jarvis {
    lateinit var prefs: Prefs; private set
    lateinit var store: CommandStore; private set
    lateinit var models: Models; private set
    lateinit var actions: Actions; private set
    private lateinit var brain: Brain
    private lateinit var hands: Hands
    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val worker = Executors.newSingleThreadExecutor()
    private var inited = false

    @Volatile var busy = false; private set
    @Volatile private var pending: UserCommand? = null      // a new command waiting for "да"
    var listener: ((JSONObject) -> Unit)? = null

    private const val CH = "jarvis"
    private const val NOTE_ID = 7
    const val ACTION_STOP = "com.jarvis.app.STOP"

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        LlamaBridge.init(app.applicationInfo.nativeLibraryDir)
        prefs = Prefs(File(app.filesDir, "prefs.json"))
        store = CommandStore(File(app.filesDir, "commands.json"))
        models = Models(File(app.filesDir, "models"), prefs)
        brain = Brain(store)
        actions = Actions(app, store) { say(it) }
        hands = Hands({ llm() }, actions, { prefs.handsSteps }, { prefs.backend == "local" && "0.5B" in prefs.localModel })
        initTts()
        if (Build.VERSION.SDK_INT >= 26) {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CH, "Джарвис работает", NotificationManager.IMPORTANCE_LOW))
        }
        inited = true
    }

    /** (Re)creates the speech engine with the chosen engine, voice, pitch and rate. */
    fun initTts() {
        ttsReady = false
        tts?.shutdown()
        val listener = TextToSpeech.OnInitListener { st -> if (st == TextToSpeech.SUCCESS) { applyVoice(); ttsReady = true } }
        tts = if (prefs.ttsEngine.isBlank()) TextToSpeech(app, listener) else TextToSpeech(app, listener, prefs.ttsEngine)
    }

    fun applyVoice() {
        val t = tts ?: return
        t.language = Locale("ru", "RU")
        if (prefs.voiceName.isNotBlank()) runCatching { t.voices?.firstOrNull { it.name == prefs.voiceName }?.let { t.voice = it } }
        t.setPitch(prefs.voicePitch)
        t.setSpeechRate(prefs.voiceRate)
    }

    /** Russian voices of the current engine, for the settings screen. */
    fun voicesJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        runCatching {
            tts?.voices?.filter { it.locale.language == "ru" }?.sortedBy { it.name }?.forEach {
                arr.put(JSONObject().put("name", it.name).put("net", it.isNetworkConnectionRequired)
                    .put("q", it.quality).put("male", it.name.contains("male", true) && !it.name.contains("female", true)))
            }
        }
        return arr
    }

    fun enginesJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        runCatching { tts?.engines?.forEach { arr.put(JSONObject().put("pkg", it.name).put("label", it.label)) } }
        return arr
    }

    fun testVoice() {
        if (!ttsReady) return
        applyVoice()
        tts?.speak(if (prefs.sir) "Все системы в норме, сэр. Чем могу помочь?" else "Все системы в норме. Чем могу помочь?",
            TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    fun llm(): Llm = if (prefs.backend == "ollama") OllamaLlm(prefs) else LocalLlm(prefs)

    private fun emit(type: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("type", type)
        for ((k, v) in kv) o.put(k, v)
        listener?.invoke(o)
    }

    fun say(raw: String) {
        if (raw.isBlank()) return
        // Jarvis manner: short replies end with "сэр"
        val text = if (prefs.sir && raw.length < 70 && !raw.contains("сэр", true) && raw.trimEnd().last() in ".!")
            raw.trimEnd().dropLast(1) + ", сэр" + raw.trimEnd().last() else raw
        emit("say", "text" to text)
        if (prefs.speak && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "j" + System.nanoTime())
    }

    private val HANDS = Regex("^(сам|сама|руками|сделай сам|сделай сама)[,:!. ]+", RegexOption.IGNORE_CASE)
    private val YES = setOf("да", "ага", "сохрани", "сохраняй", "ок", "окей", "давай", "подтверждаю")
    private val NO = setOf("нет", "отмена", "не надо", "не сохраняй", "отмени")

    /** One phrase from the user (typed, spoken, or from the tile). hands = forced hands mode. */
    fun handle(text: String, handsMode: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (busy) { emit("error", "text" to "Я ещё выполняю прошлую задачу. Нажми «Стоп», если надо прервать."); return }
        busy = true
        emit("user", "text" to t)
        emit("busy", "on" to true)
        worker.execute {
            try {
                val n = Brain.norm(brain.clean(t))
                val p = pending
                when {
                    p != null && n in YES -> { store.put(p); pending = null; say("Сохранил команду «${p.name}».") ; emit("commands") }
                    p != null && n in NO -> { pending = null; say("Не сохраняю.") }
                    handsMode || HANDS.containsMatchIn(brain.clean(t)) -> {
                        val goal = brain.clean(t).replace(HANDS, "").ifBlank { t }
                        notifyRunning(goal)
                        actions.stopped = false
                        say(hands.run(goal) { emit("step", "text" to it) })
                    }
                    else -> {
                        pending = null
                        val plan = brain.think(t) { prompt, grammar -> llm().gen(prompt, grammar, 220) }
                        val nc = plan.newCommand
                        if (nc != null) {
                            if (nc.steps.isEmpty()) say("Не понял, из каких действий собрать команду. Скажи, например: создай команду кино: громкость 70 и открой ютуб.")
                            else { pending = nc; emit("new_command", "cmd" to nc.toJson()); say("Сохранить команду «${nc.name}»? Скажи «да» или нажми «Сохранить».") }
                        } else {
                            if (plan.matched != null) emit("step", "text" to "⚡ своя команда «${plan.matched}»")
                            actions.stopped = false
                            if (plan.steps.size > 1) notifyRunning(t)
                            actions.runAll(plan.steps, { emit("step", "text" to it) })
                            say(plan.say)
                            if (plan.steps.isEmpty() && plan.say.isBlank()) say("Не понял. Скажи иначе, или начни со слова «сам», и я попробую сделать это на экране.")
                        }
                    }
                }
            } catch (e: Exception) {
                emit("error", "text" to (e.message ?: e.toString()))
            } finally {
                busy = false
                cancelNotice()
                emit("busy", "on" to false)
            }
        }
    }

    /** The new command shown on screen was confirmed (maybe edited) by the button. */
    fun confirm(json: String?) {
        val c = if (json.isNullOrBlank()) pending else runCatching { UserCommand.of(JSONObject(json)) }.getOrNull()
        pending = null
        if (c == null || c.name.isBlank()) return
        store.put(c); say("Сохранил команду «${c.name}»."); emit("commands")
    }

    fun discard() { pending = null }

    fun runCommand(name: String) = handle(name)

    fun stop() {
        actions.stopped = true; hands.stopped = true
        LlamaBridge.stop()
        tts?.stop()
    }

    // a notification with a Stop button: in hands mode Jarvis works inside other apps
    private fun notifyRunning(what: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val stop = PendingIntent.getBroadcast(app, 1, Intent(app, StopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(app, CH) else @Suppress("DEPRECATION") android.app.Notification.Builder(app))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Джарвис работает").setContentText(what.take(80))
            .setOngoing(true).addAction(android.app.Notification.Action.Builder(null, "Стоп", stop).build())
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, b.build())
    }

    private fun cancelNotice() = (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == Jarvis.ACTION_STOP) Jarvis.stop() }
}
