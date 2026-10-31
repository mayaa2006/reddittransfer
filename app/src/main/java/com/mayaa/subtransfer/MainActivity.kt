package com.mayaa.subtransfer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the bundled index.html in a WebView.
 *
 * Reddit login happens in the system browser (so Google/passkey logins and the browser's
 * existing session work). Reddit redirects to subtransfer://auth#access_token=..., which
 * Android routes back here; we hand the fragment to the page, which stores the token.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private companion object {
        const val BASE = "https://appassets.androidplatform.net/assets/index.html"
        const val REQ_FILE = 1
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0e1113")
        window.navigationBarColor = Color.parseColor("#0e1113")

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0e1113"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    if (request.url.host == "appassets.androidplatform.net") return false
                    openInBrowser(request.url)
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        startActivityForResult(params.createIntent(), REQ_FILE)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
        }
        setContentView(web)

        if (!handleAuthIntent(intent)) web.loadUrl(BASE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    /** Returns true if the intent carried an OAuth redirect and the page was reloaded with it. */
    private fun handleAuthIntent(i: Intent?): Boolean {
        val d = i?.data ?: return false
        if (d.scheme != "subtransfer") return false
        // The query changes so this is always a full reload, even if the page is already open.
        web.loadUrl("$BASE?t=${System.currentTimeMillis()}#${d.encodedFragment ?: ""}")
        return true
    }

    /** Force the default browser (not the Reddit app, which may claim reddit.com links). */
    private fun openInBrowser(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            i.selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val result = if (resultCode == RESULT_OK && data != null)
                android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    inner class Bridge {
        /** Saves a text file to the Downloads folder. */
        @JavascriptInterface
        fun saveText(name: String, text: String) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val msg = if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Saved to Downloads/$name"
            } else "Could not save file"
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
