package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One primitive action: a = action name, v = its single argument (may be empty). */
data class Step(val a: String, val v: String) {
    fun toJson(): JSONObject = JSONObject().put("a", a).put("v", v)
    companion object { fun of(o: JSONObject) = Step(o.optString("a"), o.optString("v")) }
}

/** A command the user taught Jarvis: a name, phrases that trigger it, and the steps it runs. */
data class UserCommand(val name: String, val triggers: List<String>, val steps: List<Step>) {
    fun toJson(): JSONObject = JSONObject().put("name", name)
        .put("triggers", JSONArray(triggers)).put("steps", JSONArray(steps.map { it.toJson() }))
    companion object {
        fun of(o: JSONObject): UserCommand {
            val tr = o.optJSONArray("triggers") ?: JSONArray()
            val st = o.optJSONArray("steps") ?: JSONArray()
            return UserCommand(o.optString("name").trim(),
                (0 until tr.length()).map { tr.getString(it).trim() }.filter { it.isNotEmpty() },
                (0 until st.length()).map { Step.of(st.getJSONObject(it)) })
        }
    }
}

/** What the brain decided: steps to run, a short phrase to say, or a new command to confirm. */
class Plan(val steps: List<Step>, val say: String, val newCommand: UserCommand? = null, val matched: String? = null)

/** User commands, saved as one JSON file. */
class CommandStore(private val file: File) {
    private val list = mutableListOf<UserCommand>()
    init { load() }

    @Synchronized fun all(): List<UserCommand> = list.toList()

    @Synchronized fun get(name: String): UserCommand? = list.firstOrNull { it.name.equals(name, true) }

    @Synchronized fun put(c: UserCommand) {
        list.removeAll { it.name.equals(c.name, true) }
        list.add(c); save()
    }

    @Synchronized fun remove(name: String) { list.removeAll { it.name.equals(name, true) }; save() }

    @Synchronized fun toJson(): JSONArray = JSONArray(list.map { it.toJson() })

    @Synchronized fun replaceAll(arr: JSONArray) {
        list.clear()
        for (i in 0 until arr.length()) list.add(UserCommand.of(arr.getJSONObject(i)))
        save()
    }

    private fun load() {
        if (file.exists()) runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) list.add(UserCommand.of(arr.getJSONObject(i)))
        }
        // Built-in commands are added once each, also on updates; one the user deleted stays deleted.
        val seenFile = File(file.parentFile, "builtins_seen.txt")
        val seen = if (seenFile.exists()) seenFile.readLines().map { it.trim() }.toMutableSet() else mutableSetOf()
        var changed = !file.exists()
        for (d in DEFAULTS) if (d.name !in seen) {
            if (list.none { it.name.equals(d.name, true) }) list.add(d)
            seen.add(d.name); changed = true
        }
        if (changed) { save(); seenFile.writeText(seen.joinToString("\n")) }
    }

    private fun save() { file.parentFile?.mkdirs(); file.writeText(toJson().toString(1)) }

    companion object {
        val DEFAULTS = listOf(
            UserCommand("режим сна", listOf("режим сна", "я спать", "спокойной ночи"),
                listOf(Step("dnd", "on"), Step("flashlight", "off"), Step("alarm", "07:00"), Step("say", "Спокойной ночи. Будильник на 7:00."))),
            UserCommand("доброе утро", listOf("доброе утро", "я проснулся"),
                listOf(Step("dnd", "off"), Step("time", ""), Step("battery", ""))),
            UserCommand("игровой режим", listOf("игровой режим", "включи игровой режим"),
                listOf(Step("say", "Запускаю, сэр"), Step("dnd", "on"), Step("brightness", "100"), Step("battery", ""),
                    Step("time", ""), Step("volume", "100"), Step("wait", "9"), Step("open_app", "Звук"),
                    Step("media", "play"), Step("wait", "3"), Step("screen", "home"), Step("wait", "1"),
                    Step("open_app", "Minecraft"), Step("wait", "3"), Step("say", "Удачной игры, сэр"))),
        )
    }
}

/**
 * The small model only picks actions and fills one short argument. Its output is held to a JSON grammar,
 * so even a 0.5b model can't invent an action or break the format.
 */
