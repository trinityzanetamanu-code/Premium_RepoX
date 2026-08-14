package com.Cinemacity

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

object ActivityHelper {
    var currentActivity: FragmentActivity? = null
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
            return h
        }
    }

    override fun load(context: Context) {
        val app = context.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is FragmentActivity) {
                    ActivityHelper.currentActivity = activity
                }
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
        this.settings = CinemacitySettingsFragment::class.java
    }
}
