package dev.erik.voiceinput

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Durable diagnostic log for on-device debugging.
 *
 * Writes to app-private storage and mirrors to logcat (tag [TAG]).
 * Also keeps an in-memory ring of recent lines (export + IME overlay).
 * Export via Settings → Share diagnostics.
 */
object DiagLog {
    const val TAG = "GrokVoiceDiag"

    private const val DIR = "diag"
    private const val FILE_NAME = "session.log"
    private const val MAX_BYTES = 512 * 1024 // 512 KB
    private const val MEMORY_LINES = 400

    private val lock = Any()
    private var logFile: File? = null
    private val sessionId = AtomicReference("boot")
    private val lineSeq = AtomicLong(0)
    private val memory = ArrayDeque<String>(MEMORY_LINES)

    /** Last human-readable status for IME overlay (thread-safe). */
    private val lastStatus = AtomicReference("idle")
    private val lastDetail = AtomicReference("")

    fun init(context: Context) {
        val target =
            File(File(context.applicationContext.filesDir, DIR).apply { mkdirs() }, FILE_NAME)
        val first =
            synchronized(lock) {
                val prev = logFile
                if (prev != null && prev.absolutePath == target.absolutePath) {
                    return
                }
                logFile = target
                prev == null
            }
        if (first) {
            i(
                "boot",
                "DiagLog ready",
                "file" to target.absolutePath,
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            )
        } else {
            i("boot", "DiagLog path rebind", "file" to target.absolutePath)
        }
    }

    fun currentSessionId(): String = sessionId.get()

    fun beginSession(surface: String): String {
        val id =
            Integer.toHexString(
                (System.currentTimeMillis() and 0xffffffffL).toInt(),
            ) + "-" + surface.take(3)
        sessionId.set(id)
        lineSeq.set(0)
        setStatus("session $id", "surface=$surface")
        i("session", "begin", "id" to id, "surface" to surface)
        return id
    }

    fun setStatus(status: String, detail: String = "") {
        lastStatus.set(status)
        lastDetail.set(detail)
    }

    fun statusLine(): String {
        val s = lastStatus.get()
        val d = lastDetail.get()
        return if (d.isBlank()) s else "$s\n$d"
    }

    fun lastStatusOnly(): String = lastStatus.get()

    fun lastDetailOnly(): String = lastDetail.get()

    fun i(component: String, message: String, vararg fields: Pair<String, Any?>) {
        write("I", component, message, fields)
    }

    fun w(component: String, message: String, vararg fields: Pair<String, Any?>) {
        write("W", component, message, fields)
    }

    fun e(
        component: String,
        message: String,
        error: Throwable? = null,
        vararg fields: Pair<String, Any?>,
    ) {
        val extra =
            if (error != null) {
                fields.toList() +
                    listOf(
                        "err" to (error.javaClass.simpleName),
                        "errMsg" to (error.message ?: ""),
                    )
            } else {
                fields.toList()
            }
        write("E", component, message, extra.toTypedArray())
        if (error != null) {
            try {
                Log.e(TAG, "[$component] $message", error)
            } catch (_: RuntimeException) {
            } catch (_: Exception) {
            }
        }
    }

    fun ui(event: String, vararg fields: Pair<String, Any?>) {
        i("ui", event, *fields)
    }

    /** Start a timed phase; call [Timing.end] when done. */
    fun start(component: String, phase: String, vararg fields: Pair<String, Any?>): Timing {
        i(component, "$phase start", *fields)
        return Timing(component, phase, System.nanoTime())
    }

    class Timing(
        private val component: String,
        private val phase: String,
        private val startNs: Long,
    ) {
        fun end(vararg fields: Pair<String, Any?>): Long {
            val ms = (System.nanoTime() - startNs) / 1_000_000L
            i(component, "$phase end", *fields, "ms" to ms)
            return ms
        }

        fun fail(
            error: Throwable,
            vararg fields: Pair<String, Any?>,
        ): Long {
            val ms = (System.nanoTime() - startNs) / 1_000_000L
            e(
                component,
                "$phase fail",
                error,
                *fields,
                "ms" to ms,
            )
            return ms
        }
    }

