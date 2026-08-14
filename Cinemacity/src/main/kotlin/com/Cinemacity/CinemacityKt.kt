package com.Cinemacity

import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun showCinemacityCFBypassDialogAndWait(): Boolean {
    val activity = ActivityHelper.currentActivity ?: return false
    return suspendCancellableCoroutine { cont ->
        main {
            try {
                val dialog = CloudflareWebViewDialog()
                dialog.onDismissListener = { success ->
                    if (cont.isActive) {
                        cont.resume(success)
                    }
                }
                dialog.show(activity.supportFragmentManager, "CF_Dialog")
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
