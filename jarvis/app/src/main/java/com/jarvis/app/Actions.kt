package com.jarvis.app

import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs primitive steps on the phone. Every step returns a short Russian result line. */
class Actions(private val ctx: Context, private val store: CommandStore, private val say: (String) -> Unit) {

    private val notesFile = File(ctx.filesDir, "notes.json")
    @Volatile var stopped = false
    /** Set by Jarvis: (kind, value) -> result line. kind = "web" or "recon". */
    var research: ((String, String) -> String)? = null

    private fun start(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    private fun needAccess(): String = "Нужен доступ «Джарвис» в Спец. возможностях (Настройки Джарвиса → Разрешения)."

    /** Runs steps in order; "run" expands user commands (up to 3 levels deep, so commands can't loop forever). */
    fun runAll(steps: List<Step>, log: (String) -> Unit, depth: Int = 0) {
        for ((i, s) in steps.withIndex()) {
            if (stopped) { log("⏹ остановлено"); return }
            if (s.a == "run") {
                val c = store.get(s.v)
                if (c == null) { log("❓ нет команды «${s.v}»"); continue }
                if (depth >= 3) { log("⚠️ слишком глубокая вложенность команд"); continue }
                log("▶️ ${c.name}")
                runAll(c.steps, log, depth + 1)
                continue
            }
            val r = runCatching { run(s) }.getOrElse { "ошибка: ${it.message}" }
            log("• ${s.a} ${s.v}".trim() + " → " + r)
            // opening an app takes a moment; the next step (tap, type) needs its screen
            if (s.a in setOf("open_app", "open_url", "settings", "browser_search") && i < steps.size - 1) SystemClock.sleep(1500)
        }
    }

    fun run(s: Step): String = when (s.a) {
        "open_app" -> openApp(s.v)
        "flashlight" -> torch(s.v == "on")
        "volume" -> volume(s.v)
        "alarm" -> alarm(s.v)
        "timer" -> timer(s.v)
        "dnd" -> dnd(s.v == "on")
        "media" -> media(s.v)
        "web_search" -> research?.invoke("web", s.v) ?: search(s.v)
        "browser_search" -> search(s.v)
        "recon" -> research?.invoke("recon", s.v) ?: "разведка недоступна"
        "open_url" -> openUrl(s.v)
        "dial" -> dial(s.v)
        "sms" -> sms(s.v)
        "settings" -> settings(s.v)
        "brightness" -> brightness(s.v)
        "battery" -> battery().also(say)
        "time" -> time().also(say)
        "note" -> note(s.v)
        "read_notes" -> readNotes().also(say)
        "screen" -> JarvisAccess.instance?.let { if (it.global(s.v)) "ок" else "не получилось" } ?: needAccess()
        "tap" -> JarvisAccess.instance?.let { if (it.tapText(s.v)) "нажал «${s.v}»" else "не нашёл «${s.v}» на экране" } ?: needAccess()
        "type" -> JarvisAccess.instance?.let { if (it.type(s.v)) "ввёл" else "нет поля для ввода" } ?: needAccess()
        "scroll" -> JarvisAccess.instance?.let { if (it.scroll(s.v)) "ок" else "не листается" } ?: needAccess()
        "wait" -> { SystemClock.sleep((s.v.toIntOrNull() ?: 1).coerceIn(0, 30) * 1000L); "ок" }
        "say" -> { say(s.v); "ок" }
        else -> "неизвестное действие"
    }

    // ---------- apps ----------

    private val ALIASES = mapOf(
        "ютуб" to "youtube", "ютюб" to "youtube", "телеграм" to "telegram", "телега" to "telegram", "тг" to "telegram",
        "вк" to "vk", "вконтакте" to "vk", "ватсап" to "whatsapp", "вотсап" to "whatsapp", "тикток" to "tiktok",
        "хром" to "chrome", "инстаграм" to "instagram", "инста" to "instagram", "спотифай" to "spotify",
        "дискорд" to "discord", "майнкрафт" to "minecraft", "бравл старс" to "brawl stars", "бравл" to "brawl stars",
        "стим" to "steam", "твич" to "twitch", "гугл" to "google", "плей маркет" to "play store", "плэй маркет" to "play store",
        "камера" to "camera", "галерея" to "gallery", "фото" to "photos", "калькулятор" to "calculator", "часы" to "clock",
        "календарь" to "calendar", "почта" to "gmail", "карты" to "maps", "музыка" to "music", "заметки" to "notes",
        "настройки" to "settings", "контакты" to "contacts", "сообщения" to "messages", "телефон" to "phone",
    )

    private val TR = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")

    private fun latin(s: String) = s.lowercase().map { TR[it] ?: it.toString() }.joinToString("").replace(Regex("[^a-z0-9]"), "")

    private fun openApp(name: String): String {
        val n = name.lowercase().trim()
        val want = ALIASES[n] ?: n
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it to it.loadLabel(pm).toString() }
        val w1 = want.lowercase(); val w2 = latin(want); val w3 = latin(n)
        fun score(label: String): Int {
            val l = label.lowercase(); val ll = latin(label)
            return when {
                l == w1 || l == n -> 100
                ll.isNotEmpty() && (ll == w2 || ll == w3) -> 95
                l.startsWith(w1) || l.startsWith(n) -> 80
                ll.isNotEmpty() && (ll.startsWith(w2) || (w3.length >= 3 && ll.startsWith(w3))) -> 75
                l.contains(w1) || l.contains(n) -> 60
                ll.isNotEmpty() && w2.length >= 3 && ll.contains(w2) -> 55
                else -> 0
            }
        }
        val best = apps.maxByOrNull { score(it.second) }?.takeIf { score(it.second) > 0 }
            ?: return "не нашёл приложение «$name»"
        val i = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName) ?: return "не запускается"
        return if (start(i)) "открыл ${best.second}" else "не удалось открыть"
    }

    // ---------- hardware ----------

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "нет вспышки"
        cm.setTorchMode(id, on)
        return if (on) "фонарик вкл" else "фонарик выкл"
    }

    private fun volume(v: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        when (v) {
            "up" -> repeat(2) { am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, flags) }
            "down" -> repeat(2) { am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, flags) }
            "mute" -> {
                am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, flags)
                if (canDnd()) am.ringerMode = AudioManager.RINGER_MODE_VIBRATE   // the ringer needs DND access
            }
            "unmute" -> {
                am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, flags)
                if (canDnd()) am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            else -> {
                val pct = (v.toIntOrNull() ?: return "не понял громкость").coerceIn(0, 100)
                am.setStreamVolume(st, am.getStreamMaxVolume(st) * pct / 100, flags)
            }
        }
        return "громкость: $v"
    }

    private fun canDnd() = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    private fun dnd(on: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "разреши Джарвису доступ к «Не беспокоить» и повтори"
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "не беспокоить вкл" else "не беспокоить выкл"
    }

    private fun media(v: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = when (v) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "медиа: $v"
    }

    private fun brightness(v: String): String {
        val pct = (v.toIntOrNull() ?: return "не понял яркость").coerceIn(0, 100)
        if (!Settings.System.canWrite(ctx)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return "разреши Джарвису менять системные настройки и повтори"
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceAtLeast(3))
        return "яркость $pct%"
    }

    private fun battery(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Заряд $pct%" + if (bm.isCharging) ", заряжается." else "."
    }

    /** Short situation line for the chat model: "23:40, ночь, заряд 15%, заряжается". */
    fun situation(): String = runCatching {
        val c = java.util.Calendar.getInstance(); val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (h) { in 5..11 -> "утро"; in 12..17 -> "день"; in 18..22 -> "вечер"; else -> "ночь" }
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        SimpleDateFormat("HH:mm", Locale("ru")).format(Date()) + ", $part, " + SimpleDateFormat("EEEE", Locale("ru")).format(Date()) +
            ", заряд ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" + if (bm.isCharging) ", заряжается" else ""
    }.getOrDefault("")

    private fun time(): String = SimpleDateFormat("'Сейчас' HH:mm, EEEE, d MMMM", Locale("ru")).format(Date())

    // ---------- clock ----------

    private fun alarm(v: String): String {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(v) ?: return "не понял время"
        val h = m.groupValues[1].toInt().coerceIn(0, 23); val mi = m.groupValues[2].toInt().coerceIn(0, 59)
        val ok = start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, mi).putExtra(AlarmClock.EXTRA_MESSAGE, "Джарвис").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return if (ok) "будильник %02d:%02d".format(h, mi) else "нет приложения часов"
    }

    private fun timer(v: String): String {
        val min = (Regex("\\d+").find(v)?.value?.toIntOrNull() ?: return "не понял время").coerceIn(1, 1440)
        val ok = start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, min * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Джарвис").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return if (ok) "таймер на $min мин" else "нет приложения часов"
    }

    // ---------- web ----------

    private fun search(q: String): String {
        if (start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))) return "ищу «$q»"
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))))) "ищу «$q»" else "нет браузера"
    }

    private fun openUrl(u: String): String {
        val url = if (u.startsWith("http")) u else "https://" + u.trim()
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "открыл $url" else "нет браузера"
    }

    private fun settings(v: String): String {
        val action = when (v) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (start(Intent(action))) "открыл настройки $v" else "не открываются"
    }

    // ---------- people ----------

    /** "мама", "маме", "Саше": finds a contact by the first letters of the name (Russian endings change). */
    private fun phoneOf(who: String): String? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3) return digits
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val w = who.lowercase().replace('ё', 'е').trim()
        val stem = if (w.length > 4) w.dropLast(1) else w
        val cur = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null) ?: return null
        cur.use {
            var fallback: String? = null
            while (it.moveToNext()) {
                val name = (it.getString(0) ?: "").lowercase().replace('ё', 'е')
                val num = it.getString(1) ?: continue
                if (name == w) return num
                if (fallback == null && name.split(' ').any { p -> p.startsWith(stem.take(maxOf(3, stem.length - 1))) }) fallback = num
            }
            return fallback
        }
    }

    private fun dial(who: String): String {
        val num = phoneOf(who)
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (num ?: ""))))
        return if (num != null) "набор открыт, нажми вызов" else "не нашёл «$who» в контактах (дай доступ к контактам в настройках Джарвиса)"
    }

    private fun sms(v: String): String {
        val who = v.substringBefore(':').trim()
        val text = if (':' in v) v.substringAfter(':').trim() else ""
        val num = phoneOf(who)
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (num ?: ""))).putExtra("sms_body", text))
        return if (num != null) "черновик готов, нажми отправить" else "открыл SMS, получателя «$who» выбери сам"
    }

    // ---------- notes ----------

    private fun notes(): JSONArray = runCatching { JSONArray(notesFile.readText()) }.getOrElse { JSONArray() }

    private fun note(text: String): String {
        val a = notes()
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("text", text))
        notesFile.writeText(a.toString())
        return "записал"
    }

    fun notesJson(): JSONArray = notes()

    private fun readNotes(): String {
        val a = notes()
        if (a.length() == 0) return "Заметок нет."
        return (maxOf(0, a.length() - 5) until a.length()).joinToString(". ") { a.getJSONObject(it).optString("text") }
    }
}
