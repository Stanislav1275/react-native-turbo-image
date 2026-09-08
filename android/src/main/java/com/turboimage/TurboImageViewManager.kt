package com.turboimage

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Build.VERSION.SDK_INT
import android.widget.ImageView.ScaleType
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.dispose
import coil.drawable.CrossfadeDrawable
import coil.load
import coil.memory.MemoryCache
import coil.size.Dimension
import coil.size.Size
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import okhttp3.Headers
import com.turboimage.decoder.APNGDecoder
import com.turboimage.events.interceptor.ProgressInterceptor
import okhttp3.OkHttpClient
import androidx.core.graphics.drawable.toDrawable
import java.util.WeakHashMap

class TurboImageViewManager : SimpleViewManager<TurboImageView>(), LifecycleEventListener {
  override fun getName() = REACT_CLASS

  private var isInBackground = false
  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any>? {
    return MapBuilder.of(
      "onStart", MapBuilder.of("registrationName", "onStart"),
      "onProgress", MapBuilder.of("registrationName", "onProgress"),
      "onSuccess", MapBuilder.of("registrationName", "onSuccess"),
      "onFailure", MapBuilder.of("registrationName", "onFailure"),
      "onCompletion", MapBuilder.of("registrationName", "onCompletion"),
    )
  }

  override fun createViewInstance(reactContext: ThemedReactContext): TurboImageView {
    reactContext.addLifecycleEventListener(this)
    val view = TurboImageView(reactContext)
    TurboImageProgressRegistry.register(view)
    return view
  }

  override fun onAfterUpdateTransaction(view: TurboImageView) {
    super.onAfterUpdateTransaction(view)
    reloadImage(view)
  }

  override fun onDropViewInstance(view: TurboImageView) {
    super.onDropViewInstance(view)
    TurboImageProgressRegistry.unregister(view)
    view.dispose()
  }

  /**
   * One ImageLoader per (context, respectCacheHeaders) instead of one per prop commit.
   *
   * `onAfterUpdateTransaction` runs on every commit, and it used to build a fresh OkHttpClient and
   * a fresh ImageLoader there. Each `ImageLoader.build()` creates its own RealImageLoader, which
   * registers its own ConnectivityManager.NetworkCallback and allocates its own bitmap pool, so a
   * list of titles ended up holding one loader per image per re-render. Measured on 1.7.4: 36 live
   * loaders after a single pass through the catalogue, 35 still alive after 60s in the background
   * and 34 after an explicit trim-memory. That is where "used 3169196kB DMA-BUF ... DMA-BUF OOM",
   * the `NetworkCallback was not registered` crashes in SystemCallbacks.onTrimMemory, and the
   * background bitmap-memory pressure all came from — plus a binder call to ConnectivityService on
   * the UI thread per constructed loader, which is the ANR side of it.
   *
   * `cachePolicy` only ever varies `respectCacheHeaders`, so the map holds at most two loaders per
   * context. Contexts are weak keys: a cache of loaders must not be what keeps an activity alive.
   */
  private val imageLoaders = WeakHashMap<Context, MutableMap<Boolean, ImageLoader>>()

  private fun imageLoaderFor(context: Context, respectCacheHeaders: Boolean): ImageLoader =
    imageLoaders
      .getOrPut(context) { mutableMapOf() }
      .getOrPut(respectCacheHeaders) {
        Coil.imageLoader(context).newBuilder()
          .respectCacheHeaders(respectCacheHeaders)
          .okHttpClient(progressOkHttpClient)
          .build()
      }

