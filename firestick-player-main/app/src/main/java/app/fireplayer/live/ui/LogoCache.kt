package app.fireplayer.live.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Channel logos for the guide and live controls: fetched and decoded on two background threads, downsampled to the
 * size they're drawn at, kept in a bounded memory cache (6 MB). A logo that fails isn't asked for again this run.
 * [get] never blocks: it returns what's cached and starts a fetch; [onLoaded] runs on the main thread when one lands.
 */
class LogoCache(private val targetPx: Int, var onLoaded: (() -> Unit)? = null) {

    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. */
    fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in pending || url in failed) return null
        if (pending.size > 24) return null // a burst of new rows: the rest wait for the next draw
        pending += url
        pool.execute {
            val bmp = runCatching { fetch(url) }.getOrNull()
            main.post {
                pending -= url
                if (bmp != null) {
                    cache.put(url, bmp)
                    onLoaded?.invoke()
                } else {
                    failed += url
                }
            }
        }
        return null
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    private fun fetch(url: String): Bitmap? {
        if (!url.startsWith("http")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "FirePlayer/2.0 (Linux; Android)")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx / 2) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "FirePlayerLogos").apply { priority = Thread.MIN_PRIORITY } }
    }
}
