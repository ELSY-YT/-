package com.jarvis.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** prompt is already in Qwen chat format; grammar is GBNF (ignored by Ollama, which only gets "format: json"). */
interface Llm {
    fun gen(prompt: String, grammar: String, maxTokens: Int = 200): String
    /** Free conversation: no JSON, a little randomness so it sounds alive. */
    fun chat(prompt: String, maxTokens: Int = 160): String
}

class LocalLlm(private val p: Prefs) : Llm {
    companion object {
        @Volatile private var warned: String? = null

        /** Total RAM in bytes from /proc/meminfo, 0 if unknown. */
        fun totalRam(): Long = runCatching {
            java.io.File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") }
                .filter { it.isDigit() }.toLong() * 1024
        }.getOrDefault(0L)
    }

    /** Loads the model (with progress) and warns once if it is too big for this phone's memory. */
    private fun prepare() {
        val f = java.io.File(p.localModel)
        val ram = totalRam()
        if (ram > 0 && f.length() > ram * 0.4 && warned != p.localModel) {
            warned = p.localModel
            LlamaBridge.onProgress?.invoke(-1, (f.length() shr 20).toInt(), (ram shr 20).toInt(), "")
        }
        LlamaBridge.ensureWatched(p.localModel, p.nCtx, p.effThreads())?.let { throw IllegalStateException(it) }
    }
    override fun gen(prompt: String, grammar: String, maxTokens: Int): String {
        if (p.localModel.isEmpty() || !java.io.File(p.localModel).exists())
            throw IllegalStateException("Модель не скачана. Настройки → Модель → Скачать.")
        prepare()
        val r = LlamaBridge.generate(prompt, maxTokens, 0f, grammar, 150)
        if (!r.ok) throw IllegalStateException(r.text)
        return r.text
    }

    override fun chat(prompt: String, maxTokens: Int): String {
        if (p.localModel.isEmpty() || !java.io.File(p.localModel).exists())
            throw IllegalStateException("Модель не скачана. Настройки → Модель → Скачать.")
        prepare()
        val r = LlamaBridge.generate(prompt, maxTokens, 0.7f, "", 150)
        if (!r.ok) throw IllegalStateException(r.text)
        return r.text
    }
}

/** A bigger model on a PC: `OLLAMA_HOST=0.0.0.0 ollama serve`, phone and PC on the same Wi-Fi. */
class OllamaLlm(private val p: Prefs) : Llm {
    companion object {
        val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES).build()
    }

    override fun gen(prompt: String, grammar: String, maxTokens: Int): String = call(prompt, maxTokens, true)

    override fun chat(prompt: String, maxTokens: Int): String = call(prompt, maxTokens, false)

    private fun call(prompt: String, maxTokens: Int, json: Boolean): String {
        val body = JSONObject().put("model", p.ollamaModel).put("prompt", prompt).put("raw", true)
            .put("stream", false).put("keep_alive", "30m")
            .put("options", JSONObject().put("temperature", if (json) 0.0 else 0.7).put("num_predict", maxTokens).put("num_ctx", 4096)
                .put("stop", org.json.JSONArray().put("<|im_end|>").put("<|im_start|>")))
        if (json) body.put("format", "json")
        val req = Request.Builder().url(p.ollamaUrl + "/api/generate")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                if (!r.isSuccessful) throw IllegalStateException("Ollama ответила ${r.code}: ${txt.take(200)}")
                return JSONObject(txt).optString("response")
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("ПК с Ollama недоступен (${p.ollamaUrl}). Проверь Wi-Fi и OLLAMA_HOST=0.0.0.0")
        }
    }
}
