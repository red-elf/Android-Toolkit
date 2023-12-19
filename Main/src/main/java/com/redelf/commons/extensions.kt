package com.redelf.commons

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Base64
import android.util.Base64OutputStream
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.redelf.commons.execution.Executor
import timber.log.Timber
import java.io.*
import java.util.*
import java.util.concurrent.RejectedExecutionException

var GLOBAL_RECORD_EXCEPTIONS = true
val DEFAULT_ACTIVITY_REQUEST = randomInteger()

fun randomInteger() = Random().nextInt((1000 - 300) + 1) + 300

fun randomBigInteger() = Random().nextInt((10000 - 300) + 1) + 300

fun yieldWhile(condition: () -> Boolean) {

    while (condition()) {

        Thread.yield()
    }
}

fun recordException(e: Throwable) {

    Timber.e(e)

    if (GLOBAL_RECORD_EXCEPTIONS) {

        FirebaseCrashlytics.getInstance().recordException(e)
    }
}

@Suppress("DEPRECATION")
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {

    val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?

    manager?.let {

        for (service in it.getRunningServices(Int.MAX_VALUE)) {

            if (serviceClass.name == service.service.className) {

                return true
            }
        }
    }
    return false
}

@SuppressLint("Range")
fun Context.getFileName(uri: Uri): String? {

    var result: String? = null

    if (uri.scheme.equals("content")) {

        val cursor: Cursor? = contentResolver
            .query(uri, null, null, null, null)

        cursor.use {

            if (it != null && it.moveToFirst()) {

                result = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        }
    }

    if (result == null) {

        result = uri.path

        val cut = result?.lastIndexOf('/')

        if (cut != -1 && cut != null) {

            result = result?.substring(cut + 1)
        }
    }

    return result
}

fun Context.closeKeyboard(v: View) {

    val inputMethodManager: InputMethodManager? =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?

    inputMethodManager?.hideSoftInputFromWindow(v.applicationWindowToken, 0)
}

fun Activity.selectExternalStorageFolder(name: String, requestId: Int = DEFAULT_ACTIVITY_REQUEST) {

    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {

        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_TITLE, name)
    }

    startActivityForResult(intent, requestId)
}

@Throws(IllegalArgumentException::class)
fun getFileNameAndExtension(fileName: String): Pair<String, String> {

    if (TextUtils.isEmpty(fileName)) {

        throw IllegalArgumentException("Empty file name")
    }
    val tokens = fileName.split("\\.(?=[^.]+$)".toRegex()).toTypedArray()
    if (tokens.size < 2) {

        throw IllegalArgumentException("Could not extract file name and extension from: $fileName")
    }
    return Pair(tokens[0], tokens[1])
}

@Throws(IOException::class, IllegalArgumentException::class, IllegalStateException::class)
fun Context.getCachedMediaFile(

    uri: Uri,
    workingDir: File = cacheDir,
    outputFileName: String = ""

): File {

    val fileName = getFileName(uri)
    if (TextUtils.isEmpty(fileName)) {

        throw IllegalArgumentException("Could not obtain file name from Uri: $uri")
    }
    fileName?.let { fName ->

        val resolver = contentResolver
        val pair = getFileNameAndExtension(fName)
        val name = if (TextUtils.isEmpty(outputFileName)) {

            pair.first
        } else {

            outputFileName
        }
        val extension = pair.second

        val outputFile = File(workingDir.absolutePath, "$name.$extension")
        outputFile.init()

        val errMsg = "Could not open input stream from: $uri"
        val inputStream =
            resolver.openInputStream(uri) ?: throw IllegalArgumentException(errMsg)
        val bufferedInputStream = BufferedInputStream(inputStream)

        var stored = 0
        val capacity = 1024
        val buffer = ByteArray(capacity)
        var read = bufferedInputStream.read(buffer)
        val outputStream = FileOutputStream(outputFile)
        val bufferedOutputStream = BufferedOutputStream(outputStream)
        while (read != -1) {

            bufferedOutputStream.write(buffer)
            stored += read
            read = bufferedInputStream.read(buffer)
        }
        if (stored == 0) {

            throw IllegalStateException("No bytes stored into: ${outputFile.absolutePath}")
        }
        Timber.v("$stored bytes written into ${outputFile.absolutePath}")

        bufferedOutputStream.flush()
        bufferedOutputStream.close()
        outputStream.close()
        bufferedInputStream.close()
        inputStream.close()
        Timber.v("File length ${outputFile.name}: ${outputFile.length()}")

        return outputFile
    }
    throw IllegalArgumentException("Could not obtain path from Uri: $uri")
}

