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

    @Synchronized
    fun generate(prompt: String, maxTokens: Int, temp: Float, grammar: String = ""): Result {
        val b = nativeGenerate(prompt.toByteArray(Charsets.UTF_8), maxTokens, temp, grammar)
        val text = String(b, 1, b.size - 1, Charsets.UTF_8)
        return Result(b[0].toInt() == 1, text)
    }

    fun stop() { if (inited) nativeStop() }

    @Synchronized
    fun unload() { if (inited) { nativeUnload(); loadedPath = null } }

    val isLoaded: Boolean get() = inited && loadedPath != null
}
