package com.jarvis.app

import org.json.JSONObject
import java.io.File

/** All settings in one small JSON file. */
class Prefs(private val file: File) {
    var backend = "local"                 // local | ollama
    var localModel = ""
    var threads = 0                        // 0 = auto: only the fast cores (more threads than fast cores makes llama.cpp crawl)
    var nCtx = 2048                        // same as the 4 GB profile of ReconAI
    var ollamaUrl = "http://192.168.1.10:11434"
    var ollamaModel = "huihui_ai/qwen2.5-abliterate:7b"
    var speak = true                       // answer out loud
    var handsSteps = 10                    // max actions in "hands" mode
    var voicePitch = 0.85f                  // lower = deeper, closer to a calm "butler" voice
    var voiceRate = 0.92f          // unhurried, calm
    var voiceName = ""                     // a specific system voice, "" = engine default
    var ttsEngine = ""                     // TTS engine package, "" = system default (RHVoice, Piper etc. if installed)
    var sir = true                         // address the user as "сэр"

    init { load() }

    /** Threads actually used: the user's number, or auto (fast cores, at most 2). */
    fun effThreads(): Int = if (threads > 0) threads else autoThreads

    companion object {
        val autoThreads: Int by lazy {
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            val big = if (freqs.isEmpty()) 2 else { val top = freqs.max(); freqs.count { it >= top * 0.8 } }
            big.coerceIn(1, 2)   // 2 is what fixed the endless chat on a 4 GB phone; more threads made llama.cpp crawl
        }
    }

    fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            backend = o.optString("backend", backend); localModel = o.optString("localModel", localModel)
            threads = o.optInt("threads2", threads); nCtx = o.optInt("nCtx", nCtx)
            ollamaUrl = o.optString("ollamaUrl", ollamaUrl); ollamaModel = o.optString("ollamaModel", ollamaModel)
            speak = o.optBoolean("speak", speak); handsSteps = o.optInt("handsSteps", handsSteps)
            voicePitch = o.optDouble("voicePitch", voicePitch.toDouble()).toFloat(); voiceRate = o.optDouble("voiceRate", voiceRate.toDouble()).toFloat()
            voiceName = o.optString("voiceName", voiceName); ttsEngine = o.optString("ttsEngine", ttsEngine); sir = o.optBoolean("sir", sir)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("backend", backend).put("localModel", localModel).put("threads", threads).put("threads2", threads)
        .put("nCtx", nCtx).put("ollamaUrl", ollamaUrl).put("ollamaModel", ollamaModel).put("speak", speak).put("handsSteps", handsSteps)
        .put("voicePitch", voicePitch.toDouble()).put("voiceRate", voiceRate.toDouble()).put("voiceName", voiceName)
        .put("ttsEngine", ttsEngine).put("sir", sir)

    @Synchronized fun save() { file.parentFile?.mkdirs(); file.writeText(toJson().toString(1)) }

    /** From the settings screen: only known keys, sane limits. */
    fun set(key: String, value: String) {
        when (key) {
            "backend" -> if (value == "local" || value == "ollama") backend = value
            "threads" -> value.toIntOrNull()?.let { threads = it.coerceIn(0, 8) }
            "nCtx" -> value.toIntOrNull()?.let { nCtx = it.coerceIn(512, 8192) }
            "ollamaUrl" -> ollamaUrl = value.trim().trimEnd('/')
            "ollamaModel" -> ollamaModel = value.trim()
            "speak" -> speak = value == "true"
            "handsSteps" -> value.toIntOrNull()?.let { handsSteps = it.coerceIn(3, 25) }
            "localModel" -> localModel = value
            "voicePitch" -> value.toFloatOrNull()?.let { voicePitch = it.coerceIn(0.5f, 1.5f) }
            "voiceRate" -> value.toFloatOrNull()?.let { voiceRate = it.coerceIn(0.6f, 1.6f) }
            "voiceName" -> voiceName = value
            "ttsEngine" -> ttsEngine = value
            "sir" -> sir = value == "true"
        }
        save()
    }
}
