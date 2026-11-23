package com.reconai.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The same HTTP API as app.py in the Termux version, so the interface did not have to change. */
class Server(private val ctx: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
    val cfg = Config(File(ctx.filesDir, "config.json"))
    val models = Models(File(ctx.filesDir, "models"), cfg)
    private val accounts = Accounts(ctx)
    private val agent = Agent(cfg)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val llmQueue = Executors.newSingleThreadExecutor()   // one model in memory, one job at a time
    private val upDir = File(ctx.filesDir, "uploads").apply { mkdirs() }

    private fun json(o: Any, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    private fun err(text: String, status: Response.Status = Response.Status.BAD_REQUEST) = json(JSONObject().put("error", text), status)

    private fun body(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        val buf = ByteArray(len); var off = 0
        while (off < len) { val n = s.inputStream.read(buf, off, len - off); if (n < 0) break; off += n }
        return runCatching { JSONObject(String(buf, 0, off, Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    private fun asset(path: String): Response {
        val name = path.removePrefix("/static/").ifBlank { "index.html" }.replace("..", "")
        return try {
            val bytes = ctx.assets.open("www/$name").use { it.readBytes() }
            val mime = when {
                name.endsWith(".html") -> "text/html; charset=utf-8"
                name.endsWith(".js") -> "application/javascript; charset=utf-8"
                name.endsWith(".json") -> "application/json; charset=utf-8"
                name.endsWith(".png") -> "image/png"
                else -> "application/octet-stream"
            }
            newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "no-cache") }
        } catch (_: Exception) { err("not found", Response.Status.NOT_FOUND) }
    }

    private fun startJob(work: (Job) -> Unit): Response {
        val id = UUID.randomUUID().toString().replace("-", "")
        val job = Job(); jobs[id] = job
        if (jobs.size > 50) jobs.keys.take(jobs.size - 50).forEach { jobs.remove(it) }
        llmQueue.submit { try { work(job) } catch (e: Throwable) { job.answer = "Ошибка: ${e.message}"; job.done = true } }
        return json(JSONObject().put("job", id))
    }

    override fun serve(s: IHTTPSession): Response {
        val uri = s.uri
        val post = s.method == Method.POST
        if (!uri.startsWith("/api/")) return asset(if (uri == "/") "index.html" else uri)

        val token = s.cookies.read("sid")
        val user = accounts.userOf(token)

        when (uri) {
            "/api/register", "/api/login" -> {
                val d = body(s)
                val name = d.optString("name").trim(); val pw = d.optString("password")
                if (uri == "/api/register") accounts.register(name, pw)?.let { return err(it) }
                else if (!accounts.check(name, pw)) return err("Неверный логин или пароль", Response.Status.UNAUTHORIZED)
                val t = accounts.newSession(name)
                return json(JSONObject().put("ok", true).put("user", name)).apply {
                    addHeader("Set-Cookie", "sid=$t; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict")
                }
            }
            "/api/logout" -> { accounts.dropSession(token); return json(JSONObject().put("ok", true)) }
            "/api/me" -> return json(JSONObject().put("user", user ?: JSONObject.NULL)
                .put("model", if (cfg.backend == "ollama") cfg.model else File(cfg.localModel).name))
        }
        if (user == null) return err("auth", Response.Status.UNAUTHORIZED)

        return when {
            uri == "/api/ask" && post -> {
                val text = body(s).optString("text").trim()
                if (text.isEmpty()) err("пусто") else startJob { agent.ask(it, user, text) }
            }
            uri == "/api/recon" && post -> {
                var t = body(s).optString("target").trim().lowercase()
                t = t.removePrefix("https://").removePrefix("http://").substringBefore('/')
                if (t.isEmpty() || ('.' !in t && ':' !in t)) err("укажи домен или IP") else startJob { agent.recon(it, t) }
            }
            uri.startsWith("/api/job/") -> jobs[uri.removePrefix("/api/job/")]?.let { json(it.toJson()) }
                ?: err("нет задачи", Response.Status.NOT_FOUND)
            uri == "/api/stop" -> { LlamaBridge.stop(); json(JSONObject().put("ok", true)) }
            uri == "/api/reset" -> { agent.reset(user); json(JSONObject().put("ok", true)) }
            uri.startsWith("/api/tool/") && post -> {
                val name = uri.removePrefix("/api/tool/")
                val p = Tools.paramOf(name)
                if (p == null || name == "image_exif") err("нет такого инструмента", Response.Status.NOT_FOUND)
                else json(Tools.run(name, JSONObject().put(p, body(s).optString("arg").trim())))
            }
            uri == "/api/photo" && post -> {
                val files = HashMap<String, String>()
                s.parseBody(files)
                val tmp = files["file"] ?: return err("нет файла")
                val orig = s.parameters["file"]?.firstOrNull() ?: "photo.jpg"
                val ext = orig.substringAfterLast('.', "jpg").lowercase().take(5)
                val dest = File(upDir, UUID.randomUUID().toString().replace("-", "") + "." + ext)
                File(tmp).copyTo(dest, overwrite = true)
                val res = Tools.run("image_exif", JSONObject().put("path", dest.absolutePath))
                if (res is JSONObject) res.put("path", dest.absolutePath)
                json(res)
            }
            uri == "/api/settings" && !post -> {
                val j = cfg.toJson()
                if (cfg.backend == "ollama") {
                    val inst = OllamaLlm(cfg).installed()
                    j.put("ollama_status", if (inst.isEmpty()) "не отвечает или нет моделей" else "работает")
                        .put("installed", org.json.JSONArray(inst))
                } else j.put("ollama_status", "не используется").put("installed", org.json.JSONArray())
                json(j.put("models", models.state()))
            }
            uri == "/api/settings" && post -> {
                val d = body(s)
                val before = Triple(cfg.localModel, cfg.numCtx, cfg.threads)
                cfg.apply(d); cfg.save()
                if (before != Triple(cfg.localModel, cfg.numCtx, cfg.threads)) LlamaBridge.unload()  // reload with new settings
                json(JSONObject().put("ok", true))
            }
            uri == "/api/model/download" && post -> models.download(body(s).optString("preset"))?.let { err(it) }
                ?: json(JSONObject().put("ok", true))
            uri == "/api/model/cancel" -> { models.cancel(); json(JSONObject().put("ok", true)) }
            uri == "/api/model/state" -> json(models.state())
            uri == "/api/model/delete" && post -> json(JSONObject().put("ok", models.delete(body(s).optString("path"))))
            else -> err("not found", Response.Status.NOT_FOUND)
        }
    }
}
