package com.salahqibla.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var adView: AdView

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // Serves the bundled app from https://appassets.androidplatform.net/assets/...
    // so localStorage and IndexedDB work reliably (file:// origins do not).
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    // Back button: first let the web app close sheets, modals and the Quran reader.
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            webView.evaluateJavascript("(window.__androidBack && window.__androidBack()) === true") { handled ->
                when {
                    handled == "true" -> Unit
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        adView = findViewById(R.id.adView)

        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lets adhan/ayah audio start without a tap on every verse.
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(NATIVE_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val hasLocation =
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                if (hasLocation) {
                    callback?.invoke(origin, true, false)
                    return
                }
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onPause() {
        webView.onPause()
        adView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        adView.resume()
    }

    override fun onDestroy() {
        adView.destroy()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Runs after the page loads: hides the web placeholder banner (the real
        // AdMob banner is native, below the WebView) and defines the back handler.
        private const val NATIVE_JS = """
            (function () {
              document.documentElement.classList.add('native');
              if (!document.getElementById('native-css')) {
                var s = document.createElement('style');
                s.id = 'native-css';
                s.textContent = '.native .banner{display:none!important}';
                document.head.appendChild(s);
              }
              window.__androidBack = function () {
                if (document.querySelector('.sheet')) { act('closeSheet'); return true; }
                if (document.querySelector('.mwrap')) { act('close'); return true; }
                if (typeof QX !== 'undefined' && QX.open != null) { act('qback'); return true; }
                if (typeof S !== 'undefined' && S.tab !== 'home') { act('tab:home'); return true; }
                return false;
              };
            })();
        """
    }
}
