package com.recora.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * Запис, що показується у списку (з MediaStore або з файлової системи).
 */
data class Recording(
    val name: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val viewUri: Uri,
    val mediaStoreUri: Uri?, // для видалення з MediaStore
    val file: File?          // для видалення файла
)

/**
 * Ціль для запису нового відео: або MediaStore (API 29+), або файл.
 */
data class PendingOutput(
    val file: File?,
    val mediaStoreUri: Uri?,
    val pfd: ParcelFileDescriptor?
)

/**
 * Все зберігання записів в одному місці.
 *
 * - Android 10+ (API 29+): MediaStore, тека «Фільми/Recora», дозволи не потрібні;
 * - Android 7–9: прямий запис у спільну теку (дозвіл WRITE_EXTERNAL_STORAGE)
 *   або у приватну теку застосунку, якщо дозволу немає.
 */
object RecordingStore {

    private const val PUBLIC_DIR_NAME = "Recora"

    /** Приватна тека (fallback, коли немає доступу до спільної). */
    fun privateDir(context: Context): File {
        val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val dir = if (movies != null) File(movies, "ScreenRecorder") else File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), PUBLIC_DIR_NAME)

    // ------------------------------------------------------------- створення

    /** Створює ціль для нового запису. Викликати ПЕРЕД recorder.prepare(). */
    fun createOutput(context: Context, fileName: String): PendingOutput {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.VideoColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/$PUBLIC_DIR_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("MediaStore open FD failed")
            return PendingOutput(file = null, mediaStoreUri = uri, pfd = pfd)
        }

        // API 24–28: намагаємось у спільну теку, інакше — у приватну
        val canWritePublic = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val dir = if (canWritePublic) {
            val pub = publicDir()
            if (!pub.exists()) pub.mkdirs()
            if (pub.canWrite()) pub else privateDir(context)
        } else {
            privateDir(context)
        }
        return PendingOutput(file = File(dir, fileName), mediaStoreUri = null, pfd = null)
    }

    /** Завершує запис: success=true — публікує файл, false — прибирає недописане. */
    fun finishOutput(context: Context, output: PendingOutput, success: Boolean) {
        try {
            output.pfd?.close()
        } catch (_: Exception) {
        }

        val uri = output.mediaStoreUri
        if (uri != null) {
            if (success) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
            return
        }

        val file = output.file ?: return
        if (!success) {
            file.delete()
        }
        // Сканування оновить індекс галереї (і прибере запис про видалений файл)
        scan(context, file)
    }

    // ---------------------------------------------------------------- список

    /** Усі записи: спільна тека + приватна (fallback/спадок v0.1). */
    fun listAll(context: Context): List<Recording> {
        val result = mutableListOf<Recording>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED
            )
            val selection = "${MediaStore.Video.VideoColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
            val args = arrayOf(Environment.DIRECTORY_MOVIES + "/$PUBLIC_DIR_NAME/", "0")
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    result.add(
                        Recording(
                            name = cursor.getString(nameCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            dateMillis = cursor.getLong(dateCol) * 1000L,
                            viewUri = uri,
                            mediaStoreUri = uri,
                            file = null
                        )
                    )
                }
            }
        } else {
            // API 24–28: читаємо спільну теку напряму (якщо є дозвіл на читання)
            val canRead = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (canRead) {
                publicDir().listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                    ?.forEach { file -> result.addFromFile(context, file) }
            }
        }

        // Приватна тека: fallback і записи, зроблені до v0.2
        privateDir(context).listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.forEach { file -> result.addFromFile(context, file) }

        return result.sortedByDescending { it.dateMillis }
    }

    private fun MutableList<Recording>.addFromFile(context: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        add(
            Recording(
                name = file.name,
                sizeBytes = file.length(),
                dateMillis = file.lastModified(),
                viewUri = uri,
                mediaStoreUri = null,
                file = file
            )
        )
    }

    // ------------------------------------------------------------- видалення

    fun delete(context: Context, recording: Recording): Boolean {
        recording.mediaStoreUri?.let { uri ->
            return context.contentResolver.delete(uri, null, null) > 0
        }
        val file = recording.file ?: return false
        val deleted = file.delete()
        if (deleted) scan(context, file) // щоб галерея забула про файл
        return deleted
    }

    private fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
        )
    }
}
