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
 * size they're drawn at, kept in a bounded memory cache (6 MB). Each one's plate ([LogoPlate]) is chosen there too,
 * once, never while drawing. A logo that fails isn't asked for again this run. [get] never blocks: it returns what's
 * cached and starts a fetch; [onChange] runs on the main thread when one lands (loaded) or fails.
 */
class LogoCache(private val targetPx: Int, var onChange: ((url: String, loaded: Boolean) -> Unit)? = null) {

    private val cache = object : LruCache<String, Logo>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Logo) = value.bitmap.byteCount
    }
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. */
    fun get(url: String): Logo? {
        cache.get(url)?.let { return it }
        if (url in pending || url in failed) return null
        if (pending.size > 24) return null // a burst of new rows: the rest wait for the next draw
        pending += url
        pool.execute {
            val logo = runCatching { fetch(url) }.getOrNull()
            main.post {
                pending -= url
                if (logo != null) cache.put(url, logo) else failed += url
                onChange?.invoke(url, logo != null)
            }
        }
        return null
    }

    /** True when [url] gives no logo (it failed this run, or isn't a web address). Main thread only. */
    fun missing(url: String): Boolean = url in failed || !url.startsWith("http")

    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    private fun fetch(url: String): Logo? {
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
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            return logoOf(bmp)
        } finally {
            conn.disconnect()
        }
    }

    /** The logo with its plate in each look ([LogoPlate.light]), from a grid of at most about 64 x 64 of its pixels. */
    private fun logoOf(bmp: Bitmap): Logo {
        if (bmp.width <= 0 || bmp.height <= 0) return Logo(bmp, false)
        val step = maxOf(1, maxOf(bmp.width, bmp.height) / 64)
        val cols = (bmp.width + step - 1) / step
        val rows = (bmp.height + step - 1) / step
        val px = IntArray(cols * rows)
        val line = IntArray(bmp.width)
        var n = 0
        for (r in 0 until rows) {
            bmp.getPixels(line, 0, bmp.width, 0, r * step, bmp.width, 1)
            var x = 0
            while (x < bmp.width) { px[n++] = line[x]; x += step }
        }
        return Logo(bmp, LogoPlate.light(px, n), LogoPlate.light(px, n, LogoPlate.DARK_INK_GLASS))
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "FirePlayerLogos").apply { priority = Thread.MIN_PRIORITY } }
    }
}
