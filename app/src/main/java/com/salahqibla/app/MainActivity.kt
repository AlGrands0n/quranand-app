package com.salahqibla.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var adView: AdView

    // The system splash stays up until the page has rendered (or after a short timeout).
    @Volatile private var pageReady = false
    private val ui = Handler(Looper.getMainLooper())

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // Serves bundled assets and downloaded audio from https://appassets.androidplatform.net/
    // so localStorage, IndexedDB and media playback work with a proper origin.
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/audio/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "audio")))
            .build()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifyWebPermissionChanged() }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    // Back button: let the web app close sheets, dialogs and the Quran reader first.
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
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The system splash stays until the page is ready, then the page fades in over it.
        splash.setKeepOnScreenCondition { !pageReady }
        ui.postDelayed({ revealPage() }, 3_000)

        PrayerScheduler.ensureChannels(this)
        webView = findViewById(R.id.webview)
        adView = findViewById(R.id.adView)
        webView.setBackgroundColor(0xFF081B19.toInt())
        webView.alpha = 0f
        PlaybackControl.requestPause = { webView.evaluateJavascript("window.__qpause&&window.__qpause()", null) }

        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.addJavascriptInterface(NativeBridge(this, webView), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(NATIVE_JS) { revealPage() }
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
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        // Keep the unlock bubble alive if the user turned it on earlier.
        if (PrayerScheduler.bubbleEnabled(this) && android.provider.Settings.canDrawOverlays(this)) {
            try { UnlockService.start(this) } catch (_: Exception) { }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    /** Called by the bridge when the page asks for notification permission (Android 13+). */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !PrayerScheduler.notificationsGranted(this)) {
            ui.post { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else {
            notifyWebPermissionChanged()
        }
    }

    private fun notifyWebPermissionChanged() {
        ui.post { webView.evaluateJavascript("window.__onPerm&&window.__onPerm()", null) }
    }

    /** Fades the page in once. The system splash hands off at the same moment. */
    private fun revealPage() {
        if (pageReady) return
        pageReady = true
        webView.animate().alpha(1f).setDuration(260).start()
    }

    override fun onPause() {
        // Keep the WebView running while a recitation plays in the background (if the user enabled it).
        if (!(PlaybackControl.playing && PrayerScheduler.bgPlayEnabled(this))) webView.onPause()
        adView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        adView.resume()
        // The user may have just granted notifications or the overlay permission.
        notifyWebPermissionChanged()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        PlaybackControl.requestPause = null
        adView.destroy()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Runs after each page load: hides the web placeholder banner (the real AdMob banner is
        // native, below the WebView) and defines the handler used by the back button.
        private const val NATIVE_JS = """
            (function () {
              document.documentElement.classList.add('native');
              if (!document.getElementById('native-css')) {
                var s = document.createElement('style');
                s.id = 'native-css';
                s.textContent = '.native .banner{display:none!important}';
                document.head.appendChild(s);
              }
              window.__qpause = function () {
                if (typeof QX !== 'undefined' && QX.el && !QX.el.paused) QX.el.pause();
              };
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
