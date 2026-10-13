package app.litetube.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * Writes files where the phone's gallery and music apps find them:
 * Movies/LiteTube, Music/LiteTube and Pictures/LiteTube.
 * Android 10+: MediaStore, no permission needed. Android 5–9: the public folders
 * (needs the storage permission, which the UI requests first).
 */
class SaveTarget private constructor(
    private val context: Context,
    val uri: Uri?,
    val file: File?,
    val descriptor: ParcelFileDescriptor?,
) {
    /** Makes the file visible to other apps. */
    fun publish() {
        descriptor?.close()
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        file?.let { MediaScannerConnection.scanFile(context, arrayOf(it.path), null, null) }
    }

    fun discard() {
        runCatching { descriptor?.close() }
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
        file?.delete()
    }

    /** Human-readable location, e.g. "Movies/LiteTube". */
    val location: String get() = file?.parentFile?.let { "${it.parentFile?.name}/${it.name}" } ?: folder

    private var folder = ""

    enum class Kind(val directory: String) {
        VIDEO(Environment.DIRECTORY_MOVIES),
        AUDIO(Environment.DIRECTORY_MUSIC),
        IMAGE(Environment.DIRECTORY_PICTURES),
    }

    companion object {
        fun create(context: Context, kind: Kind, fileName: String, mimeType: String): SaveTarget {
            val name = sanitize(fileName)
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = when (kind) {
                    Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, kind.directory + "/LiteTube")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values)
                    ?: error("Couldn't create $name")
                val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: error("Couldn't open $name")
                return SaveTarget(context, uri, null, fd).also { it.folder = kind.directory + "/LiteTube" }
            }
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(kind.directory), "LiteTube")
            dir.mkdirs()
            var file = File(dir, name)
            var n = 1
            while (file.exists()) file = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')).also { n++ }
            return SaveTarget(context, null, file, null)
        }

        fun sanitize(name: String): String {
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)
            return if (ext.isEmpty()) base else "$base.$ext"
        }
    }
}
