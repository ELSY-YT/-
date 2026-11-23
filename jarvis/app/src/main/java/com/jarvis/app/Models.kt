package com.jarvis.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Downloads GGUF models into the app's storage, or imports one the user already has. */
class Models(private val dir: File, private val cfg: Prefs) {
    data class Preset(val id: String, val title: String, val url: String)

    companion object {
        private const val HF = "https://huggingface.co/mradermacher"
        // huihui-ai abliterated Qwen2.5, Q4_K_M; the same family as ReconAI
        val PRESETS = listOf(
            Preset("phone", "Умнее: 1.5b (~990 МБ, 4 ГБ ОЗУ)",
                "$HF/Qwen2.5-1.5B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-abliterated.Q4_K_M.gguf"),
            Preset("tiny", "Быстрее: 0.5b (~400 МБ)",
                "$HF/Qwen2.5-0.5B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-abliterated.Q4_K_M.gguf"),
        )
    }

    @Volatile var busy = false; private set
    @Volatile var status = ""; private set
    @Volatile var done = 0L; private set
    @Volatile var total = 0L; private set
    @Volatile private var cancel = false

    init { dir.mkdirs() }

    fun list(): JSONArray = JSONArray((dir.listFiles { f -> f.name.endsWith(".gguf") } ?: emptyArray())
        .sortedBy { it.name }.map { JSONObject().put("path", it.absolutePath).put("name", it.name).put("mb", it.length() / 1048576) })

    fun state(): JSONObject = JSONObject().put("busy", busy).put("status", status).put("done", done).put("total", total)
        .put("files", list()).put("current", cfg.localModel).put("loaded", LlamaBridge.isLoaded)
        .put("presets", JSONArray(PRESETS.map { JSONObject().put("id", it.id).put("title", it.title) })).put("free_mb", dir.usableSpace / 1048576)

    fun cancel() { cancel = true }

    fun download(presetId: String): String? {
        val p = PRESETS.firstOrNull { it.id == presetId } ?: return "нет такого пресета"
        if (busy) return "уже идёт загрузка"
        busy = true; cancel = false; status = "Подключаюсь…"; done = 0; total = 0
        Thread {
            val name = p.url.substringAfterLast('/')
            val part = File(dir, "$name.part"); val dest = File(dir, name)
            try {
                val client = OllamaLlm.http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
                val have = if (part.exists()) part.length() else 0L
                val req = Request.Builder().url(p.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    val append = r.code == 206
                    done = if (append) have else 0
                    total = done + (r.body?.contentLength() ?: 0)
                    status = "Скачиваю ${p.title}"
                    FileOutputStream(part, append).use { out ->
                        val inp = r.body!!.byteStream(); val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancel) throw Exception("отменено (докачается при следующем нажатии)")
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                        }
                    }
                }
                if (total > 0 && part.length() < total) throw Exception("файл скачался не полностью, нажмите ещё раз — докачается")
                part.renameTo(dest)
                LlamaBridge.unload()
                cfg.localModel = dest.absolutePath; cfg.backend = "local"; cfg.save()
                status = "Готово: $name"
            } catch (e: Exception) {
                status = "Ошибка загрузки: ${e.message}"
            } finally { busy = false }
        }.start()
        return null
    }

    /** Copies a .gguf picked in the file manager into app storage (llama.cpp needs a real file path). */
    fun import(cr: ContentResolver, uri: Uri) {
        if (busy) return
        busy = true; cancel = false; done = 0; total = 0; status = "Копирую файл…"
        Thread {
            try {
                var name = "model.gguf"
                cr.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { total = c.getLong(it) }
                    }
                }
                if (!name.endsWith(".gguf", true)) throw Exception("нужен файл .gguf")
                val dest = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                cr.openInputStream(uri)!!.use { inp ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { if (cancel) throw Exception("отменено"); val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n }
                    }
                }
                LlamaBridge.unload()
                cfg.localModel = dest.absolutePath; cfg.backend = "local"; cfg.save()
                status = "Готово: ${dest.name}"
            } catch (e: Exception) { status = "Ошибка импорта: ${e.message}" } finally { busy = false }
        }.start()
    }

    fun delete(path: String): Boolean {
        val f = File(path)
        if (f.parentFile?.absolutePath != dir.absolutePath) return false
        if (cfg.localModel == f.absolutePath) { LlamaBridge.unload(); cfg.localModel = ""; cfg.save() }
        return f.delete()
    }
}
