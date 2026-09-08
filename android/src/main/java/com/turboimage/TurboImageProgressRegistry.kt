package com.turboimage

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.turboimage.events.ProgressEvent
import java.util.Collections
import java.util.WeakHashMap

/**
 * Routes download progress to whichever views are currently showing that URL.
 *
 * Progress used to be delivered by a [ProgressInterceptor] that closed over one specific view,
 * which forced every view to own its own OkHttpClient and therefore its own Coil ImageLoader —
 * and each ImageLoader registers a ConnectivityManager.NetworkCallback and a bitmap pool of its
 * own. A screen full of images ended up with hundreds of them (measured: 36 live loaders after a
 * single pass through the catalogue, still alive after backgrounding and an explicit
 * trim-memory), which is where the DMA-BUF OOM crashes and the background bitmap-memory pressure
 * came from.
 *
 * Decoupling progress from the view is what lets a single client be shared. Matching is by URL
 * rather than by a request tag because Coil folds request headers into its memory-cache key, so
 * tagging a request would fragment the cache. The interceptor that feeds this is an application
 * interceptor, so it reports the pre-redirect URL — the same string the view was handed.
 *
 * Views are held weakly: a registry of images must never be the reason a screen cannot be
 * collected.
 */
internal object TurboImageProgressRegistry {
  private val views = Collections.newSetFromMap(WeakHashMap<TurboImageView, Boolean>())

  fun register(view: TurboImageView) {
    synchronized(views) { views.add(view) }
  }

  fun unregister(view: TurboImageView) {
    synchronized(views) { views.remove(view) }
  }

  /** Snapshot under lock, act outside it — dispatching an event can re-enter arbitrary code. */
  fun forEachView(action: (TurboImageView) -> Unit) {
    val snapshot = synchronized(views) { views.toList() }
    snapshot.forEach(action)
  }

  fun dispatch(url: String, bytesRead: Long, contentLength: Long) {
    val targets = synchronized(views) { views.filter { it.uri == url } }
    if (targets.isEmpty()) return

    targets.forEach { view ->
      val reactContext = view.context as? ReactContext ?: return@forEach
      val dispatcher = UIManagerHelper.getEventDispatcher(reactContext, view.id) ?: return@forEach
      val payload = Arguments.createMap().apply {
        putDouble("completed", bytesRead.toDouble())
        putDouble("total", contentLength.toDouble())
      }
      dispatcher.dispatchEvent(
        ProgressEvent(UIManagerHelper.getSurfaceId(reactContext), view.id, payload)
      )
    }
  }
}
