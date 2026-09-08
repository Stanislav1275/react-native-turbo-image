package com.turboimage.events.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Wraps every response body in a byte counter and reports progress together with the URL.
 *
 * Registered with `addInterceptor` (an application interceptor), so `chain.request().url` is the
 * URL as requested, before any redirect — which is the string the view holds and matches on.
 */
class ProgressInterceptor(private val listener: UrlProgressListener) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val url = request.url.toString()
    val originalResponse = chain.proceed(request)
    val body = originalResponse.body ?: return originalResponse
    return originalResponse.newBuilder()
      .body(
        ProgressResponseBody(
          body,
          object : ProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
              listener.update(url, bytesRead, contentLength, done)
            }
          },
        )
      )
      .build()
  }
}
