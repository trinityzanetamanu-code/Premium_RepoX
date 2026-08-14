package com.Cinemacity

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

object ActivityHelper {
    @Volatile
    var currentActivity: Activity? = null
}

@CloudstreamPlugin
class CinemacityPlugin : Plugin() {

    companion object {
        @Volatile var cfCookies: String = ""
        @Volatile var cfUserAgent: String = ""
        @Volatile var cfCookieHost: String = ""

        fun getCfHeaders(): Map<String, String> {
            val h = LinkedHashMap<String, String>()
            if (cfUserAgent.isNotEmpty()) h["User-Agent"] = cfUserAgent
            if (cfCookies.isNotEmpty()) h["Cookie"] = cfCookies
            h["Referer"] = "https://cinemacity.cc/" // WAJIB ada agar Coil lolos dari 403 Image CF
            return h
        }
    }

    override fun load(context: Context) {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                ActivityHelper.currentActivity = ctx
                break
            }
            ctx = ctx.baseContext
        }

        val app = context.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ActivityHelper.currentActivity = activity
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (ActivityHelper.currentActivity == activity) {
                    ActivityHelper.currentActivity = null
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        })

        registerMainAPI(Cinemacity())
    }
}
