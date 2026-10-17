package io.github.davidweiner.lightwiki

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads images once and keeps them on the device (up to [MAX_BYTES], oldest viewed
 * dropped first), so pictures in stored articles can usually be viewed offline too.
 */
class ImageCache(filesDir: File) {
    private val dir = File(filesDir, "images")

    suspend fun load(url: String): ImageBitmap? {
        val file = File(dir, sha1(url))
        val bytes = withContext(Dispatchers.IO) {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
                file.readBytes()
            } else {
                null
            }
        } ?: WikiApi.download(url).also { downloaded -> withContext(Dispatchers.IO) { store(file, downloaded) } }
        return withContext(Dispatchers.Default) { decode(bytes) }
    }

    private fun store(file: File, bytes: ByteArray) {
        dir.mkdirs()
        file.writeBytes(bytes)
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (old in files) {
            if (total <= MAX_BYTES || old == file) continue
            total -= old.length()
            old.delete()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        // Scale down while decoding: the screen is only 1080 pixels wide.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
        const val MAX_DIMENSION = 1600
    }
}
