package com.Cinemacity

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun showCinemacityCFBypassDialogAndWait(): Boolean {
    val activity = ActivityHelper.currentActivity ?: return false
    return suspendCancellableCoroutine { cont ->
        // Pindah ke Main/UI Thread dengan aman menggunakan API standar Android
        Handler(Looper.getMainLooper()).post {
            try {
                CloudflareWebViewDialog.show(activity) { success ->
                    if (cont.isActive) {
                        cont.resume(success)
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
