package com.jarvis.app

/** On-device llama.cpp. One model, one generation at a time. */
object LlamaBridge {
    private var inited = false
    private var loadedPath: String? = null
    private var loadedCtx = 0
    private var loadedThreads = 0

    private external fun nativeInit(libDir: String)
    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): ByteArray
    private external fun nativeLoaded(): Boolean
    private external fun nativeGenerate(prompt: ByteArray, maxTokens: Int, temp: Float, grammar: String): ByteArray
    private external fun nativeStop()
    private external fun nativeUnload()
    private external fun nativeProgress(): ByteArray

    /** phase (1 loading, 2 reading prompt, 3 writing), done, total, text so far. Called from a helper thread. */
    @Volatile var onProgress: ((Int, Int, Int, String) -> Unit)? = null

    @Synchronized
    fun init(nativeLibDir: String) {
        if (inited) return
        System.loadLibrary("jarvis")
        nativeInit(nativeLibDir)
        inited = true
    }

    /** Loads the model if it is not loaded yet or the settings changed. Returns null or an error text. */
    @Synchronized
    fun ensure(path: String, nCtx: Int, nThreads: Int): String? {
        if (nativeLoaded() && loadedPath == path && loadedCtx == nCtx && loadedThreads == nThreads) return null
        val err = String(nativeLoad(path, nCtx, nThreads), Charsets.UTF_8)
        if (err.isNotEmpty()) { loadedPath = null; return err }
        loadedPath = path; loadedCtx = nCtx; loadedThreads = nThreads
        return null
    }

    class Result(val ok: Boolean, val text: String)

    /** Polls the engine every 0.6 s for the screen and stops it after timeoutSec, so nothing hangs forever. */
    private class Watch(val timeoutSec: Int) : Thread("llm-watch") {
        @Volatile var done = false
        @Volatile var timedOut = false
        init { isDaemon = true }
        override fun run() {
            val t0 = System.currentTimeMillis()
            while (!done) {
                runCatching {
                    val s = String(nativeProgress(), Charsets.UTF_8)
                    val f = s.split('|', limit = 4)
                    if (f.size == 4) onProgress?.invoke(f[0].toIntOrNull() ?: 0, f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3])
                }
                if (timeoutSec > 0 && !timedOut && System.currentTimeMillis() - t0 > timeoutSec * 1000L) { timedOut = true; nativeStop() }
                try { sleep(600) } catch (e: InterruptedException) { return }
            }
        }
    }

    /** Loads the model with progress on screen. */
    fun ensureWatched(path: String, nCtx: Int, nThreads: Int): String? {
        val w = Watch(0); w.start()
        try { return ensure(path, nCtx, nThreads) } finally { w.done = true; w.interrupt() }
    }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int, temp: Float, grammar: String = "", timeoutSec: Int = 0): Result {
        val w = Watch(timeoutSec); w.start()
        val b = try { nativeGenerate(prompt.toByteArray(Charsets.UTF_8), maxTokens, temp, grammar) } finally { w.done = true; w.interrupt() }
        val text = String(b, 1, b.size - 1, Charsets.UTF_8)
        val ok = b[0].toInt() == 1
        if (w.timedOut) {
            return if (ok && text.isNotBlank()) Result(true, text)
            else Result(false, "Модель не успела ответить за $timeoutSec с. Телефону тяжело: выберите модель поменьше (Настройки → Модель → 1.5b) или уменьшите n_ctx до 1024.")
        }
        return Result(ok, text)
    }

    fun stop() { if (inited) nativeStop() }

    @Synchronized
    fun unload() { if (inited) { nativeUnload(); loadedPath = null } }

    val isLoaded: Boolean get() = inited && loadedPath != null
}
