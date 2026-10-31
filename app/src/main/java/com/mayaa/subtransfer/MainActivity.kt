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
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Hosts the bundled index.html in a WebView.
 *
 * Two ways to subscribe on the target account:
 *  1. Reddit API (OAuth): login happens in the system browser and redirects to
 *     subtransfer://auth#access_token=... (needs a Reddit "installed app" client ID).
 *  2. No Reddit app: log in to old.reddit.com inside an in-app WebView, then the app runs the
 *     subscribe calls from that logged-in page using the normal browser session.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var reddit: WebView? = null
    private var redditView: LinearLayout? = null
    private var statusText: TextView? = null
    private var pendingNames: String = "[]"
    private var startAfterLoad = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private companion object {
        const val BASE = "https://appassets.androidplatform.net/assets/index.html"
        const val REQ_FILE = 1
        const val BG = "#0e1113"
        const val OLD = "old.reddit.com"

        const val SCRIPT = """
(async()=>{
const names=__NAMES__;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let me;try{me=(await (await fetch('/api/me.json',{credentials:'include'})).json()).data||{}}catch(e){me={}}
if(!me.name){RedditBridge.notLoggedIn();return}
let uh=me.modhash||'';
if(!uh){try{const t=await (await fetch('/',{credentials:'include'})).text();const m=t.match(/modhash["']?\s*[:=]\s*["']([a-z0-9]+)["']/i);if(m)uh=m[1]}catch(e){}}
async function sub(list){
  for(let a=0;a<4;a++){
    try{
      const r=await fetch('/api/subscribe',{method:'POST',credentials:'include',
        headers:{'Content-Type':'application/x-www-form-urlencoded','X-Modhash':uh},
        body:new URLSearchParams({action:'sub',sr_name:list.join(','),uh:uh,api_type:'json',skip_initial_defaults:'true'})});
      if(r.status===429){await sleep((+r.headers.get('retry-after')||30)*1000+500);continue}
      if(!r.ok)return false;
      try{const j=await r.json();if(j&&j.json&&j.json.errors&&j.json.errors.length)return false}catch(e){}
      return true
    }catch(e){return false}
  }
  return false
}
const ok=[],fail=[];
for(let i=0;i<names.length;i+=10){
  const b=names.slice(i,i+10);
  if(await sub(b))ok.push(...b);
  else for(const n of b){ if(await sub([n]))ok.push(n); else fail.push(n); }
  RedditBridge.progress(Math.min(i+10,names.length),names.length);
  await sleep(700)
}
RedditBridge.finished(me.name,JSON.stringify(ok),JSON.stringify(fail))
})()
"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor(BG)
        window.navigationBarColor = Color.parseColor(BG)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor(BG))
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
        showMain()
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

    // ---------------- No-Reddit-app mode ----------------

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun showMain() {
        if (redditView != null) {
            setContentView(web)
            reddit?.destroy()
            reddit = null
            redditView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startRedditLogin(namesJson: String) {
        pendingNames = namesJson
        startAfterLoad = false

        val rw = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(RedditBridge(), "RedditBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (startAfterLoad && Uri.parse(url).host == OLD) {
                        startAfterLoad = false
                        runTransfer()
                    }
                }
            }
        }
        reddit = rw

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Log in to the target account, then tap Start"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        statusText = status

        val back = Button(this).apply {
            text = "Back"
            setOnClickListener { showMain() }
        }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener {
                val onOld = Uri.parse(rw.url).host == OLD
                if (onOld) runTransfer() else {
                    startAfterLoad = true
                    rw.loadUrl("https://$OLD/")
                }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1a1d21"))
            addView(back)
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(start)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
            addView(rw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        redditView = root
        setContentView(root)
        rw.loadUrl("https://$OLD/login")
    }

    private fun runTransfer() {
        statusText?.text = "Working… keep this screen open"
        reddit?.evaluateJavascript(SCRIPT.replace("__NAMES__", pendingNames), null)
    }

    inner class RedditBridge {
        @JavascriptInterface
        fun notLoggedIn() = runOnUiThread {
            statusText?.text = "Not logged in yet — log in, then tap Start"
            Toast.makeText(this@MainActivity, "Log in first, then tap Start", Toast.LENGTH_LONG).show()
        }

        @JavascriptInterface
        fun progress(done: Int, total: Int) = runOnUiThread {
            statusText?.text = "Subscribed $done / $total"
        }

        @JavascriptInterface
        fun finished(user: String, okJson: String, failJson: String) = runOnUiThread {
            showMain()
            web.evaluateJavascript(
                "window.onCookieResult(${JSONObject.quote(user)}, $okJson, $failJson)", null
            )
        }
    }

    // ---------------- Plumbing ----------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val result = if (resultCode == RESULT_OK && data != null)
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
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
        val rw = reddit
        when {
            rw != null && rw.canGoBack() -> rw.goBack()
            rw != null -> showMain()
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
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

        /** Opens the in-app Reddit login and subscribes the given names (JSON array). */
        @JavascriptInterface
        fun cookieTransfer(namesJson: String) {
            runOnUiThread { startRedditLogin(namesJson) }
        }
    }
}