  private fun reloadImage(view: TurboImageView) {
    val defaultCrossfade = if (view.thumbhashDrawable != null || view.blurhashDrawable != null) {
      0
    } else {
      CrossfadeDrawable.DEFAULT_DURATION
    }

    val imageLoader = imageLoaderFor(view.context, view.cachePolicy == "urlCache")

    view.load(view.uri, imageLoader) {
      view.headers?.let { headers(it) }
      view.cacheKey?.let {
        memoryCacheKey(it)
        diskCacheKey(it)
      }
      view.allowHardware?.let { allowHardware(it) }
      listener(TurboImageListener(view))
      view.format?.let {
        when (it) {
          "svg" -> {
            decoderFactory { result, options, _ ->
              SvgDecoder(result.source, options)
            }
          }

          "gif" -> {
            decoderFactory { result, options, _ ->
              if (SDK_INT >= 28) {
                ImageDecoderDecoder(result.source, options)
              } else {
                GifDecoder(result.source, options)
              }
            }
          }

          "apng" -> {
            decoderFactory { result, _, _ ->
              APNGDecoder(result.source)
            }
          }

          else -> {}
        }
      }

      placeholder(
        view.thumbhashDrawable ?: view.blurhashDrawable ?: view.circleProgressDrawable
      )
      view.memoryCacheKey?.let {
        placeholderMemoryCacheKey(it)
      }
      transformations(view.transformations)
      crossfade(view.crossfade ?: defaultCrossfade)
      view.showPlaceholderOnFailure?.let {
        if (view.memoryCacheKey != null) {
          imageLoader.memoryCache?.get(MemoryCache.Key(view.memoryCacheKey!!))?.let { value ->
            error(value.bitmap.toDrawable(view.context.resources))
          }
        } else {
          error(view.thumbhashDrawable ?: view.blurhashDrawable)
        }
      }
      view.resize?.let { size(it) }
    }
  }

  @ReactProp(name = "source")
  fun setSource(view: TurboImageView, source: ReadableMap) {
    val uri = source.toHashMap()["uri"] as? String
    view.uri = uri
    val headers = source.toHashMap()["headers"] as? HashMap<*, *>
    val headersBuilder = Headers.Builder()
    headers?.map { (key, value) ->
      headersBuilder.add(key as String, value as String)
    }
    view.headers = headersBuilder.build()
    view.cacheKey = source.toHashMap()["cacheKey"] as? String
    uri?.let { TurboImageCacheKeyIndex.register(view.context, view.cacheKey ?: it) }
  }

  @ReactProp(name = "placeholder")
  fun setPlaceholder(view: TurboImageView, placeholder: ReadableMap?) {
    view.blurhash = placeholder?.getString("blurhash")
    view.thumbhash = placeholder?.getString("thumbhash")
    view.memoryCacheKey = placeholder?.getString("memoryCacheKey")
  }

  @ReactProp(name = "showPlaceholderOnFailure")
  fun setShowPlaceholderOnFailure(view: TurboImageView, showPlaceholderOnFailure: Boolean?) {
    view.showPlaceholderOnFailure = showPlaceholderOnFailure
  }

  @ReactProp(name = "cachePolicy")
  fun setCachePolicy(view: TurboImageView, cachePolicy: String?) {
    view.cachePolicy = cachePolicy
  }

  @ReactProp(name = "resizeMode")
  fun setResizeMode(view: TurboImageView, resizeMode: String?) {
    view.scaleType = RESIZE_MODE[resizeMode]
  }

  @ReactProp(name = "indicator")
  fun setIndicator(view: TurboImageView, indicator: ReadableMap?) {
    indicator?.let {
      if (it.hasKey("style")) {
        view.indicator["style"] = it.getString("style") ?: "medium"
      } else {
        view.indicator["style"] = "medium"
      }
      if (it.hasKey("color")) {
        view.indicator["color"] = it.getInt("color")
      }
    }
  }

  @ReactProp(name = "fadeDuration")
  fun setCrossfade(view: TurboImageView, crossfade: Int?) {
    view.crossfade = crossfade
  }

  @ReactProp(name = "rounded")
  fun setRounded(view: TurboImageView, rounded: Boolean?) {
    view.rounded = rounded
  }

  @ReactProp(name = "blur")
  fun setBlur(view: TurboImageView, blur: Int?) {
    view.blur = blur
  }

