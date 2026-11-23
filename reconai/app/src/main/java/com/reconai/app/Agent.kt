package com.reconai.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

const val SYSTEM = "Ты ReconAI — ассистент по OSINT-разведке инфраструктуры, сайтов и открытой информации. " +
        "Пользуйся инструментами, проверяй факты, давай ссылки. Отвечай по-русски, кратко. " +
        "Не составляй досье на частных лиц и не ищи их личные данные. По номеру телефона давай только технический анализ " +
        "(страна, регион, оператор, тип) и ссылки для ручной проверки, не пытайся установить владельца. " +
        "Проверку email на утечки предлагай для своих адресов."

data class Call(val name: String, val args: JSONObject)
data class Msg(val role: String, val content: String, val calls: List<Call> = emptyList(), val toolName: String? = null)

class LlmError(msg: String) : Exception(msg)

interface Llm {
    fun chat(msgs: List<Msg>, tools: Boolean, maxTokens: Int): Msg
}

/** llama.cpp inside the APK. Qwen2.5 chat format with its native <tool_call> tags. */
class LocalLlm(private val cfg: Config) : Llm {
    private fun toolsBlock(): String = buildString {
        append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
        Tools.SPECS.forEach { append("\n").append(Tools.specJson(it).toString()) }
        append("\n</tools>\n\nFor each function call, return a json object with function name and arguments within " +
                "<tool_call></tool_call> XML tags:\n<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
    }

    fun render(msgs: List<Msg>, tools: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            when (m.role) {
                "system" -> sb.append("<|im_start|>system\n").append(m.content).append(if (tools) toolsBlock() else "").append("<|im_end|>\n")
                "user" -> sb.append("<|im_start|>user\n").append(m.content).append("<|im_end|>\n")
                "assistant" -> {
                    sb.append("<|im_start|>assistant\n").append(m.content)
                    m.calls.forEach {
                        if (sb.last() != '\n') sb.append("\n")
                        sb.append("<tool_call>\n").append(JSONObject().put("name", it.name).put("arguments", it.args)).append("\n</tool_call>")
                    }
                    sb.append("<|im_end|>\n")
                }
                "tool" -> {   // consecutive tool results go into one user turn
                    sb.append("<|im_start|>user")
                    while (i < msgs.size && msgs[i].role == "tool") {
                        sb.append("\n<tool_response>\n").append(msgs[i].content).append("\n</tool_response>")
                        i++
                    }
                    sb.append("<|im_end|>\n")
                    continue
                }
            }
            i++
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    override fun chat(msgs: List<Msg>, tools: Boolean, maxTokens: Int): Msg {
        val path = cfg.localModel
        if (path.isBlank() || !java.io.File(path).exists())
            throw LlmError("Модель не скачана. Откройте ⚙️ Настройки → «Скачать модель» или выберите .gguf файл.")
        LlamaBridge.ensure(path, cfg.numCtx, cfg.threads)?.let { throw LlmError("Модель: $it") }
        var work = msgs
        repeat(3) {
            val r = LlamaBridge.generate(render(work, tools), maxTokens, cfg.temp.toFloat())
            if (r.ok) return parse(r.text)
            if (!r.text.startsWith("контекст переполнен")) throw LlmError(r.text)
            // context overflow: shorten tool results and older messages, then retry
            work = work.map { m ->
                if (m.role == "system") m else m.copy(content = m.content.take(maxOf(200, m.content.length * 3 / 5)))
            }
        }
        throw LlmError("Контекст переполнен даже после сокращения. Нажмите «Новый чат» или увеличьте контекст в настройках.")
    }

    companion object {
        private val CALL = Regex("<tool_call>\\s*(\\{.*?\\})\\s*(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)

        fun parse(text: String): Msg {
            val calls = mutableListOf<Call>()
            for (m in CALL.findAll(text)) {
                runCatching {
                    val j = JSONObject(m.groupValues[1])
                    val args = j.opt("arguments").let { a ->
                        when (a) { is JSONObject -> a; is String -> runCatching { JSONObject(a) }.getOrDefault(JSONObject()); else -> JSONObject() }
                    }
                    calls.add(Call(j.getString("name"), args))
                }
            }
            val content = text.replace(CALL, "").trim()
            return Msg("assistant", content, calls)
        }
    }
}

/** Ollama on a PC in the same Wi-Fi network, or in Termux on this phone. */
class OllamaLlm(private val cfg: Config) : Llm {
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.MINUTES).build()

    private fun toJson(m: Msg): JSONObject {
        val j = JSONObject().put("role", m.role).put("content", m.content)
        if (m.calls.isNotEmpty()) j.put("tool_calls", JSONArray(m.calls.map {
            JSONObject().put("function", JSONObject().put("name", it.name).put("arguments", it.args))
        }))
        if (m.toolName != null) j.put("tool_name", m.toolName)
        return j
    }

    override fun chat(msgs: List<Msg>, tools: Boolean, maxTokens: Int): Msg {
        val body = JSONObject().put("model", cfg.model).put("stream", false)
            .put("messages", JSONArray(msgs.map { toJson(it) }))
            .put("options", JSONObject().put("num_ctx", cfg.numCtx).put("num_predict", maxTokens))
        if (tools) body.put("tools", JSONArray(Tools.SPECS.map { Tools.specJson(it) }))
        val req = Request.Builder().url(cfg.ollama + "/api/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val r = try { http.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") } }
        catch (e: java.io.IOException) { throw LlmError("Ollama не отвечает по адресу ${cfg.ollama}. На ПК: OLLAMA_HOST=0.0.0.0 ollama serve") }
        if (r.has("error")) throw LlmError("Ollama: " + r.getString("error"))
        val m = r.getJSONObject("message")
        val calls = mutableListOf<Call>()
        m.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i).getJSONObject("function")
                val a = f.opt("arguments")
                calls.add(Call(f.getString("name"), when (a) { is JSONObject -> a; is String -> runCatching { JSONObject(a) }.getOrDefault(JSONObject()); else -> JSONObject() }))
            }
        }
        return Msg("assistant", m.optString("content"), calls)
    }

    fun installed(): List<String> = try {
        val r = http.newBuilder().readTimeout(4, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(cfg.ollama + "/api/tags").build()).execute().use { JSONObject(it.body?.string() ?: "{}") }
        val a = r.optJSONArray("models") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).getString("name") }
    } catch (_: Exception) { emptyList() }
}

