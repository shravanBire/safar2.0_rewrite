package com.example.safar20.data.local

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring-buffer diagnostic logger (last 500 lines persisted in filesDir) with secret scrubbing
 * and global uncaught exception capture (P1-A10).
 */
object AppLog {

    private const val MAX_LINES = 500
    private const val LOG_FILE_NAME = "safar_diagnostics.log"
    private val HEX_SECRET_REGEX = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val CRED_REGEX = Regex("(?i)(password|pass|secret|key)\\s*[=:]\\s*\\S+")

    private val lines = ArrayDeque<String>(MAX_LINES)
    private var logFile: File? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val file = File(context.filesDir, LOG_FILE_NAME)
        logFile = file
        if (file.exists()) {
            try {
                val existing = file.readLines()
                for (line in existing.takeLast(MAX_LINES)) {
                    lines.addLast(line)
                }
            } catch (_: Exception) {
            }
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("CRASH", "Uncaught exception on thread ${thread.name}", throwable)
            } catch (_: Throwable) {
            } finally {
                prevHandler?.uncaughtException(thread, throwable)
            }
        }
        initialized = true
        i("APP", "AppLog initialized")
    }

    fun sanitize(text: String): String {
        return text
            .replace(HEX_SECRET_REGEX, "[REDACTED_KEY]")
            .replace(CRED_REGEX, "$1=[REDACTED]")
    }

    @Synchronized
    fun i(tag: String, message: String) {
        appendLine("INFO", tag, message, null)
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        appendLine("ERROR", tag, message, throwable)
    }

    @Synchronized
    private fun appendLine(level: String, tag: String, message: String, throwable: Throwable?) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val cleanMsg = sanitize(message)
        addRawLine("$ts [$level/$tag] $cleanMsg")

        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val traceLines = sw.toString().lineSequence().take(15)
            for (tLine in traceLines) {
                addRawLine("$ts [$level/$tag]   ${sanitize(tLine.trim())}")
            }
        }
        persistToDisk()
    }

    private fun addRawLine(line: String) {
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
        }
        lines.addLast(line)
    }

    private fun persistToDisk() {
        val file = logFile ?: return
        try {
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun getLines(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        persistToDisk()
    }

    @Synchronized
    fun exportFile(context: Context): File {
        val file = logFile ?: File(context.filesDir, LOG_FILE_NAME).also { logFile = it }
        persistToDisk()
        return file
    }

    fun shareDiagnostics(context: Context): Boolean {
        return try {
            val file = exportFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SAFAR 2.0 Diagnostics Log")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Export SAFAR Diagnostics").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }
}
