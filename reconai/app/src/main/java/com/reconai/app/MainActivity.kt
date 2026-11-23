package com.reconai.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    companion object {
        private var server: Server? = null
        private const val PICK_FILE = 1
        private const val PICK_MODEL = 2
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var port = 8766

    private fun startServer() {
        if (server != null) { port = server!!.listeningPort; return }
        LlamaBridge.init(applicationInfo.nativeLibraryDir)
        // 8765 may be taken by the Termux version, so the APK starts from 8766
        for (p in 8766..8776) {
            try { server = Server(applicationContext, p).also { it.start(NanoHttpdTimeout, false) }; port = p; return }
            catch (_: java.io.IOException) {}
        }
        throw IllegalStateException("нет свободного порта")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0d1117")
        window.navigationBarColor = Color.parseColor("#161b22")
        startServer()

        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(Color.parseColor("#0d1117"))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "127.0.0.1") return false
                // every outside link (Shodan, maps, search results) opens in the normal browser
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { startActivityForResult(params.createIntent(), PICK_FILE); true }
                catch (_: Exception) { fileCallback = null; false }
            }
        }
        web.loadUrl("http://127.0.0.1:$port/")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            PICK_MODEL -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) server?.models?.import(contentResolver, uri)
            }
        }
    }

    inner class Bridge {
        /** Called from the Settings tab: pick a .gguf in the file manager. */
        @JavascriptInterface
        fun pickModel() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(i, PICK_MODEL)
        }
    }
}

private const val NanoHttpdTimeout = 5 * 60 * 1000
