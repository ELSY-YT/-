package com.jarvis.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** prompt is already in Qwen chat format; grammar is GBNF (ignored by Ollama, which only gets "format: json"). */
interface Llm { fun gen(prompt: String, grammar: String, maxTokens: Int = 200): String }

class LocalLlm(private val p: Prefs) : Llm {
    override fun gen(prompt: String, grammar: String, maxTokens: Int): String {
        if (p.localModel.isEmpty() || !java.io.File(p.localModel).exists())
            throw IllegalStateException("Модель не скачана. Настройки → Модель → Скачать.")
        LlamaBridge.ensure(p.localModel, p.nCtx, p.threads)?.let { throw IllegalStateException(it) }
        val r = LlamaBridge.generate(prompt, maxTokens, 0f, grammar)
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

    override fun gen(prompt: String, grammar: String, maxTokens: Int): String {
        val body = JSONObject().put("model", p.ollamaModel).put("prompt", prompt).put("raw", true)
            .put("stream", false).put("format", "json").put("keep_alive", "30m")
            .put("options", JSONObject().put("temperature", 0).put("num_predict", maxTokens).put("num_ctx", 4096))
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
