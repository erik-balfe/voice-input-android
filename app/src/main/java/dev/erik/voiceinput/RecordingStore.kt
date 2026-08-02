package dev.erik.voiceinput

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * File-backed History store: `{id}.m4a` + `{id}.json` under [root].
 * Pure FS logic so unit tests can use a temp directory without mocking this class.
 */
class RecordingStore(
    private val root: File,
) {
    init {
        root.mkdirs()
    }

    fun audioFile(id: String): File = File(root, "$id.m4a")

    fun metaFile(id: String): File = File(root, "$id.json")

    /**
     * Persist compressed audio + meta. Runs [prune] after write.
     * @return saved meta, or null if [m4aBytes] empty
     */
    fun save(
        m4aBytes: ByteArray,
        durationMs: Long,
        status: SessionStatus,
        language: String,
        sampleRate: Int = 16_000,
        sourcePackage: String? = null,
        text: String? = null,
        error: String? = null,
        id: String = newId(),
        createdAtMs: Long = System.currentTimeMillis(),
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): RecordingMeta? {
        if (m4aBytes.isEmpty()) return null
        root.mkdirs()
        val af = audioFile(id)
        af.writeBytes(m4aBytes)
        val meta =
            RecordingMeta(
                id = id,
                createdAtMs = createdAtMs,
                durationMs = durationMs,
                status = status,
                language = language,
                sourcePackage = sourcePackage,
                text = text,
                error = error,
                audioBytes = m4aBytes.size.toLong(),
                sampleRate = sampleRate,
            )
        writeMeta(meta)
        prune(maxItems, maxBytes, protectId = id)
        return meta
    }

    fun get(id: String): RecordingMeta? {
        val f = metaFile(id)
        if (!f.isFile) return null
        return readMeta(f)
    }

    fun listNewestFirst(): List<RecordingMeta> =
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.createdAtMs }
            ?: emptyList()

    fun updateStatus(
        id: String,
        status: SessionStatus,
        text: String? = null,
        error: String? = null,
        setText: Boolean = false,
        setError: Boolean = false,
    ): RecordingMeta? {
        val current = get(id) ?: return null
        val next =
            current.copy(
                status = status,
                text = if (setText) text else current.text,
                error = if (setError) error else current.error,
                audioBytes = audioFile(id).takeIf { it.isFile }?.length() ?: current.audioBytes,
            )
        writeMeta(next)
        return next
    }

    fun markOk(id: String, text: String): RecordingMeta? =
        updateStatus(
            id,
            status = SessionStatus.OK,
            text = text,
            error = null,
            setText = true,
            setError = true,
        )

    fun markFailed(id: String, error: String): RecordingMeta? =
        updateStatus(
            id,
            status = SessionStatus.FAILED,
            error = error,
            setError = true,
        )

    fun delete(id: String): Boolean {
        var ok = false
        val a = audioFile(id)
        val m = metaFile(id)
        if (a.exists()) ok = a.delete() || ok
        if (m.exists()) ok = m.delete() || ok
        return ok || (!a.exists() && !m.exists())
    }

    fun totalAudioBytes(): Long =
        root.listFiles { f -> f.isFile && f.name.endsWith(".m4a") }
            ?.sumOf { it.length() }
            ?: 0L

    /**
     * Enforce max item count and total M4A bytes.
     * Deletes oldest by createdAt first; never deletes [protectId] in the first pass
     * if something else can free space.
     */
    fun prune(
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        protectId: String? = null,
    ): Int {
        var removed = 0
        fun entries(): MutableList<RecordingMeta> = listNewestFirst().toMutableList()

        var list = entries()
        // Count: drop oldest beyond maxItems (skip protect if others exist)
        while (list.size > maxItems.coerceAtLeast(0)) {
            val victim =
                list
                    .filter { it.id != protectId }
                    .minByOrNull { it.createdAtMs }
                    ?: list.minByOrNull { it.createdAtMs }
                    ?: break
            if (delete(victim.id)) removed++
            list = entries()
        }

        list = entries()
        while (totalAudioBytes() > maxBytes.coerceAtLeast(0L) && list.isNotEmpty()) {
            // Never delete protectId to free space; stop if only it remains (or nothing else).
            val victim =
                list
                    .filter { it.id != protectId }
                    .minByOrNull { it.createdAtMs }
                    ?: break
            if (delete(victim.id)) removed++
            list = entries()
        }
        return removed
    }

    fun readAudioBytes(id: String): ByteArray? {
        val f = audioFile(id)
        if (!f.isFile || f.length() == 0L) return null
        return f.readBytes()
    }

    private fun writeMeta(meta: RecordingMeta) {
        // Hand-rolled JSON so unit tests do not depend on Android org.json stubs.
        metaFile(meta.id).writeText(MetaJson.encode(meta))
    }

    private fun readMeta(file: File): RecordingMeta? =
        try {
            val meta = MetaJson.decode(file.readText()) ?: return null
            val audioLen = audioFile(meta.id).takeIf { it.isFile }?.length() ?: meta.audioBytes
            meta.copy(audioBytes = audioLen)
        } catch (_: Exception) {
            null
        }

    companion object {
        const val DEFAULT_MAX_ITEMS = 50
        const val DEFAULT_MAX_BYTES = 500L * 1024L * 1024L

        fun fromContext(context: Context): RecordingStore =
            RecordingStore(File(context.applicationContext.filesDir, "recordings"))

        fun newId(nowMs: Long = System.currentTimeMillis()): String {
            val fmt =
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
            return fmt.format(Date(nowMs))
        }
    }
}

