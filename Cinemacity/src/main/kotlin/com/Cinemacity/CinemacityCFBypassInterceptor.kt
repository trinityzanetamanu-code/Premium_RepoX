package com.Cinemacity

import okhttp3.Interceptor
import okhttp3.Response

object CinemacityCFBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        builder.removeHeader("X-Requested-With")
        builder.header("sec-ch-ua-mobile", "?1")
        builder.header("sec-ch-ua-platform", "\"Android\"")

        val ua = CinemacityPlugin.cfUserAgent
        if (ua.isNotEmpty()) builder.header("User-Agent", ua)

        val cookies = CinemacityPlugin.cfCookies
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies)

        return chain.proceed(builder.build())
    }
}
