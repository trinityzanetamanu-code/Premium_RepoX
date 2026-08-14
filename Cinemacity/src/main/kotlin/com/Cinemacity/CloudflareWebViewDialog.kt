package com.Cinemacity

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object CloudflareWebViewDialog {
    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: Activity, onDismiss: (Boolean) -> Unit) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
            )
        }

        val titleView = TextView(activity).apply {
            text = "Loading challenge page..."
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        layout.addView(titleView)

        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Menjamin sinkronisasi User-Agent
            settings.userAgentString = settings.userAgentString

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val pageTitle = view?.title ?: ""
                    if (pageTitle.contains("just a moment", true) || pageTitle.contains("challenge", true)) {
                        titleView.text = "Challenge active. Solve CAPTCHA to continue."
                    } else {
                        titleView.text = "Checking cookies..."
                    }
                    super.onPageFinished(view, url)
                }
            }
        }
        layout.addView(webView)

        // Menggunakan AlertDialog bawaan Android murni
        val dialog = AlertDialog.Builder(activity)
            .setView(layout)
            .setCancelable(true)
            .create()

        val handler = Handler(Looper.getMainLooper())
        val cfUrl = "https://cinemacity.cc/"
        var isSuccess = false

        val cookiePollRunnable = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(cfUrl)
                if (cookies != null && cookies.contains("cf_clearance=")) {
                    val clearanceMatch = Regex("""cf_clearance=[^;]{15,}""").find(cookies)
                    if (clearanceMatch != null) {
                        handler.removeCallbacks(this)
                        isSuccess = true
                        CinemacityPlugin.cfCookies = cookies
                        CinemacityPlugin.cfUserAgent = webView.settings.userAgentString
                        CinemacityPlugin.cfCookieHost = cfUrl

                        Toast.makeText(activity, "CF Cookies Saved", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        return
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(cookiePollRunnable)
            webView.destroy()
            onDismiss(isSuccess)
        }

        dialog.show()
        webView.loadUrl(cfUrl)
        handler.postDelayed(cookiePollRunnable, 2000)
    }
}
