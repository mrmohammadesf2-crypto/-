package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  private val filePickerLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val results: Array<Uri>? =
            if (result.resultCode == Activity.RESULT_OK) {
              val data = result.data
              if (data?.clipData != null) {
                val clipData = data.clipData!!
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
              } else if (data?.data != null) {
                arrayOf(data.data!!)
              } else {
                null
              }
            } else {
              null
            }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
      }
    }

    NotificationHelper.createNotificationChannel(this)
    AlarmScheduler.rescheduleAll(this)

    val assetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

    webView =
        WebView(this).apply {
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
          }

          isFocusable = true
          isFocusableInTouchMode = true

          addJavascriptInterface(NotificationBridge(this@MainActivity), "AndroidNotifications")
          addJavascriptInterface(
              FirebaseBridge(this@MainActivity, { webView }, lifecycleScope),
              "AndroidFirebase"
          )

          webViewClient =
              object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                  super.onPageStarted(view, url, favicon)
                  injectNotificationBridge(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                  super.onPageFinished(view, url)
                  injectNotificationBridge(view)
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                  return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                  val url = request.url.toString()
                  if (url.startsWith("https://appassets.androidplatform.net/") ||
                      url.startsWith("file:///android_asset/")) {
                    return false
                  }
                  return try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    startActivity(intent)
                    true
                  } catch (e: Exception) {
                    false
                  }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                  if (view != null) {
                    val parent = view.parent as? android.view.ViewGroup
                    parent?.removeView(view)
                    view.destroy()
                  }
                  recreate()
                  return true
                }
              }

          layoutParams =
              android.view.ViewGroup.LayoutParams(
                  android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                  android.view.ViewGroup.LayoutParams.MATCH_PARENT
              )

          webChromeClient =
              object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                  consoleMessage?.let {
                    Log.d("WebViewConsole", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                  }
                  return true
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                  filePathCallback?.onReceiveValue(null)
                  filePathCallback = callback

                  return try {
                    val intent =
                        fileChooserParams?.createIntent()
                            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                              type = "*/*"
                              addCategory(Intent.CATEGORY_OPENABLE)
                            }
                    filePickerLauncher.launch(intent)
                    true
                  } catch (e: Exception) {
                    filePathCallback = null
                    false
                  }
                }
              }

          loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }

    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            if (::webView.isInitialized) {
              webView.evaluateJavascript("typeof window.handleAndroidBack === 'function' ? window.handleAndroidBack() : false") { result ->
                if (result == "true" || result == "\"true\"") {
                  // Handled inside webview (closed modal/sheet or returned to home)
                } else if (webView.canGoBack()) {
                  webView.goBack()
                } else {
                  isEnabled = false
                  onBackPressedDispatcher.onBackPressed()
                }
              }
            } else {
              isEnabled = false
              onBackPressedDispatcher.onBackPressed()
            }
          }
        })

    setContent {
      MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
          AndroidView(
              modifier = Modifier.fillMaxSize(),
              factory = { webView }
          )
        }
      }
    }
  }

  private fun injectNotificationBridge(view: WebView?) {
    val js =
        """
        (function() {
          if (!window.Capacitor) {
            window.Capacitor = { Plugins: {} };
          }
          if (!window.Capacitor.Plugins) {
            window.Capacitor.Plugins = {};
          }
          window.Capacitor.Plugins.LocalNotifications = {
            checkPermissions: function() {
              try {
                return Promise.resolve(JSON.parse(window.AndroidNotifications.checkPermissions()));
              } catch(e) {
                return Promise.resolve({ display: 'granted' });
              }
            },
            requestPermissions: function() {
              try {
                return Promise.resolve(JSON.parse(window.AndroidNotifications.requestPermissions()));
              } catch(e) {
                return Promise.resolve({ display: 'granted' });
              }
            },
            checkExactNotificationSetting: function() {
              try {
                return Promise.resolve(JSON.parse(window.AndroidNotifications.checkExactNotificationSetting()));
              } catch(e) {
                return Promise.resolve({ exact_alarm: 'granted' });
              }
            },
            changeExactNotificationSetting: function() {
              try {
                window.AndroidNotifications.changeExactNotificationSetting();
              } catch(e) {}
              return Promise.resolve();
            },
            getPending: function() {
              try {
                return Promise.resolve(JSON.parse(window.AndroidNotifications.getPending()));
              } catch(e) {
                return Promise.resolve({ notifications: [] });
              }
            },
            cancel: function(options) {
              try {
                window.AndroidNotifications.cancel(JSON.stringify(options || {}));
              } catch(e) {}
              return Promise.resolve();
            },
            schedule: function(options) {
              try {
                if (options && options.notifications) {
                  options.notifications.forEach(function(n) {
                    if (n && n.schedule && n.schedule.at) {
                      if (n.schedule.at instanceof Date) {
                        n.schedule.at = n.schedule.at.getTime();
                      } else if (typeof n.schedule.at === 'string') {
                        var parsed = Date.parse(n.schedule.at);
                        if (!isNaN(parsed)) n.schedule.at = parsed;
                      }
                    }
                  });
                }
                var serialized = JSON.stringify(options);
                window.AndroidNotifications.schedule(serialized);
              } catch(e) {
                console.error("LocalNotifications schedule error:", e);
              }
              return Promise.resolve();
            }
          };
        })();
        """.trimIndent()
    view?.evaluateJavascript(js, null)
  }

  override fun onDestroy() {
    if (::webView.isInitialized) {
      webView.destroy()
    }
    super.onDestroy()
  }
}
