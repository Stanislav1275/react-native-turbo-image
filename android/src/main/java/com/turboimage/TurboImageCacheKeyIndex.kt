package com.turboimage

import android.content.Context
import android.os.Handler
import android.os.HandlerThread

/**
 * Tracks cache identity keys (cacheKey ?: uri) ever used by TurboImage, since
 * Coil's DiskCache exposes no cheap enumerate-by-prefix API - prefix-based
 * clearing needs its own index to know which keys exist.
 *
 * `register` sits on hot paths: `setSource` runs on the UI thread for every recycled list cell,
 * and `prefetch` calls it once per URL. It used to read the whole persisted StringSet, copy it and
 * rewrite the whole SharedPreferences XML on every call — O(n) work per image where n is every key
 * ever loaded, across sessions, so it got slower the longer the app was used (measured on a
 * Redmi 10 reading a webtoon: dozens of full-file rewrites per second while scrolling).
 *
 * Now the set lives in memory (loaded once), a register is an O(1) add, and persisting is
 * debounced onto a background thread. The set is capped: past MAX_KEYS it is dropped and starts
 * over, which only costs prefix-clearing a few stale disk entries — Coil's own LRU still bounds
 * the disk cache itself.
 */
object TurboImageCacheKeyIndex {
  private const val PREFS_NAME = "TurboImageCacheKeyIndex"
  private const val KEY_SET = "keys"
  private const val PERSIST_DELAY_MS = 2_000L
  private const val MAX_KEYS = 5_000

  private var keys: HashSet<String>? = null
  private var persistScheduled = false

  private val writer: Handler by lazy {
    val thread = HandlerThread("turbo-image-key-index").apply { start() }
    Handler(thread.looper)
  }

  private fun loaded(context: Context): HashSet<String> {
    keys?.let { return it }
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val stored = prefs.getStringSet(KEY_SET, emptySet()) ?: emptySet()
    // Indexes written by older versions were never capped.
    val set = if (stored.size > MAX_KEYS) HashSet() else HashSet(stored)
    keys = set
    return set
  }

  private fun schedulePersist(context: Context) {
    if (persistScheduled) return
    persistScheduled = true
    val appContext = context.applicationContext
    writer.postDelayed({
      val snapshot = synchronized(this) {
        persistScheduled = false
        HashSet(keys ?: emptySet())
      }
      appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_SET, snapshot).apply()
    }, PERSIST_DELAY_MS)
  }

  @Synchronized
  fun register(context: Context, key: String) {
    val set = loaded(context)
    if (!set.add(key)) return
    if (set.size > MAX_KEYS) {
      set.clear()
      set.add(key)
    }
    schedulePersist(context)
  }

  @Synchronized
  fun keys(context: Context, includePrefix: String?, excludePrefix: String?): List<String> {
    return loaded(context).filter { key ->
      val included = includePrefix?.let { key.startsWith(it) } ?: true
      val excluded = excludePrefix?.let { key.startsWith(it) } ?: false
      included && !excluded
    }
  }

  @Synchronized
  fun remove(context: Context, keysToRemove: Collection<String>) {
    if (loaded(context).removeAll(keysToRemove.toSet())) schedulePersist(context)
  }

  @Synchronized
  fun clear(context: Context) {
    keys = HashSet()
    schedulePersist(context)
  }
}
