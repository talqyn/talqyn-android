package com.talqyn.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Loads product images.
 *
 * The SDK ships [TalqynUrlImageLoader]; substitute your own to reuse the app's cache or its
 * image pipeline — Coil, Glide — so a product seen in the catalog is not downloaded twice.
 */
public interface TalqynImageLoader {
    /**
     * Loads an image. Throw on failure; the card then shows its placeholder.
     *
     * @param url The image address, as the catalog carries it.
     */
    public suspend fun load(url: String): ImageBitmap

    /**
     * An image already in memory, or `null`.
     *
     * A card composed again while an answer streams shows a cached image at once instead of
     * fading it in again. Optional: without a cache every image fades in.
     */
    public fun cached(url: String): ImageBitmap? = null
}

/**
 * The default loader: `HttpURLConnection` and an in-memory cache.
 *
 * Images are decoded down to [maxDimension] pixels on their shorter side: a card is a few dozen
 * dp wide, and a catalog photo is often thousands of pixels. The cache is measured in bytes
 * rather than in images — a decoded image takes four bytes a pixel, and a count of images says
 * nothing about the memory they take.
 *
 * @param maxBytes How much memory the cached images may take. By default an eighth of the heap
 *   the app may grow to, and no more than 64 MB.
 * @param maxDimension The shorter side an image is decoded down to, in pixels.
 */
public class TalqynUrlImageLoader(
    maxBytes: Int = defaultMaxBytes(),
    private val maxDimension: Int = 720,
) : TalqynImageLoader {
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    override fun cached(url: String): ImageBitmap? = cache.get(url)

    override suspend fun load(url: String): ImageBitmap {
        cache.get(url)?.let { return it }
        val bytes = download(url)
        val image = withContext(Dispatchers.Default) { decode(bytes) } ?: throw IOException("could not decode the image at $url")
        return image.also { cache.put(url, it) }
    }

    /** Drops every cached image — for an app that frees memory when the system asks it to (`onTrimMemory`). */
    public fun clear() {
        cache.evictAll()
    }

    /**
     * A cancelled card disconnects its download rather than reading it to the end, and a card
     * cancelled before its download started never opens the connection.
     */
    private suspend fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        val cancelled = AtomicBoolean(false)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancelled.set(true)
                connection.disconnect()
            }
            Dispatchers.IO.asExecutor().execute {
                if (cancelled.get()) return@execute
                continuation.resumeWith(
                    runCatching {
                        try {
                            connection.connect()
                            // `disconnect` before a connection exists does nothing: a cancellation
                            // that came in while connecting is caught here rather than by the read.
                            if (cancelled.get()) throw IOException("the image at $url is no longer wanted")
                            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
                            connection.inputStream.use { it.readBytes() }
                        } finally {
                            connection.disconnect()
                        }
                    },
                )
            }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val shorter = min(sampled.width, sampled.height)
        val bitmap = if (maxDimension <= 0 || shorter <= maxDimension) {
            sampled
        } else {
            // A power-of-two step leaves the shorter side anywhere up to twice the target, and the
            // cache pays for every pixel of it: the rest of the way is a scale.
            val scale = maxDimension.toFloat() / shorter
            val width = (sampled.width * scale).roundToInt().coerceAtLeast(1)
            val height = (sampled.height * scale).roundToInt().coerceAtLeast(1)
            sampled.scale(width, height).also { if (it !== sampled) sampled.recycle() }
        }
        // The pixels go to the GPU here, off the main thread, rather than on the first frame that draws them.
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap()
    }

    public companion object {
        private const val TIMEOUT_MILLIS = 15_000
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024

        /** One loader for the process, so every screen shares its cache. The screens' default. */
        @JvmStatic
        public val Shared: TalqynUrlImageLoader by lazy { TalqynUrlImageLoader() }

        private fun defaultMaxBytes(): Int = min(Runtime.getRuntime().maxMemory() / 8, MAX_CACHE_BYTES).toInt()
    }
}

/** The largest power-of-two step the decoder may take and still leave the shorter side at [maxDimension] or more. */
internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (maxDimension <= 0) return 1
    val shorter = min(width, height)
    var sample = 1
    while (shorter / (sample * 2) >= maxDimension) sample *= 2
    return sample
}