  @ReactProp(name = "monochrome")
  fun setMonochrome(view: TurboImageView, monochrome: Int?) {
    view.monochrome = monochrome
  }

  @ReactProp(name = "resize")
  fun setResize(view: TurboImageView, resize: Int?) {
    resize?.let {
      view.resize = Size(
        PixelUtil.toPixelFromDIP(resize.toFloat()).toInt(), Dimension.Undefined
      )
    }
  }

  @ReactProp(name = "tint")
  fun setTint(view: TurboImageView, tint: Int?) {
    view.tint = tint
  }

  @ReactProp(name = "allowHardware")
  fun setAllowHardware(view: TurboImageView, allowHardware: Boolean?) {
    view.allowHardware = allowHardware
  }

  @ReactProp(name = "format")
  fun setFormat(view: TurboImageView, format: String?) {
    view.format = format
  }


  companion object {
    private const val REACT_CLASS = "TurboImageView"

    /**
     * The one OkHttpClient every loader shares.
     *
     * It can be shared precisely because the progress interceptor no longer closes over a view:
     * it reports the URL, and [TurboImageProgressRegistry] finds the views showing it. Sharing
     * also means one connection pool and one dispatcher thread pool for all images, instead of
     * one of each per view.
     */
    private val progressOkHttpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .addInterceptor(
          ProgressInterceptor { url, bytesRead, contentLength, _ ->
            TurboImageProgressRegistry.dispatch(url, bytesRead, contentLength)
          }
        )
        .build()
    }
    private val RESIZE_MODE = mapOf(
      "contain" to ScaleType.FIT_CENTER,
      "cover" to ScaleType.CENTER_CROP,
      "stretch" to ScaleType.FIT_XY,
      "center" to ScaleType.CENTER_INSIDE
    )
  }

  /**
   * Lifecycle applies to every live view, not to whichever one happened to be created last.
   *
   * The field this used to hold was overwritten by `createViewInstance` on every mount, so a
   * screen of N images left N-1 views that were never paused and never reloaded: their bitmaps
   * stayed referenced while the app sat in the background — the state Play measures — and the
   * single view that did get disposed was usually one already unmounted. It was also `lateinit`,
   * so a pause before the first image mounted threw UninitializedPropertyAccessException from a
   * lifecycle callback.
   *
   * [TurboImageProgressRegistry] already holds every live view weakly, so it is the list to walk.
   */
  override fun onHostResume() {
    if (!isInBackground) return
    isInBackground = false
    TurboImageProgressRegistry.forEachView { reloadImage(it) }
  }

  override fun onHostPause() {
    isInBackground = true
    TurboImageProgressRegistry.forEachView { view ->
      // `dispose()` cancels the request; the decoded frame stays attached to the view as its
      // drawable, so a screen of reader pages keeps every page it showed. Measured in the
      // background, 60s after HOME: Graphics 214.6 MB — the quantity Play flags at 200 MB.
      // Dropping the drawable is safe because `onHostResume` reloads every registered view.
      view.dispose()
      view.setImageDrawable(null)
    }
    // Disposing the views drops the requests, not the pixels: Coil keeps decoded bitmaps in its
    // MemoryCache, which is shared with the app-wide loader and sized at ~25% of the available
    // heap — hundreds of MB of reader pages on a large device, held for as long as the process
    // lives. Nothing in the background can use them, and the disk cache still has the encoded
    // bytes, so the cost of dropping them is a re-decode on return.
    imageLoaders.values.flatMap { it.values }.forEach { it.memoryCache?.clear() }
  }

  override fun onHostDestroy() {
    TurboImageProgressRegistry.forEachView { it.dispose() }
    // Shutting a loader down is what releases its NetworkCallback and its pools. Without this the
    // registrations survive the activity that caused them, and `dumpsys connectivity` keeps
    // listing them for the process.
    imageLoaders.values.flatMap { it.values }.forEach { it.shutdown() }
    imageLoaders.clear()
  }
}