@Throws(IOException::class, IllegalArgumentException::class, IllegalStateException::class)
fun Context.writeIntoFileBuffered(

    where: File,
    what: ByteArray,
    deleteIfExist: Boolean = false

): Boolean {

    where.init(deleteIfExist)

    val inputStream = ByteArrayInputStream(what)
    val bufferedInputStream = BufferedInputStream(inputStream)

    var stored = 0
    val capacity = 1024 * 4
    val buffer = ByteArray(capacity)
    var read = bufferedInputStream.read(buffer)
    val outputStream = FileOutputStream(where)
    val bufferedOutputStream = BufferedOutputStream(outputStream)

    while (read != -1) {

        bufferedOutputStream.write(buffer)
        stored += read
        read = bufferedInputStream.read(buffer)
    }

    if (stored == 0) {

        throw IllegalStateException("No bytes stored into: ${where.absolutePath}")
    }

    Timber.v("$stored bytes written into ${where.absolutePath}")

    bufferedOutputStream.flush()
    bufferedOutputStream.close()

    outputStream.flush()
    outputStream.close()

    bufferedInputStream.close()
    inputStream.close()

    Timber.v("File length ${where.name}: ${where.length()}")

    return true
}

@Throws(IOException::class, IllegalArgumentException::class, IllegalStateException::class)
fun Context.writeIntoFile(

    where: File,
    what: InputStream,
    deleteIfExist: Boolean = false

): Boolean {

    where.init(deleteIfExist)

    val bufferedInputStream = BufferedInputStream(what)

    var stored = 0
    val capacity = 1024
    val buffer = ByteArray(capacity)
    var read = bufferedInputStream.read(buffer)
    val outputStream = FileOutputStream(where)
    val bufferedOutputStream = BufferedOutputStream(outputStream)

    while (read != -1) {

        bufferedOutputStream.write(buffer)
        stored += read
        read = bufferedInputStream.read(buffer)
    }

    if (stored == 0) {

        throw IllegalStateException("No bytes stored into: ${where.absolutePath}")
    }

    Timber.v("$stored bytes written into ${where.absolutePath}")

    bufferedOutputStream.flush()
    bufferedOutputStream.close()
    outputStream.close()
    bufferedInputStream.close()
    what.close()

    Timber.v("File length ${where.name}: ${where.length()}")

    return true
}

@Throws(IllegalStateException::class)
fun File.init(deleteIfExist: Boolean = true) {

    Timber.v("Initializing file: $absolutePath")

    if (deleteIfExist && exists()) {

        Timber.w("File already exists: $absolutePath")

        if (delete()) {

            Timber.v("File deleted: $absolutePath")

        } else {

            val msg = "File could not be deleted: $absolutePath"
            throw IllegalStateException(msg)
        }
    }

    val msg = "File could not be created: $absolutePath"

    try {

        val created = createNewFile()
        val exists = exists()

        if (created && exists) {

            Timber.v("File created: $absolutePath")

        } else {

            throw IllegalStateException(msg)
        }

    } catch (e: IOException) {

        Timber.e(e)
        throw IllegalStateException(msg)
    }
}

fun Context.isLowEndDevice(): Boolean {

    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val lowMemory = activityManager.isLowRamDevice
    val memoryClass = activityManager.memoryClass
    val processors = Runtime.getRuntime().availableProcessors()
    return lowMemory || processors <= 4 || memoryClass <= 192
}

fun Context.toast(msg: String, short: Boolean = false) {

    val length = if (short) {

        Toast.LENGTH_SHORT

    } else {

        Toast.LENGTH_LONG
    }

    Handler(Looper.getMainLooper()).post {

        Toast.makeText(applicationContext, msg, length).show()
    }
}

fun Activity.toast(error: Throwable) {

    toast(error, short = true, localised = true)
}

