package `is`.xyz.mpv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import java.io.File
import java.net.URLConnection
import java.util.Locale

/**
 * Lightweight media-orientation probe used before mpv produces its first frame.
 *
 * It deliberately handles only local files and content URIs. Network streams and synthetic mpv
 * URLs are left to mpv's normal video-parameter callbacks, because probing them here could block
 * activity launch indefinitely.
 */
internal object MediaOrientationResolver {
    enum class Orientation {
        LANDSCAPE,
        PORTRAIT,
        SQUARE,
        UNKNOWN,
    }

    fun canResolve(path: String): Boolean = isProbeablePath(path)

    fun resolve(context: Context, path: String): Orientation {
        if (!canResolve(path))
            return Orientation.UNKNOWN

        val mimeType = detectMimeType(context, path)
        return when {
            mimeType?.startsWith("image/") == true -> {
                resolveImage(context, path).takeIf { it != Orientation.UNKNOWN }
                    ?: resolveVideo(context, path)
            }

            mimeType?.startsWith("video/") == true -> {
                resolveVideo(context, path).takeIf { it != Orientation.UNKNOWN }
                    ?: resolveImage(context, path)
            }

            else -> {
                // Unknown/document-provider MIME types are common. BitmapFactory rejects video
                // headers quickly, while MediaMetadataRetriever can be much slower on an image,
                // so try the lightweight image-bounds path first.
                resolveImage(context, path).takeIf { it != Orientation.UNKNOWN }
                    ?: resolveVideo(context, path)
            }
        }
    }

    fun classify(
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
        squareThreshold: Float = 1.2f,
    ): Orientation {
        if (width <= 0 || height <= 0)
            return Orientation.UNKNOWN

        val rotation = ((rotationDegrees % 360) + 360) % 360
        val (displayWidth, displayHeight) = if (rotation == 90 || rotation == 270)
            height to width
        else
            width to height

        val ratio = displayWidth.toFloat() / displayHeight.toFloat()
        if (!ratio.isFinite() || ratio <= 0f)
            return Orientation.UNKNOWN
        if (ratio in (1f / squareThreshold)..squareThreshold)
            return Orientation.SQUARE
        return if (ratio > 1f) Orientation.LANDSCAPE else Orientation.PORTRAIT
    }

    private fun isProbeablePath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("content://") -> true
            lower.startsWith("file://") -> true
            !lower.contains("://") -> true
            else -> false
        }
    }

    private fun detectMimeType(context: Context, path: String): String? {
        return try {
            if (path.startsWith("content://", ignoreCase = true)) {
                val resolver = context.contentResolver
                val uri = Uri.parse(path)
                resolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                    ?: queryDisplayName(resolver, uri)?.let(URLConnection::guessContentTypeFromName)
                    ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment)
            } else {
                URLConnection.guessContentTypeFromName(path.substringBefore('?').substringBefore('#'))
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst())
                    return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveVideo(context: Context, path: String): Orientation {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(context, retriever, path)

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return Orientation.UNKNOWN
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return Orientation.UNKNOWN
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            classify(width, height, rotation)
        } catch (_: Throwable) {
            Orientation.UNKNOWN
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // Ignore vendor-specific release failures.
            }
        }
    }

    private fun setDataSource(
        context: Context,
        retriever: MediaMetadataRetriever,
        path: String,
    ) {
        when {
            path.startsWith("content://", ignoreCase = true) ->
                retriever.setDataSource(context, Uri.parse(path))

            path.startsWith("file://", ignoreCase = true) -> {
                val filePath = Uri.parse(path).path
                    ?: throw IllegalArgumentException("Invalid file URI: $path")
                retriever.setDataSource(filePath)
            }

            else -> retriever.setDataSource(path)
        }
    }

    private fun resolveImage(context: Context, path: String): Orientation {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            when {
                path.startsWith("content://", ignoreCase = true) -> {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                    } ?: return Orientation.UNKNOWN
                }

                path.startsWith("file://", ignoreCase = true) -> {
                    val filePath = Uri.parse(path).path ?: return Orientation.UNKNOWN
                    BitmapFactory.decodeFile(filePath, bounds)
                }

                path.contains("://") -> return Orientation.UNKNOWN
                else -> BitmapFactory.decodeFile(path, bounds)
            }
        } catch (_: Throwable) {
            return Orientation.UNKNOWN
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            return Orientation.UNKNOWN

        return classify(bounds.outWidth, bounds.outHeight, readImageRotation(context, path))
    }

    private fun readImageRotation(context: Context, path: String): Int {
        val exifOrientation = try {
            when {
                path.startsWith("content://", ignoreCase = true) ->
                    readContentExifOrientation(context, Uri.parse(path))

                path.startsWith("file://", ignoreCase = true) -> {
                    val filePath = Uri.parse(path).path ?: return 0
                    readFileExifOrientation(filePath)
                }

                path.contains("://") -> ExifInterface.ORIENTATION_UNDEFINED
                else -> readFileExifOrientation(path)
            }
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_UNDEFINED
        }

        return when (exifOrientation) {
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90 -> 90

            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270 -> 270

            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            else -> 0
        }
    }

    private fun readFileExifOrientation(path: String): Int {
        if (!File(path).isFile)
            return ExifInterface.ORIENTATION_UNDEFINED
        return ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
    }

    @Suppress("DEPRECATION")
    private fun readContentExifOrientation(context: Context, uri: Uri): Int {
        // The InputStream constructor exists from API 24. Android 9 uses this branch; older
        // supported devices simply fall back to unrotated image bounds and mpv corrects later.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return ExifInterface.ORIENTATION_UNDEFINED

        return context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        } ?: ExifInterface.ORIENTATION_UNDEFINED
    }
}
