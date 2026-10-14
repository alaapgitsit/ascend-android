package com.ascend.health

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var webReady = false

    // Health Connect permission launcher
    private val permissionLauncher =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(HealthReader.PERMISSIONS)) {
                runSync()
            } else {
                toastJs("Health access not granted — enable it in Health Connect")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true           // localStorage for Ascend data
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(), "AndroidAscend")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webReady = true
                // auto-sync on open if we already have permission
                autoSyncIfGranted()
            }
        }
        webView.loadUrl("file:///android_asset/ascend.html")
    }

    /** Called from JS: window.AndroidAscend.requestSync() */
    inner class Bridge {
        @JavascriptInterface
        fun requestSync() { runOnUiThread { startPermissionThenSync() } }

        @JavascriptInterface
        fun isNativeAvailable(): Boolean = HealthReader.isAvailable(this@MainActivity)
    }

    private fun startPermissionThenSync() {
        if (!HealthReader.isAvailable(this)) {
            toastJs("Health Connect not installed on this device")
            return
        }
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(HealthReader.PERMISSIONS)) {
                runSync()
            } else {
                permissionLauncher.launch(HealthReader.PERMISSIONS)
            }
        }
    }

    private fun autoSyncIfGranted() {
        if (!HealthReader.isAvailable(this)) return
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(HealthReader.PERMISSIONS)) runSync()
            } catch (_: Exception) {}
        }
    }

    private fun runSync() {
        lifecycleScope.launch {
            try {
                val json = HealthReader.readJson(this@MainActivity, 14)
                injectJson(json)
            } catch (e: Exception) {
                toastJs("Sync failed: ${e.message}")
            }
        }
    }

    private fun injectJson(json: String) {
        if (!webReady) return
        runOnUiThread {
            // json is a valid JS array literal; hand it straight to the web app
            webView.evaluateJavascript(
                "window.__ascendHealthImport && window.__ascendHealthImport($json);",
                null
            )
        }
    }

    private fun toastJs(msg: String) {
        runOnUiThread {
            val safe = msg.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript(
                "window.toast && window.toast('$safe');", null
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
