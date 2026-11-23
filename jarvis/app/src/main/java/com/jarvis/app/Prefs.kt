package com.jarvis.app

import org.json.JSONObject
import java.io.File

/** All settings in one small JSON file. */
class Prefs(private val file: File) {
    var backend = "local"                 // local | ollama
    var localModel = ""
    var threads = 4
    var nCtx = 2048                        // same as the 4 GB profile of ReconAI
    var ollamaUrl = "http://192.168.1.10:11434"
    var ollamaModel = "huihui_ai/qwen2.5-abliterate:7b"
    var speak = true                       // answer out loud
    var handsSteps = 10                    // max actions in "hands" mode

    init { load() }

    fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            backend = o.optString("backend", backend); localModel = o.optString("localModel", localModel)
            threads = o.optInt("threads", threads); nCtx = o.optInt("nCtx", nCtx)
            ollamaUrl = o.optString("ollamaUrl", ollamaUrl); ollamaModel = o.optString("ollamaModel", ollamaModel)
            speak = o.optBoolean("speak", speak); handsSteps = o.optInt("handsSteps", handsSteps)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("backend", backend).put("localModel", localModel).put("threads", threads)
        .put("nCtx", nCtx).put("ollamaUrl", ollamaUrl).put("ollamaModel", ollamaModel).put("speak", speak).put("handsSteps", handsSteps)

    @Synchronized fun save() { file.parentFile?.mkdirs(); file.writeText(toJson().toString(1)) }

    /** From the settings screen: only known keys, sane limits. */
    fun set(key: String, value: String) {
        when (key) {
            "backend" -> if (value == "local" || value == "ollama") backend = value
            "threads" -> value.toIntOrNull()?.let { threads = it.coerceIn(1, 8) }
            "nCtx" -> value.toIntOrNull()?.let { nCtx = it.coerceIn(512, 8192) }
            "ollamaUrl" -> ollamaUrl = value.trim().trimEnd('/')
            "ollamaModel" -> ollamaModel = value.trim()
            "speak" -> speak = value == "true"
            "handsSteps" -> value.toIntOrNull()?.let { handsSteps = it.coerceIn(3, 25) }
            "localModel" -> localModel = value
        }
        save()
    }
}
