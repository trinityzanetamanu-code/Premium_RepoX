package com.Cinemacity

import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class CinemacitySettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val autoBypassPref = SwitchPreferenceCompat(context).apply {
            key = "cinemacity_cf_bypass_auto"
            title = "Auto Bypass Cloudflare"
            summary = "Automatically show Cloudflare dialog when blocked"
            setDefaultValue(true)
        }

        val bypassPref = Preference(context).apply {
            key = "cinemacity_cf_bypass"
            title = "Cloudflare Bypass"
            summary = "Solve any CAPTCHA shown below. The dialog will close automatically once done."
            setOnPreferenceClickListener {
                val activity = ActivityHelper.currentActivity
                if (activity != null) {
                    CloudflareWebViewDialog().show(activity.supportFragmentManager, "CF_Dialog")
                } else {
                    Toast.makeText(context, "Cannot open Dialog: Activity not found", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        val clearPref = Preference(context).apply {
            key = "cf_clear_btn"
            title = "Clear CF Cookies?"
            summary = "This will remove the saved Cloudflare cookies and User-Agent. You will need to bypass Cloudflare again before streaming."
            setOnPreferenceClickListener {
                CinemacityPlugin.cfCookies = ""
                CinemacityPlugin.cfUserAgent = ""
                CinemacityPlugin.cfCookieHost = ""
                CookieManager.getInstance().removeAllCookies(null)
                Toast.makeText(context, "CF Cookies cleared", Toast.LENGTH_SHORT).show()
                true
            }
        }

        screen.addPreference(autoBypassPref)
        screen.addPreference(bypassPref)
        screen.addPreference(clearPref)
        preferenceScreen = screen
    }
}