class Job {
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var answer: String? = null
    @Volatile var done = false
    @Volatile var data: JSONObject? = null
    fun toJson(): JSONObject = JSONObject().put("log", JSONArray(synchronized(log) { log.toList() }))
        .put("answer", answer ?: JSONObject.NULL).put("done", done).also { j -> data?.let { j.put("data", it) } }
}

/** What the model sees from a tool: no raw WHOIS dump, it only eats the small context. */
fun forModel(name: String, res: Any, limit: Int): String {
    if (name == "whois_lookup" && res is JSONObject) return JSONObject(res.toString()).apply { remove("raw") }.toString().take(limit)
    return Tools.toText(res, limit)
}

class Agent(private val cfg: Config) {
    private val chats = mutableMapOf<String, MutableList<Msg>>()

    private fun llm(): Llm = if (cfg.backend == "ollama") OllamaLlm(cfg) else LocalLlm(cfg)

    fun reset(user: String) = synchronized(chats) { chats.remove(user) }

    /**
     * Small models (1.5b) often answer from imagination instead of calling tools.
     * So the obvious tools are run up front from keywords and the model gets real data to work with.
     */
    fun autoTools(text: String): List<Call> {
        val t = text.lowercase()
        Regex("(/\\S+/uploads/\\S+\\.\\w{2,5})").find(text)?.let {
            return listOf(Call("image_exif", JSONObject().put("path", it.groupValues[1].trimEnd('.', ',', ':'))))
        }
        val email = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+").find(text)?.value
        if (email != null) return listOf(Call("breach_check", JSONObject().put("email", email.trimEnd('.'))))
        val phone = Regex("(?<![\\w.])(\\+?\\d[\\d ()\\-]{8,17}\\d)(?![\\w.])").find(text)?.groupValues?.get(1)
        if (phone != null && phone.count { it.isDigit() } in 10..15 && !Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(phone))
            return listOf(Call("phone_info", JSONObject().put("number", phone)))
        val ip = Regex("(?<![\\w./])(\\d{1,3}(?:\\.\\d{1,3}){3})(?![\\w])").find(text)?.groupValues?.get(1)
        val url = Regex("https?://[^\\s,]+").find(text)?.value
        val domain = Regex("(?<![\\w./@-])((?:[a-z0-9-]+\\.)+[a-z]{2,24})(?![\\w-])", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].lowercase() }
            .firstOrNull { !it.endsWith(".jpg") && !it.endsWith(".png") && !it.endsWith(".jpeg") }
        val target = domain ?: url?.substringAfter("://")?.substringBefore('/')?.substringBefore(':') ?: ip
        fun has(vararg k: String) = k.any { it in t }
        val out = mutableListOf<Call>()
        fun add(name: String, v: String) { if (out.none { it.name == name }) out.add(Call(name, JSONObject().put(Tools.paramOf(name)!!, v))) }
        if (target != null) {
            val isIp = target == ip
            if (!isIp && has("dns", "днс", "mx", "txt", "запис", "почт")) add("dns_lookup", target)
            if (!isIp && has("whois", "вхоис", "регистр", "владел", "создан", "истека", "дата")) add("whois_lookup", target)
            if (has("хост", "ip", "айпи", "провайдер", "asn", "располож", "сервер", "страна", "гео")) add("ip_info", target)
            if (!isIp && has("поддомен", "subdomain", "сабдомен")) add("subdomains", target)
            if (!isIp && has("заголов", "header", "редирект", "cms", "технолог", "защит")) add("http_headers", url ?: target)
            if (has("порт", "port", "shodan", "шодан", "уязвим", "cve", "сервис")) add("shodan", target)
            if (!isIp && has("urlscan", "скан", "скрин", "что грузит", "трекер")) add("urlscan", target)
            if (!isIp && has("архив", "wayback", "истори", "раньше", "снимк")) add("wayback", target)
            if (!isIp && has("открой", "страниц", "что на сайте", "прочитай", "о чём сайт", "контакт")) add("fetch_url", url ?: target)
            if (out.isEmpty()) { if (isIp) add("ip_info", target) else { add("dns_lookup", target); add("ip_info", target) } }
        } else if (has("найди", "поиск", "загугли", "поищи", "что такое", "что за", "новост", "search", "кто такие")) {
            add("web_search", text.take(200))
        }
        return out.take(3)
    }

    fun ask(job: Job, user: String, text: String) {
        val msgs = synchronized(chats) { chats.getOrPut(user) { mutableListOf(Msg("system", SYSTEM)) } }
        msgs.add(Msg("user", text))
        try {
            val llm = llm()
            val pre = autoTools(text)
            if (pre.isNotEmpty()) {
                msgs.add(Msg("assistant", "", pre))
                for (c in pre) {
                    job.log.add("🔧 ${c.name} ${c.args}")
                    msgs.add(Msg("tool", forModel(c.name, Tools.run(c.name, c.args), cfg.toolChars), toolName = c.name))
                }
            }
            var finished = false
            for (step in 0 until cfg.maxSteps) {
                val m = llm.chat(msgs, true, 512)
                msgs.add(m)
                if (m.calls.isEmpty()) { job.answer = m.content; finished = true; break }
                for (c in m.calls) {
                    job.log.add("🔧 ${c.name} ${c.args}")
                    msgs.add(Msg("tool", forModel(c.name, Tools.run(c.name, c.args), cfg.toolChars), toolName = c.name))
                }
            }
            if (!finished) job.answer = "Достигнут лимит шагов — уточни запрос."
        } catch (e: LlmError) {
            job.answer = e.message
        } catch (e: Exception) {
            job.answer = "Ошибка: ${e.javaClass.simpleName}: ${e.message}"
        }
        // keep the context short on a phone: system + last N messages, starting with a user question
        val h = cfg.history
        if (msgs.size > h + 1) {
            var tail = msgs.takeLast(h)
            while (tail.isNotEmpty() && tail[0].role != "user") tail = tail.drop(1)
            val sys = msgs[0]
            msgs.clear(); msgs.add(sys); msgs.addAll(tail)
        }
        job.done = true
    }

    /** Fast recon: the tools run directly and the model only writes the summary (reliable even on 1.5b). */
    fun recon(job: Job, target: String) {
        val isIp = target.replace(".", "").all { it.isDigit() } || ':' in target
        val plan = if (isIp) listOf("ip_info", "shodan") else listOf("dns_lookup", "whois_lookup", "ip_info", "shodan", "http_headers", "subdomains", "urlscan")
        val data = JSONObject()
        for (name in plan) {
            job.log.add("🔧 $name $target")
            var res = Tools.run(name, JSONObject().put(Tools.paramOf(name)!!, target))
            if (name == "http_headers" && res is JSONObject && res.has("headers")) {
                val h = res.getJSONObject("headers"); val keep = JSONObject()
                for (k in h.keys()) if (k.lowercase() in setOf("server", "x-powered-by", "via", "cf-ray", "x-cache", "strict-transport-security")) keep.put(k, h.get(k))
                res.put("headers", keep)
            }
            if (name == "whois_lookup" && res is JSONObject) res.remove("raw")
            if (name == "urlscan" && res is JSONObject) {
                val sc = res.optJSONArray("scans") ?: JSONArray(); val short = JSONArray()
                for (i in 0 until minOf(3, sc.length())) sc.getJSONObject(i).let { short.put(JSONObject().put("time", it.optString("time")).put("url", it.optString("url")).put("server", it.optString("server")).put("report", it.optString("report"))) }
                res = JSONObject().put("total", res.optInt("total")).put("latest", short)
            }
            if (name == "subdomains" && res is JSONArray) {
                val sample = JSONArray(); for (i in 0 until minOf(25, res.length())) sample.put(res.get(i))
                res = JSONObject().put("count", res.length()).put("sample", sample)
            }
            data.put(name, res)
        }
        job.data = data
        val prompt = "Кратко, по пунктам, на русском подведи итог разведки по цели $target: хостинг и провайдер, DNS и почта, " +
                "регистратор и даты, открытые порты и CVE из Shodan, заметные поддомены, что бросается в глаза.\n\n" + data.toString().take(2800)
        job.answer = try {
            llm().chat(listOf(Msg("system", SYSTEM), Msg("user", prompt)), false, 500).content
        } catch (e: Exception) {
            "${e.message}\n\nСырые данные:\n" + data.toString(1).take(4000)
        }
        job.done = true
    }
}
