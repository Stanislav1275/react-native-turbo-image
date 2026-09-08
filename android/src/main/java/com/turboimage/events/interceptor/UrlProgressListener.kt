package com.turboimage.events.interceptor

/**
 * Progress callback that carries the URL being downloaded.
 *
 * [ProgressListener] deliberately keeps its view-agnostic shape — it is what [ProgressResponseBody]
 * counts bytes against — so this sits one level up, at the interceptor, where the request is still
 * in scope. That is what allows one shared OkHttpClient to serve every view instead of one client
 * per view.
 */
fun interface UrlProgressListener {
  fun update(url: String, bytesRead: Long, contentLength: Long, done: Boolean)
}