    fun logFile(context: Context): File {
        init(context)
        return File(File(context.applicationContext.filesDir, DIR), FILE_NAME).also {
            // Keep singleton pointer in sync with this path.
            synchronized(lock) {
                if (logFile == null || logFile?.absolutePath != it.absolutePath) {
                    logFile = it
                }
            }
        }
    }

    fun clear(context: Context) {
        init(context)
        synchronized(lock) {
            memory.clear()
            try {
                logFile?.writeText("")
            } catch (_: Exception) {
            }
        }
        i("diag", "log cleared")
    }

    fun readTail(context: Context, maxChars: Int = 48_000): String {
        init(context)
        // Prefer memory (always complete for this process), then file.
        val fromMem =
            synchronized(lock) {
                memory.joinToString("\n")
            }
        if (fromMem.isNotBlank()) {
            return if (fromMem.length <= maxChars) fromMem else fromMem.takeLast(maxChars)
        }
        val file = logFile(context)
        if (!file.exists()) return "(no log yet)"
        return try {
            val text = file.readText()
            if (text.isBlank()) "(empty log)"
            else if (text.length <= maxChars) text
            else text.takeLast(maxChars)
        } catch (e: Exception) {
            "(read failed: ${e.message})"
        }
    }

    /** Snapshot for sharing: file if non-empty, else memory dump. */
    fun materializeForShare(context: Context): File {
        val file = logFile(context)
        synchronized(lock) {
            try {
                if (!file.exists() || file.length() == 0L) {
                    file.parentFile?.mkdirs()
                    file.writeText(memory.joinToString("\n") + "\n")
                }
            } catch (_: Exception) {
            }
        }
        return file
    }

    private fun write(
        level: String,
        component: String,
        message: String,
        fields: Array<out Pair<String, Any?>>,
    ) {
        val seq = lineSeq.incrementAndGet()
        val ts =
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            } catch (_: Exception) {
                System.currentTimeMillis().toString()
            }
        val fieldStr =
            if (fields.isEmpty()) {
                ""
            } else {
                fields.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }
            }
        val sid = sessionId.get()
        val line =
            buildString {
                append(ts)
                append(' ')
                append(level)
                append(" sid=")
                append(sid)
                append(" #")
                append(seq)
                append(" [")
                append(component)
                append("] ")
                append(message)
                if (fieldStr.isNotEmpty()) {
                    append(' ')
                    append(fieldStr)
                }
            }

        // android.util.Log is not mocked in plain JVM unit tests — never throw.
        try {
            when (level) {
                "W" -> Log.w(TAG, line)
                "E" -> Log.e(TAG, line)
                else -> Log.i(TAG, line)
            }
        } catch (_: RuntimeException) {
        } catch (_: Exception) {
        }

        synchronized(lock) {
            if (memory.size >= MEMORY_LINES) {
                memory.removeFirst()
            }
            memory.addLast(line)
            val file = logFile
            if (file != null) {
                try {
                    file.parentFile?.mkdirs()
                    file.appendText(line + "\n")
                    if (file.length() > MAX_BYTES) {
                        truncateHead(file, MAX_BYTES / 2)
                    }
                } catch (_: Exception) {
                    // Never crash the app for logging.
                }
            }
        }
    }

    private fun formatValue(v: Any?): String {
        if (v == null) return "null"
        val s = v.toString().replace('\n', ' ').replace('\r', ' ')
        return if (s.length > 200) s.take(200) + "…" else s
    }

    private fun truncateHead(file: File, keepBytes: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                if (len <= keepBytes) return
                val start = len - keepBytes
                raf.seek(start)
                while (raf.filePointer < len) {
                    val b = raf.read()
                    if (b < 0 || b.toChar() == '\n') break
                }
                val remaining = ByteArray((len - raf.filePointer).toInt().coerceAtLeast(0))
                raf.readFully(remaining)
                raf.setLength(0)
                raf.seek(0)
                raf.writeBytes("…(truncated)…\n")
                raf.write(remaining)
            }
        } catch (_: Exception) {
        }
    }
}