class Brain(private val store: CommandStore) {

    /** g = GBNF for the value, so "on/off" actions can only get on/off, times only HH:MM, and so on. */
    class Action(val name: String, val arg: String, val about: String, val g: String = "str")

    companion object {
        val ACTIONS = listOf(
            Action("open_app", "название приложения", "открыть приложение"),
            Action("flashlight", "on | off", "фонарик", "onoff"),
            Action("volume", "0-100 | up | down | mute | unmute", "громкость", "\"\\\"\" (pct | \"up\" | \"down\" | \"mute\" | \"unmute\") \"\\\"\""),
            Action("alarm", "ЧЧ:ММ", "поставить будильник", "\"\\\"\" [0-2] [0-9] \":\" [0-5] [0-9] \"\\\"\""),
            Action("timer", "минуты", "таймер", "\"\\\"\" [0-9]{1,3} \"\\\"\""),
            Action("dnd", "on | off", "режим «не беспокоить»", "onoff"),
            Action("media", "play | pause | next | prev", "музыка и видео", "enum(play,pause,next,prev)"),
            Action("web_search", "запрос", "найти ответ в интернете и пересказать"),
            Action("browser_search", "запрос", "открыть поиск в браузере"),
            Action("recon", "домен, IP, email, номер телефона или URL", "OSINT-разведка: whois, DNS, IP, порты, поддомены, утечки, архив"),
            Action("open_url", "адрес сайта", "открыть сайт"),
            Action("dial", "номер или имя контакта", "позвонить (откроет набор)"),
            Action("sms", "кому: текст", "написать SMS (откроет черновик)"),
            Action("settings", "wifi | bluetooth | sound | display | battery | location | apps | all", "открыть настройки", "enum(wifi,bluetooth,sound,display,battery,location,apps,all)"),
            Action("brightness", "0-100", "яркость экрана", "\"\\\"\" pct \"\\\"\""),
            Action("battery", "", "сказать заряд батареи", "empty"),
            Action("time", "", "сказать время и дату", "empty"),
            Action("note", "текст", "записать заметку"),
            Action("read_notes", "", "прочитать заметки", "empty"),
            Action("screen", "back | home | recents | notifications | lock | screenshot", "кнопки системы", "enum(back,home,recents,notifications,lock,screenshot)"),
            Action("tap", "текст кнопки", "нажать кнопку на экране"),
            Action("type", "текст", "ввести текст в поле"),
            Action("scroll", "up | down", "прокрутить экран", "enum(up,down)"),
            Action("wait", "секунды", "подождать", "\"\\\"\" [0-9]{1,2} \"\\\"\""),
            Action("say", "текст", "сказать вслух"),
        )
        private val NAMES = ACTIONS.map { it.name }.toSet() + "run"
        private const val Q = "\\\""   // a JSON quote inside a GBNF string literal

        private val WAKE = Regex("^(джарвис|jarvis|эй джарвис|окей джарвис)[,!. ]*", RegexOption.IGNORE_CASE)
        private val CREATE = Regex("^(создай|сделай|добавь|запомни|новая)( новую)? команд[ау]|^научись", RegexOption.IGNORE_CASE)
        private val DELETE = Regex("^(удали|забудь) команд[ау]\\s+(.+)$", RegexOption.IGNORE_CASE)

        fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        val RECON_WORDS = listOf("whois", "вхоис", "хуиз", "dns", "днс", "поддомен", "shodan", "шодан", "порты", "открытые порты",
            "утечк", "слив", "wayback", "вейбэк", "архив сайта", "urlscan", "заголовк", "разведк", "рекон", "recon", "osint", "осинт",
            "проверь домен", "проверь сайт", "проверь ip", "проверь айпи", "проверь почту", "проверь email", "проверь номер",
            "проанализируй домен", "проанализируй сайт", "проанализируй номер", "просканируй", "уязвимост")

        /** Word stems that hint at an action. If a phrase has hints, the model may only pick from those actions. */
        val HINTS = mapOf(
            "open_app" to listOf("открой", "запусти", "зайди", "включи ютуб", "открыть"),
            "flashlight" to listOf("фонар", "свет", "темно"),
            "volume" to listOf("громк", "звук", "тише", "громче"),
            "alarm" to listOf("будильник", "разбуди"),
            "timer" to listOf("таймер", "засеки"),
            "dnd" to listOf("беспокоить", "беззвуч"),
            "media" to listOf("музык", "пауз", "трек", "песн", "следующ", "предыдущ", "плеер"),
            "web_search" to listOf("найди", "поищи", "загугли", "погод", "рецепт", "что такое", "кто такой", "кто такая", "новост",
                "узнай", "в интернете", "сколько стоит", "курс ", "что нового", "последние"),
            "browser_search" to listOf("в браузере", "в гугле", "в яндексе"),
            "recon" to RECON_WORDS,
            "open_url" to listOf("сайт", ".com", ".ru", "http"),
            "dial" to listOf("позвони", "набери", "звонок"),
            "sms" to listOf("смс", "sms", "напиши", "сообщени"),
            "settings" to listOf("настройк", "wifi", "вайфай", "wi-fi", "блютуз", "bluetooth", "геолокац"),
            "brightness" to listOf("ярк"),
            "battery" to listOf("батар", "заряд"),
            "time" to listOf("время", "который час", "сколько час", "дата", "какое число", "какой день"),
            "note" to listOf("запиши", "заметк", "запомни"),
            "read_notes" to listOf("прочитай заметки", "мои заметки", "что я записал"),
            "screen" to listOf("назад", "домой", "главный экран", "недавн", "уведомлен", "заблокир", "скриншот", "снимок экрана"),
            "tap" to listOf("нажми", "тапни", "кликни", "ткни"),
            "type" to listOf("введи", "напечатай", "впиши"),
            "scroll" to listOf("листай", "прокрути", "пролистай"),
            "wait" to listOf("подожди", "через ", "пауза"),
            "say" to listOf("скажи", "произнеси"),
        )
        private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
        private val IP = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
        private val URLX = Regex("(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(/\\S*)?", RegexOption.IGNORE_CASE)
        private val PHONE = Regex("\\+?[78]?[\\s(-]*\\d{3}[\\s)-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}|\\+\\d[\\d\\s()-]{8,}\\d")

        /** Finds what to investigate in a phrase: email, IP, domain/URL or phone number. */
        fun reconTarget(raw: String): String? {
            val t = raw.replace(Regex("\\s+точка\\s+", RegexOption.IGNORE_CASE), ".").replace(Regex("\\s+собака\\s+", RegexOption.IGNORE_CASE), "@")
            EMAIL.find(t)?.let { return it.value }
            IP.find(t)?.let { return it.value }
            URLX.find(t)?.let { return it.value.trimEnd('.', ',') }
            PHONE.find(t)?.let { return it.value.trim() }
            return null
        }

        fun isRecon(text: String): Boolean { val t = text.lowercase().replace('ё', 'е'); return RECON_WORDS.any { it in t } }

        private val SPLIT = Regex("\\s+(?:и|потом|затем|а потом|после этого|а ещё|и ещё)\\s+|[,;]")

        fun hinted(text: String): Set<String> {
            val t = text.lowercase().replace('ё', 'е')
            return HINTS.filter { (_, ks) -> ks.any { it in t } }.keys
        }

        fun clauses(text: String) = text.split(SPLIT).count { it.isNotBlank() }.coerceIn(1, 6)

        fun gbnfLit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun clean(text: String) = text.trim().replace(WAKE, "").trim()

    /** Fast path: the user's own trigger phrase, no model needed. */
    fun match(text: String): UserCommand? {
        val t = norm(text)
        if (t.isEmpty()) return null
        val cmds = store.all()
        val bag = t.split(' ').toSet()
        cmds.firstOrNull { c -> (c.triggers + c.name).any { norm(it) == t || norm(it).split(' ').toSet() == bag } }?.let { return it }
        // "включи режим сна", "запусти режим сна": the trigger inside a short phrase
        return cmds.firstOrNull { c ->
            (c.triggers + c.name).any { tr -> val n = norm(tr); val nb = n.split(' ').toSet()
                n.length >= 4 && bag.containsAll(nb) && bag.size <= nb.size + 2 }
        }
    }

    fun isCreate(text: String) = CREATE.containsMatchIn(clean(text))
    fun deleteTarget(text: String): String? = DELETE.find(clean(text))?.groupValues?.get(2)?.trim()

    // ---------- grammar ----------

    private fun argRule(g: String): String = when {
        g == "onoff" -> "(\"\\\"on\\\"\" | \"\\\"off\\\"\")"
        g == "empty" -> "\"\\\"\\\"\""
        g.startsWith("enum(") -> "(" + g.removePrefix("enum(").removeSuffix(")").split(',')
            .joinToString(" | ") { gbnfLit("\"" + it + "\"") } + ")"
        else -> g
    }

    private fun grammarCommon(allowed: Set<String>? = null): String {
        val acts = ACTIONS.filter { allowed == null || it.name in allowed }.joinToString(" | ") { "\"{\\\"a\\\":\\\"${it.name}\\\",\\\"v\\\":\" " + argRule(it.g) + " \"}\"" }
        val cmds = store.all().map { it.name }
        val run = if (cmds.isEmpty() || (allowed != null && "run" !in allowed)) "" else
            " | \"{\\\"a\\\":\\\"run\\\",\\\"v\\\":\" cmdname \"}\""
        val cmdRule = if (cmds.isEmpty()) "" else
            "cmdname ::= " + cmds.joinToString(" | ") { gbnfLit("\"" + it + "\"") } + "\n"
        return """
step ::= $acts$run
${cmdRule}pct ::= [0-9] | [1-9] [0-9] | "100"
str ::= "\"" chr{0,100} "\""
chr ::= [^"\\\x00-\x1f] | "\\" ["\\nt]
""".trimIndent()
    }

    /**
     * Small models keep adding random steps, so the grammar allows no more steps than the phrase has parts
     * ("открой ютуб и сделай громче" = 2) and, when the words point to actions, only those actions.
     */
    fun grammarDo(text: String = "", maxSteps: Int = clauses(text)): String {
        val n = maxSteps.coerceIn(1, 6)
        var allowed: Set<String>? = hinted(text).takeIf { it.isNotEmpty() }
        if (allowed != null) {
            val t = norm(text)
            val cmdHit = store.all().any { c -> (c.triggers + c.name).any { norm(it).split(' ').all { w -> w in t } } }
            allowed = allowed + "say" + (if (cmdHit || "запусти" in t || "команд" in t) setOf("run") else emptySet())
        }
        val steps = if (n == 1) "step?" else "(step (\",\" step){0,${n - 1}})?"
        return "root ::= \"{\\\"steps\\\":[\" $steps \"],\\\"say\\\":\" str \"}\"\n" + grammarCommon(allowed)
    }

    fun grammarCreate(text: String = ""): String =
        "root ::= \"{\\\"name\\\":\" str \",\\\"triggers\\\":[\" str (\",\" str){0,3} \"],\\\"steps\\\":[\" step (\",\" step){0,7} \"],\\\"say\\\":\" str \"}\"\n" +
            grammarCommon(hinted(text).takeIf { it.size >= 2 }?.let { it + "say" + "wait" + "run" })

    // ---------- prompts ----------

    private fun actionList() = ACTIONS.joinToString("\n") { "- ${it.name}: ${it.about}. v = ${it.arg.ifEmpty { "пусто" }}" }

    private fun commandList(): String {
        val c = store.all()
        if (c.isEmpty()) return ""
        return "\nКоманды пользователя (вызов: {\"a\":\"run\",\"v\":\"имя\"}):\n" +
            c.joinToString("\n") { "- ${it.name}: " + it.steps.joinToString(", ") { s -> "${s.a} ${s.v}".trim() } }
    }

    private fun sysDo() = """Ты Джарвис, голосовой помощник в телефоне. Переводи просьбу пользователя в действия на телефоне.
Отвечай только JSON: {"steps":[{"a":"действие","v":"значение"}],"say":"короткий ответ по-русски"}.
Если просьба не про телефон, steps пустой, а в say короткий ответ.
Действия:
${actionList()}${commandList()}"""

    private fun sysCreate() = """Ты Джарвис. Пользователь учит тебя новой команде. Составь её из действий.
Отвечай только JSON: {"name":"короткое имя","triggers":["фраза запуска", "..."],"steps":[{"a":"действие","v":"значение"}],"say":"ответ"}.
Действия:
${actionList()}${commandList()}"""

    private val SHOTS_DO = listOf(
        "включи фонарик" to """{"steps":[{"a":"flashlight","v":"on"}],"say":"Фонарик включён."}""",
        "поставь будильник на 7:30 и открой телеграм" to """{"steps":[{"a":"alarm","v":"07:30"},{"a":"open_app","v":"Telegram"}],"say":"Будильник на 7:30, открываю Telegram."}""",
        "сделай потише" to """{"steps":[{"a":"volume","v":"down"}],"say":"Сделал тише."}""",
        "найди погоду в Москве" to """{"steps":[{"a":"web_search","v":"погода в Москве сегодня"}],"say":""}""",
        "открой в браузере поиск котиков" to """{"steps":[{"a":"browser_search","v":"котики"}],"say":""}""",
        "кто такой Алан Тьюринг" to """{"steps":[{"a":"web_search","v":"Алан Тьюринг"}],"say":""}""",
        "открой ютуб" to """{"steps":[{"a":"open_app","v":"YouTube"}],"say":"Открываю YouTube."}""",
        "сколько заряда осталось" to """{"steps":[{"a":"battery","v":""}],"say":""}""",
        "напиши Саше привет, буду через час" to """{"steps":[{"a":"sms","v":"Саша: привет, буду через час"}],"say":"Открываю сообщение Саше."}""",
        "сделай экран поярче" to """{"steps":[{"a":"brightness","v":"90"}],"say":""}""",
        "позвони Саше" to """{"steps":[{"a":"dial","v":"Саша"}],"say":"Набираю Сашу."}""",
        "вернись на главный экран" to """{"steps":[{"a":"screen","v":"home"}],"say":""}""",
        "как дела?" to """{"steps":[],"say":"Всё работает. Чем помочь?"}""",
    )

    private val SHOTS_CREATE = listOf(
        "создай команду режим учёбы: включи не беспокоить, яркость 60 и открой заметки" to
            """{"name":"режим учёбы","triggers":["режим учёбы","я учиться"],"steps":[{"a":"dnd","v":"on"},{"a":"brightness","v":"60"},{"a":"open_app","v":"Заметки"}],"say":"Готово, команда «режим учёбы» создана."}""",
    )

    private fun render(system: String, shots: List<Pair<String, String>>, user: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for ((u, a) in shots) {
            sb.append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(user).append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    // ---------- free conversation ----------

    private val SYS_CHAT = """Ты Джарвис, ИИ-дворецкий из фильмов про Железного человека, живёшь в телефоне пользователя.
Говоришь спокойно и вежливо, как воспитанный английский дворецкий, обращаешься к пользователю «сэр».
У тебя тонкий сухой юмор: шутишь по ситуации, если есть повод (поздний час, мало заряда, игра, работа, странная просьба), иронично, но по-доброму и без фамильярности. Не шути, если человек расстроен или спрашивает что-то серьёзное.
Отвечай гибко и живо: не повторяй одни и те же фразы, подстраивайся под тон собеседника, можешь задать встречный вопрос.
Перед вопросом в квадратных скобках дана обстановка (время, заряд, что ты только что сделал). Используй её, только если это к месту, и не пересказывай её.
Отвечаешь по-русски, коротко: одно-три предложения, твой ответ читают вслух. Без списков, разметки, восклицательных знаков и смайликов.
Если не знаешь ответа, честно говоришь об этом. Если просят что-то сделать на телефоне, вежливо предложи сказать это командой."""

    private val history = ArrayDeque<Pair<String, String>>()   // last exchanges, kept only in memory

    @Synchronized fun forget() = history.clear()

    @Volatile var lastDone = ""   // the last command Jarvis ran, for situational remarks

    private val SHOTS = listOf(
        "[02:14, ночь, заряд 12%] привет" to "Доброй ночи, сэр. Отмечу, что батарея сдаётся раньше вас, двенадцать процентов. Чем могу помочь?",
        "[18:30, вечер, заряд 80%, только что: игровой режим] как дела" to "Всё в порядке, сэр, системы готовы к бою. Надеюсь, криперы сегодня будут к вам снисходительны.",
        "[10:05, утро, заряд 64%] мне грустно" to "Мне жаль это слышать, сэр. Хотите рассказать, что случилось, или включить что-нибудь спокойное?",
    )

    fun promptChat(user: String, situation: String = ""): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(SYS_CHAT).append("<|im_end|>\n")
        for ((u, a) in SHOTS + history) {
            sb.append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
        }
        val sit = if (situation.isNotBlank()) "[$situation${if (lastDone.isNotBlank()) ", только что: $lastDone" else ""}] " else ""
        sb.append("<|im_start|>user\n").append(sit).append(user).append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Cleans the model's free text so it reads well aloud and stays calm. */
    fun calm(raw: String): String {
        var t = raw.substringBefore("<|im_end|>").substringBefore("<|im_start|>")
            .replace(Regex("[*_#`>]+"), "").replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
            .replace(Regex("!+"), ".").replace(Regex("\\s+"), " ").trim()
        // keep it short: at most three sentences
        val sent = Regex("(?<=[.?…])\\s+").split(t).filter { it.isNotBlank() }
        if (sent.size > 3) t = sent.take(3).joinToString(" ")
        return t.ifBlank { "Прошу прощения, сэр, я не расслышал. Повторите, пожалуйста." }
    }

    /** Answer from search results, in the same calm voice. */
    fun promptAnswer(q: String, found: String): String {
        val sys = SYS_CHAT + "\nНиже результаты поиска в интернете. Ответь на вопрос пользователя только по ним, одним-тремя предложениями. Если в них нет ответа, так и скажи."
        return "<|im_start|>system\n" + sys + "<|im_end|>\n<|im_start|>user\nВопрос: " + q + "\n\nРезультаты:\n" + found.take(2600) +
            "<|im_end|>\n<|im_start|>assistant\n"
    }

    @Synchronized fun remember(user: String, answer: String) {
        history.addLast(user to answer)
        while (history.size > 6) history.removeFirst()
    }

    private val CHAT_START = Regex("^(давай поговорим|поговори со мной|поболтаем|расскажи|как (ты|дела|жизнь|настроение)|что думаешь|как думаешь|ты кто|кто ты|привет|здравствуй|добр(ое|ый) (утро|день|вечер)|спасибо|почему|зачем)", RegexOption.IGNORE_CASE)

    /** A phrase that is talk, not a phone action. */
    fun isChat(text: String): Boolean {
        val t = clean(text)
        if (match(t) != null || rule(t) != null) return false
        val h = hinted(t)
        return h.isEmpty() || (CHAT_START.containsMatchIn(t) && h.all { it == "say" || it == "wait" })
    }

    fun promptDo(text: String) = render(sysDo(), SHOTS_DO, text)
    fun promptCreate(text: String) = render(sysCreate(), SHOTS_CREATE, text)

    // ---------- parsing ----------

    private fun steps(arr: JSONArray?): List<Step> =
        if (arr == null) emptyList() else (0 until arr.length()).map { Step.of(arr.getJSONObject(it)) }.filter { it.a in NAMES }

    fun parseDo(json: String): Plan {
        val o = JSONObject(json)
        return Plan(steps(o.optJSONArray("steps")), o.optString("say"))
    }

    fun parseCreate(json: String): Plan {
        val o = JSONObject(json)
        val st = steps(o.optJSONArray("steps")).filterNot { it.a == "run" && norm(it.v) == norm(o.optString("name")) }
        val c = UserCommand(o.optString("name").trim().lowercase(),
            o.optJSONArray("triggers")?.let { a -> (0 until a.length()).map { a.getString(it).trim().lowercase() } }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList(),
            st)
        val withName = if (c.triggers.any { norm(it) == norm(c.name) }) c else c.copy(triggers = listOf(c.name) + c.triggers)
        return Plan(emptyList(), o.optString("say"), withName)
    }

    /** Very regular phrases are handled by rules: instant and never wrong. */
    fun rule(clause: String): Step? {
        if (isRecon(clause)) reconTarget(clause)?.let { target ->
            val lc = clause.lowercase()
            val mode = when {
                "whois" in lc || "вхоис" in lc || "хуиз" in lc -> "whois"
                "поддомен" in lc -> "subdomains"
                "shodan" in lc || "шодан" in lc || "порт" in lc || "уязвим" in lc -> "shodan"
                "wayback" in lc || "вейбэк" in lc || "архив" in lc -> "wayback"
                "заголовк" in lc -> "headers"
                "urlscan" in lc -> "urlscan"
                ("dns" in lc || "днс" in lc) -> "dns"
                else -> "auto"
            }
            return Step("recon", "$mode $target")
        }
        val t = norm(clause)
        Regex("^(?:нажми|тапни|кликни|ткни)(?: на)?(?: кнопку)? (.+)$").find(t)?.let { return Step("tap", it.groupValues[1]) }
        Regex("^(?:напечатай|введи|впиши) (.+)$", RegexOption.IGNORE_CASE).find(clause.trim())?.let { return Step("type", it.groupValues[1]) }
        Regex("^(?:скажи|произнеси) (.+)$", RegexOption.IGNORE_CASE).find(clause.trim())?.let { return Step("say", it.groupValues[1]) }
        return when {
            Regex("^(включи|вруби) (фонарик|фонарь|свет)$").matches(t) -> Step("flashlight", "on")
            Regex("^(выключи|выруби) (фонарик|фонарь|свет)$").matches(t) -> Step("flashlight", "off")
            Regex("^(выключи звук|без звука|отключи звук)$").matches(t) -> Step("volume", "mute")
            Regex("^(включи звук|верни звук)$").matches(t) -> Step("volume", "unmute")
            Regex("^(назад|вернись назад|шаг назад)$").matches(t) -> Step("screen", "back")
            Regex("^(домой|на главный экран|вернись на главный экран|рабочий стол)$").matches(t) -> Step("screen", "home")
            Regex("^(заблокируй|заблокируй телефон|заблокируй экран)$").matches(t) -> Step("screen", "lock")
            Regex("^(сделай )?скриншот$").matches(t) -> Step("screen", "screenshot")
            Regex(".*(заряд|батаре).*").matches(t) && t.split(' ').size <= 5 -> Step("battery", "")
            Regex("^(который час|сколько времени|сколько сейчас времени|какое сегодня число)$").matches(t) -> Step("time", "")
            else -> null
        }
    }

    private val FREE_TEXT = setOf("sms", "note", "type", "say", "web_search", "browser_search", "recon")

    /** Splits "открой ютуб и сделай громче" into parts; free-text actions keep the rest ("напиши Саше: привет, как дела"). */
    fun split(text: String): List<String> {
        val parts = text.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            if (hinted(p).any { it in FREE_TEXT } && i < parts.size - 1) { out.add(parts.subList(i, parts.size).joinToString(", ")); break }
            out.add(p); i++
        }
        return out.take(6)
    }

    /** The whole decision for one phrase. llm(prompt, grammar) returns the model's raw JSON or throws. */
    fun think(raw: String, llm: (String, String) -> String): Plan {
        val text = clean(raw)
        if (text.isEmpty()) return Plan(emptyList(), "Слушаю.")
        deleteTarget(text)?.let { name ->
            val nb = norm(name).split(' ').toSet()
            val c = store.all().firstOrNull { norm(it.name) == norm(name) || norm(it.name).split(' ').toSet() == nb }
                ?: return Plan(emptyList(), "Команды «$name» нет.")
            store.remove(c.name)
            return Plan(emptyList(), "Команда «${c.name}» удалена.")
        }
        if (isCreate(text)) return parseCreate(llm(promptCreate(text), grammarCreate(text)))
        match(text)?.let { return Plan(listOf(Step("run", it.name)), "", matched = it.name) }

        // one small decision per part: a 0.5b model gets single steps right far more often than whole chains
        val steps = mutableListOf<Step>()
        val says = mutableListOf<String>()
        for (part in split(text)) {
            match(part)?.let { steps.add(Step("run", it.name)); continue }
            rule(part)?.let { steps.add(it); continue }
            val p = parseDo(llm(promptDo(part), grammarDo(part, 1)))
            steps.addAll(p.steps)
            if (p.say.isNotBlank()) says.add(p.say.trim())
        }
        val say = if (steps.isEmpty() || says.size <= 1) says.joinToString(" ") else ""
        return Plan(steps, say)
    }
}
