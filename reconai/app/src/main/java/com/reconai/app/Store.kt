package com.reconai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Settings: same keys as the Termux version plus the on-device model. */
class Config(private val file: File) {
    var backend = "local"                                   // local = llama.cpp in the APK, ollama = PC or Termux
    var ollama = "http://192.168.1.10:11434"
    var model = "huihui_ai/qwen2.5-abliterate:1.5b"          // Ollama model name
    var localModel = ""                                      // path to the .gguf on the phone
    var numCtx = 2048                                        // tuned for 4 GB RAM
    var toolChars = 1500
    var history = 6
    var maxSteps = 6
    var threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    var temp = 0.0                                           // greedy + repeat penalty: steadier tool calls on 1.5b

    init { if (file.exists()) runCatching { apply(JSONObject(file.readText())) } }

    fun apply(j: JSONObject) {
        backend = j.optString("backend", backend).ifBlank { backend }
        ollama = j.optString("ollama", ollama).trim().trimEnd('/').ifBlank { ollama }
        model = j.optString("model", model).trim().ifBlank { model }
        localModel = j.optString("local_model", localModel)
        numCtx = j.optInt("num_ctx", numCtx).coerceIn(512, 32768)
        toolChars = j.optInt("tool_chars", toolChars).coerceIn(300, 20000)
        history = j.optInt("history", history).coerceIn(2, 40)
        maxSteps = j.optInt("max_steps", maxSteps).coerceIn(1, 20)
        threads = j.optInt("threads", threads).coerceIn(1, 8)
        temp = j.optDouble("temp", temp).coerceIn(0.0, 1.5)
    }

    fun toJson(): JSONObject = JSONObject().put("backend", backend).put("ollama", ollama).put("model", model)
        .put("local_model", localModel).put("num_ctx", numCtx).put("tool_chars", toolChars).put("history", history)
        .put("max_steps", maxSteps).put("threads", threads).put("temp", temp)

    @Synchronized
    fun save() = file.writeText(toJson().toString(2))
}

/** Accounts (PBKDF2) and login sessions in SQLite, like users.db in the Termux version. */
class Accounts(ctx: Context) {
    private val db: SQLiteDatabase = ctx.openOrCreateDatabase("users.db", Context.MODE_PRIVATE, null).also {
        it.execSQL("CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY, salt BLOB, hash BLOB)")
        it.execSQL("CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, name TEXT)")
    }
    private val rnd = SecureRandom()

    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).encoded

    @Synchronized
    fun register(name: String, pw: String): String? {
        if (name.length < 3 || pw.length < 6) return "Логин от 3 символов, пароль от 6"
        db.rawQuery("SELECT 1 FROM users WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) return "Такой логин уже есть" }
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        db.insert("users", null, ContentValues().apply { put("name", name); put("salt", salt); put("hash", hash(pw, salt)) })
        return null
    }

    @Synchronized
    fun check(name: String, pw: String): Boolean {
        db.rawQuery("SELECT salt, hash FROM users WHERE name=?", arrayOf(name)).use {
            if (!it.moveToFirst()) return false
            return MessageDigest.isEqual(hash(pw, it.getBlob(0)), it.getBlob(1))
        }
    }

    @Synchronized
    fun newSession(name: String): String {
        val t = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        db.insert("sessions", null, ContentValues().apply { put("token", t); put("name", name) })
        return t
    }

    @Synchronized
    fun userOf(token: String?): String? {
        if (token.isNullOrBlank()) return null
        db.rawQuery("SELECT name FROM sessions WHERE token=?", arrayOf(token)).use { return if (it.moveToFirst()) it.getString(0) else null }
    }

    @Synchronized
    fun dropSession(token: String?) { if (token != null) db.delete("sessions", "token=?", arrayOf(token)) }
}
