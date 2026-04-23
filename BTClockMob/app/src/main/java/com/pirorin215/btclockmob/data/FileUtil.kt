package com.pirorin215.btclockmob.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

data class DateTimeInfo(val date: String, val time: String)

object FileUtil {
    private val FILE_NAME_DATETIME_REGEX = Regex("""(?:R|M|AI)(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})\.(wav|txt)""")

    /**
     * Extract datetime string from file name
     * @param fileName File name like "R2025-12-01-02-04-08.wav"
     * @return Datetime string like "2025-12-01-02-04-08" or null if not found
     */
    private fun extractDateTimeStringFromFileName(fileName: String): String? {
        val matchResult = FILE_NAME_DATETIME_REGEX.find(fileName)
        return if (matchResult != null && matchResult.groupValues.size > 1) {
            matchResult.groupValues[1]
        } else {
            null
        }
    }

    fun getRecordingDateTimeInfo(fileName: String): DateTimeInfo {
        val dateTimeString = extractDateTimeStringFromFileName(fileName)

        return if (dateTimeString != null) {
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                val date = inputFormat.parse(dateTimeString)

                val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                date?.let {
                    DateTimeInfo(dateFormat.format(it), timeFormat.format(it))
                } ?: DateTimeInfo("不明な日付", "不明な時刻")
            } catch (e: ParseException) {
                e.printStackTrace()
                DateTimeInfo("不明な日付", "不明な時刻")
            }
        } else {
            DateTimeInfo("不明な日付", "不明な時刻")
        }
    }

    // ファイル名から録音日時を抽出する
    // 例: R2025-12-01-02-04-08.wav -> 2025/12/01 02:04:08
    fun extractRecordingDateTime(fileName: String): String {
        val dateTimeInfo = getRecordingDateTimeInfo(fileName)
        return "${dateTimeInfo.date} ${dateTimeInfo.time}"
    }

    // タイムスタンプをファイル名に使用する形式にフォーマットする
    fun formatTimestampForFileName(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun formatTimestampToDateTimeString(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    fun getTimestampFromFileName(fileName: String): Long {
        val dateTimeString = extractDateTimeStringFromFileName(fileName)

        return if (dateTimeString != null) {
            try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                inputFormat.parse(dateTimeString)?.time ?: 0L
            } catch (e: ParseException) {
                e.printStackTrace()
                0L
            }
        } else {
            0L
        }
    }

    // ファイル名からダウンロードフォルダ内のFileオブジェクトを取得する
    fun getAudioFile(context: Context, dirName: String, fileName: String): File {
        val audioDir = context.getExternalFilesDir(dirName)
        // audioDirが存在しない場合は作成する
        if (audioDir != null && !audioDir.exists()) {
            audioDir.mkdirs()
        }
        return File(audioDir, fileName)
    }

    fun getTempPcmFile(context: Context, originalFileName: String): File {
        val tempDir = File(context.cacheDir, "temp_pcm")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        val pcmFileName = originalFileName.replace(".wav", "_pcm.wav", ignoreCase = true)
        return File(tempDir, pcmFileName)
    }

    fun parseRfc3339Timestamp(rfc3339String: String?): Long {
        if (rfc3339String.isNullOrBlank()) return 0L
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                parseRfc3339TimestampO(rfc3339String)
            } else {
                parseRfc3339TimestampLegacy(rfc3339String)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun parseRfc3339TimestampO(rfc3339String: String?): Long {
        if (rfc3339String.isNullOrBlank()) return 0L
        return try {
            val offsetDateTime = OffsetDateTime.parse(rfc3339String)
            offsetDateTime.toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            e.printStackTrace()
            0L
        }
    }

    private fun parseRfc3339TimestampLegacy(rfc3339String: String?): Long {
        if (rfc3339String.isNullOrBlank()) return 0L
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(rfc3339String)?.time ?: 0L
        } catch (e: ParseException) {
            e.printStackTrace()
            0L
        }
    }

    fun getAudioFileSizeString(context: Context, dirName: String, fileName: String): String {
        // Use MediaStore API to get file size
        val fileSizeInBytes = getAudioFileSizeFromMediaStore(context, fileName)
        if (fileSizeInBytes <= 0) {
            return "File not found"
        }
        return "${formatFileSize(fileSizeInBytes)} ($fileSizeInBytes bytes)"
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    // ========== MediaStore API for Documents/BTClockMob/ ==========

    /**
     * Get the URI of an audio file in Documents/BTClockMob/ using MediaStore
     * @return Uri or null if not found
     */
    fun getAudioFileUri(context: Context, fileName: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri("external")
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, "${Environment.DIRECTORY_DOCUMENTS}/BTClockMob/")

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val id = cursor.getLong(idColumn)
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    /**
     * Delete an audio file from Documents/BTClockMob/ using MediaStore
     * @return true if deleted, false otherwise
     */
    fun deleteAudioFile(context: Context, fileName: String): Boolean {
        val uri = getAudioFileUri(context, fileName) ?: return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Get the size of an audio file in Documents/BTClockMob/ using MediaStore
     * @return file size in bytes, or 0 if not found
     */
    fun getAudioFileSizeFromMediaStore(context: Context, fileName: String): Long {
        val uri = getAudioFileUri(context, fileName) ?: return 0

        val projection = arrayOf(MediaStore.Files.FileColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                return cursor.getLong(sizeColumn)
            }
        }
        return 0
    }

    /**
     * Check if an audio file exists in Documents/BTClockMob/
     */
    fun audioFileExists(context: Context, fileName: String): Boolean {
        return getAudioFileUri(context, fileName) != null
    }

    /**
     * Get the actual file path from MediaStore URI
     * Note: This uses DATA column which is deprecated on Android 10+,
     * but still works on most devices for compatibility with existing code.
     * @return file path string or null if not found
     */
    fun getAudioFilePath(context: Context, fileName: String): String? {
        val uri = getAudioFileUri(context, fileName) ?: return null

        // Try to get the actual file path from DATA column (deprecated but works)
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dataIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                    return cursor.getString(dataIndex)
                }
            }
        }
        return null
    }
}