fun Activity.toast(error: Throwable, short: Boolean = false, localised: Boolean = false) {

    val msg = if (localised) {

        error.message

    } else {

        error.localizedMessage
    }

    msg?.let {

        toast(it, short)
        return
    }

    error::class.simpleName?.let {

        toast("Error, $it", short)
        return
    }

    toast("Error", short)
}

fun Context.playNotificationSound() {

    try {

        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(this, notification)

        val aa = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        r.audioAttributes = aa
        r.play()

    } catch (e: Exception) {

        Timber.e(e)
    }
}

fun join(what: List<String>, separator: String = ", "): String {

    var result = ""

    what.forEachIndexed { index, value ->

        result += value

        if (index != what.lastIndex) {

            result += separator
        }
    }

    return result
}

fun <T> safeRemoteValue(provider: () -> T, default: T): T {

    try {

        val result = provider()
        result?.let {

            return it
        }

    } catch (e: Exception) {

        Timber.e(e)
    }

    return default
}

fun safeRemoteString(provider: () -> String): String {

    return safeRemoteValue(provider, "")
}

fun safeRemoteString(provider: () -> String, default: String = ""): String {

    return safeRemoteValue(provider, default)
}

fun safeRemoteBoolean(provider: () -> Boolean): Boolean {

    return safeRemoteValue(provider, false)
}

fun safeRemoteBoolean(provider: () -> Boolean, default: Boolean = false): Boolean {

    return safeRemoteValue(provider, default)
}

fun safeRemoteInteger(provider: () -> Int): Int {

    return safeRemoteValue(provider, 0)
}

fun safeRemoteInteger(provider: () -> Int, default: Int = 0): Int {

    return safeRemoteValue(provider, default)
}

fun safeRemoteLong(provider: () -> Long): Long {

    return safeRemoteValue(provider, 0)
}

fun safeRemoteLong(provider: () -> Long, default: Long = 0): Long {

    return safeRemoteValue(provider, default)
}

fun safeRemoteFloat(provider: () -> Float): Float {

    return safeRemoteValue(provider, 0f)
}

fun safeRemoteFloat(provider: () -> Float, default: Float = 0f): Float {

    return safeRemoteValue(provider, default)
}

fun safeRemoteDouble(provider: () -> Double): Double {

    return safeRemoteValue(provider, 0.0)
}

fun safeRemoteDouble(provider: () -> Double, default: Double = 0.0): Double {

    return safeRemoteValue(provider, default)
}

@Throws(

    RejectedExecutionException::class,
    NullPointerException::class
)
fun exec(what: Runnable) {

    Executor.MAIN.execute(what)
}

@Throws(IllegalArgumentException::class)
fun encodeBytes(bytes: ByteArray): String {

    val tag = "Encode bytes ::"

    Timber.v("$tag START")

    @Throws(IOException::class)
    fun doEncodeBytes(bytes: ByteArray): ByteArray? {

        Timber.v("$tag DO ENCODE :: START")

        val failed: Boolean
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var bufferedInputStream: InputStream? = null
        var base64OutputStream: OutputStream? = null

        try {

            inputStream = bytes.inputStream()
            bufferedInputStream = BufferedInputStream(inputStream)
            outputStream = ByteArrayOutputStream()
            base64OutputStream = Base64OutputStream(outputStream, Base64.NO_WRAP)

            bufferedInputStream.copyTo(base64OutputStream, 1024)

            val resultBytes = outputStream.toByteArray()

            Timber.v("$tag DO ENCODE :: END")

            return resultBytes

        } catch (e: IOException) {

            failed = true

            Timber.e(e)

        } finally {

            base64OutputStream?.close()
            outputStream?.close()
            bufferedInputStream?.close()
            inputStream?.close()
        }

        if (failed) {

            throw IOException("Encoding failure")
        }

        return null
    }

    try {

        doEncodeBytes(bytes)?.let {

            return String(it)
        }

    } catch (e: OutOfMemoryError) {

        recordException(e)
    }

    throw IllegalArgumentException("No bytes encoded")
}

fun String.isBase64Encoded(): Boolean {

    return org.apache.commons.codec.binary.Base64.isBase64(this)
}