/** Minimal JSON codec for [RecordingMeta] (no Android JSONObject dependency). */
internal object MetaJson {
    fun encode(meta: RecordingMeta): String =
        buildString {
            append('{')
            append("\"id\":").append(quote(meta.id)).append(',')
            append("\"createdAtMs\":").append(meta.createdAtMs).append(',')
            append("\"durationMs\":").append(meta.durationMs).append(',')
            append("\"status\":").append(quote(meta.status.wire)).append(',')
            append("\"language\":").append(quote(meta.language)).append(',')
            append("\"sourcePackage\":").append(nullableQuote(meta.sourcePackage)).append(',')
            append("\"text\":").append(nullableQuote(meta.text)).append(',')
            append("\"error\":").append(nullableQuote(meta.error)).append(',')
            append("\"audioBytes\":").append(meta.audioBytes).append(',')
            append("\"sampleRate\":").append(meta.sampleRate)
            append('}')
        }

    fun decode(raw: String): RecordingMeta? {
        val id = stringField(raw, "id") ?: return null
        val created = longField(raw, "createdAtMs") ?: return null
        val duration = longField(raw, "durationMs") ?: 0L
        val status = SessionStatus.fromWire(stringField(raw, "status"))
        val language = stringField(raw, "language") ?: "en"
        val sampleRate = longField(raw, "sampleRate")?.toInt() ?: 16_000
        val audioBytes = longField(raw, "audioBytes") ?: 0L
        return RecordingMeta(
            id = id,
            createdAtMs = created,
            durationMs = duration,
            status = status,
            language = language,
            sourcePackage = stringField(raw, "sourcePackage"),
            text = stringField(raw, "text"),
            error = stringField(raw, "error"),
            audioBytes = audioBytes,
            sampleRate = sampleRate,
        )
    }

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }

    private fun nullableQuote(s: String?): String = if (s == null) "null" else quote(s)

    private fun stringField(raw: String, key: String): String? {
        val nullPat = "\"$key\":null"
        if (raw.contains(nullPat)) return null
        val prefix = "\"$key\":\""
        val start = raw.indexOf(prefix)
        if (start < 0) return null
        var i = start + prefix.length
        val sb = StringBuilder()
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"', '\\' -> sb.append(raw[i + 1])
                    else -> sb.append(raw[i + 1])
                }
                i += 2
                continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun longField(raw: String, key: String): Long? {
        val prefix = "\"$key\":"
        val start = raw.indexOf(prefix)
        if (start < 0) return null
        var i = start + prefix.length
        while (i < raw.length && raw[i].isWhitespace()) i++
        val end = raw.indexOfFirstFrom(i) { ch -> ch == ',' || ch == '}' }
        if (end < 0) return null
        return raw.substring(i, end).trim().toLongOrNull()
    }

    private fun String.indexOfFirstFrom(from: Int, pred: (Char) -> Boolean): Int {
        for (i in from until length) {
            if (pred(this[i])) return i
        }
        return -1
    }
}
