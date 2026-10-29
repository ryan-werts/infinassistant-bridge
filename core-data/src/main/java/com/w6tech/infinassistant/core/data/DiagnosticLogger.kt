package com.w6tech.infinassistant.core.data

import android.content.Context
import android.os.Build
import com.w6tech.infinassistant.core.common.Redactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Rolling JSONL diagnostic log for field debugging.
 * Writes entries on a background thread and caps file size at [MAX_FILE_BYTES].
 *
 * One instance per process (the app's DI singleton): the bridge writes to it, and so do the core
 * modules through [DiagnosticTeeLogger]. A second instance would append to the same file from a
 * second thread.
 */
class DiagnosticLogger(
    private val context: Context,
) {
    companion object {
        const val MAX_FILE_BYTES = 500_000L // 500KB
        private const val LOG_DIR = "diagnostics"
        private const val CURRENT_FILE = "bridge-log.jsonl"
        private const val ROTATED_FILE = "bridge-log.1.jsonl"
        /** How long an export waits for the entries logged before it to reach the file. */
        private const val EXPORT_WAIT_MS = 2_000L

        private val reader = Json { ignoreUnknownKeys = true }

        /** One line of the log as an entry; null for a line that is none (cut short, say). */
        fun parseLine(line: String): LogEntry? =
            if (line.isBlank()) {
                null
            } else {
                try {
                    reader.decodeFromString(LogEntry.serializer(), line)
                } catch (_: Exception) {
                    null
                }
            }
    }

    private val json = Json { encodeDefaults = false }

    // One thread, in order. A coroutine per entry on the IO pool wrote entries in any order, and
    // the lines of the bridge and of the core modules come from many threads: a bundle has to show
    // what happened first, first. An export also waits on this queue for what was logged before it.
    private val writeQueue = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diagnostic-log").apply { isDaemon = true }
    }

    /** Run at the start of an export: the tee logger writes the repeat counts it still holds. */
    @Volatile
    var beforeExport: (() -> Unit)? = null

    private val logDir: File by lazy {
        File(context.filesDir, LOG_DIR).also { it.mkdirs() }
    }

    private val currentFile: File get() = File(logDir, CURRENT_FILE)
    private val rotatedFile: File get() = File(logDir, ROTATED_FILE)

    @Serializable
    data class LogEntry(
        val ts: Long,
        val level: String,
        val tag: String,
        val msg: String,
        val msgId: Int? = null,
        val extra: String? = null,
    )

    fun log(
        level: String,
        tag: String,
        msg: String,
        msgId: Int? = null,
        extra: String? = null,
    ) {
        val entry = LogEntry(
            ts = System.currentTimeMillis(),
            level = level,
            tag = tag,
            msg = Redactor.redact(msg),
            msgId = msgId,
            extra = extra?.let { Redactor.redact(it) },
        )
        try {
            writeQueue.execute { appendEntry(entry) }
        } catch (_: Exception) {
            // Best-effort logging — never crash the app
        }
    }

    private fun appendEntry(entry: LogEntry) {
        try {
            rotateIfNeeded()
            val line = json.encodeToString(entry)
            FileOutputStream(currentFile, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.append(line)
                    writer.append('\n')
                }
            }
        } catch (_: Exception) {
            // Best-effort logging — never crash the app
        }
    }

    private fun rotateIfNeeded() {
        if (currentFile.exists() && currentFile.length() >= MAX_FILE_BYTES) {
            rotatedFile.delete()
            currentFile.renameTo(rotatedFile)
        }
    }

    /** Waits (a bounded time) until the entries logged so far are in the file. */
    private fun awaitWrites() {
        try {
            writeQueue.submit(Runnable {}).get(EXPORT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // A slow disk: export what is there.
        }
    }

    /**
     * Export the log file to a temporary file suitable for sharing.
     * Includes device info header and both current + rotated log files.
     * Returns the File, or null if no logs exist.
     */
    fun exportForSharing(appVersion: String, firmwareVersion: String?): File? {
        try {
            beforeExport?.invoke()
        } catch (_: Exception) {
        }
        awaitWrites()

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val exportFile = File(context.cacheDir, "pinetime-bridge-log-$timestamp.jsonl")

        try {
            exportFile.outputStream().use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                    // Device info header
                    val header = json.encodeToString(
                        LogEntry(
                            ts = System.currentTimeMillis(),
                            level = "INFO",
                            tag = "DEVICE_INFO",
                            msg = "Export start",
                            extra = buildString {
                                append("android=${Build.VERSION.SDK_INT}")
                                append(", device=${Build.MANUFACTURER} ${Build.MODEL}")
                                append(", app=$appVersion")
                                firmwareVersion?.let { append(", fw=$it") }
                            }
                        )
                    )
                    writer.append(header)
                    writer.append('\n')

                    // Append rotated (older) log first
                    if (rotatedFile.exists()) {
                        rotatedFile.forEachLine { writer.append(it); writer.append('\n') }
                    }
                    // Then current log
                    if (currentFile.exists()) {
                        currentFile.forEachLine { writer.append(it); writer.append('\n') }
                    }
                }
            }
            return if (exportFile.length() > 0) exportFile else null
        } catch (_: Exception) {
            return null
        }
    }

    /** Both files' entries, oldest first (the Health screen). Reads the files: not on the main thread. */
    fun readEntries(): List<LogEntry> {
        awaitWrites()
        val entries = ArrayList<LogEntry>()
        for (file in listOf(rotatedFile, currentFile)) {
            try {
                if (file.exists()) file.forEachLine { line -> parseLine(line)?.let { entries.add(it) } }
            } catch (_: Exception) {
                // A file rotated away mid-read: what was read stands.
            }
        }
        return entries
    }

    /** Delete all log files. */
    fun clear() {
        try {
            writeQueue.execute {
                currentFile.delete()
                rotatedFile.delete()
            }
        } catch (_: Exception) {
        }
    }
}
