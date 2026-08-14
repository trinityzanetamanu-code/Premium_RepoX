package com.Cinemacity

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.utils.Coroutines.main

class CloudflareWebViewDialog : BottomSheetDialogFragment() {
    var onDismissListener: ((Boolean) -> Unit)? = null
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val cfUrl = "https://cinemacity.cc/"
    private var isSuccess = false

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            val cookies = CookieManager.getInstance().getCookie(cfUrl)
            if (cookies != null && cookies.contains("cf_clearance=")) {
                val clearanceMatch = Regex("""cf_clearance=[^;]{15,}""").find(cookies)
                if (clearanceMatch != null) {
                    saveCookiesAndDismiss(cookies, webView.settings.userAgentString)
                    return
                }
            }
            handler.postDelayed(this, 1000) // Polling setiap 1 detik
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.8).toInt()
            )
        }

        titleView = TextView(context).apply {
            text = "Loading challenge page..."
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        layout.addView(titleView)

        webView = WebView(context).apply {
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
                        titleView.text = "Challenge active. Solve any CAPTCHA shown below."
                    } else {
                        titleView.text = "Checking cookies..."
                    }
                    super.onPageFinished(view, url)
                }
            }
        }
        layout.addView(webView)

        return layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView.loadUrl(cfUrl)
        handler.postDelayed(cookiePollRunnable, 2000)
    }

    private fun saveCookiesAndDismiss(cookieStr: String, ua: String) {
        handler.removeCallbacks(cookiePollRunnable)
        isSuccess = true
        CinemacityPlugin.cfCookies = cookieStr
        CinemacityPlugin.cfUserAgent = ua
        CinemacityPlugin.cfCookieHost = cfUrl

        main {
            Toast.makeText(context, "CF Cookies Saved", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView.destroy()
        onDismissListener?.invoke(isSuccess)
        super.onDestroyView()
    }
}
