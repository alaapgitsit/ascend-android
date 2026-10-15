package com.ascend.health

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var webReady = false

    // Holds the web page's camera request while we ask for the Android runtime permission.
    private var pendingCameraRequest: PermissionRequest? = null
    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingCameraRequest
            pendingCameraRequest = null
            if (req != null) runOnUiThread {
                if (granted) req.grant(req.resources) else req.deny()
            }
            if (!granted) toastJs("Camera permission is needed to scan barcodes")
        }

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
        webView.setBackgroundColor(Color.parseColor("#f3efe6"))
        setContentView(webView)

        // Dark status/nav-bar icons to suit the light paper theme.
        WindowInsetsControllerCompat(window, webView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true           // localStorage for Ascend data
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(), "AndroidAscend")
        // Grant the barcode scanner access to the camera.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = request.resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }
                if (!wantsCamera) { runOnUiThread { request.deny() }; return }
                runOnUiThread {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(request.resources)
                    } else {
                        pendingCameraRequest = request
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webReady = true
                autoSyncIfGranted()
            }
        }
        webView.loadUrl("file:///android_asset/ascend.html")
    }

    override fun onResume() {
        super.onResume()
        // Always land on the Today tab when the app is reopened.
        if (webReady) webView.evaluateJavascript("window.__ascendForceHome && window.__ascendForceHome();", null)
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

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (!webReady) { moveTaskToBack(true); return }
        // Ask the web app to handle back (close a sheet, or jump to Today).
        webView.evaluateJavascript("(window.__ascendBack && window.__ascendBack())===true") { result ->
            if (result == "true") {
                // handled inside the app
            } else {
                // nothing to close and already on Today → minimise instead of exiting
                moveTaskToBack(true)
            }
        }
    }
}
