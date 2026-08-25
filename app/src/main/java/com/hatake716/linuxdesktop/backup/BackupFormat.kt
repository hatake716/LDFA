package com.hatake716.linuxdesktop.backup

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `.ldfa` container framing (docs/… §5.2):
 * ```
 * +0      magic       "LDFA" + 0x01   (5 bytes; 0x01 = format_version)
 * +5      header_len  uint32 big-endian
 * +9      header_json UTF-8 JSON (always plaintext)
 * +9+len  payload     tar -> gzip -> (encrypt) byte stream
 * end-40  trailer     SHA-256(payload) 32 bytes + payload length uint64 BE
 * ```
 * The header is plaintext so the restore screen can show what the backup is
 * before decrypting anything. The trailer hash is computed while writing and
 * re-checked while extracting, for corruption detection.
 */
object BackupFormat {
    val MAGIC = byteArrayOf('L'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), 0x01)
    const val TRAILER_LEN = 40 // 32-byte digest + 8-byte length
    const val PART_SUFFIX = ".part"

    /** Write magic + length-prefixed header. Returns bytes written. */
    fun writeHeader(out: OutputStream, manifest: BackupManifest): Int {
        val body = manifest.toJsonBytes()
        out.write(MAGIC)
        out.write(u32be(body.size.toLong()))
        out.write(body)
        return MAGIC.size + 4 + body.size
    }

    /** Write the trailer (digest first, then the payload length). */
    fun writeTrailer(out: OutputStream, payloadSha256: ByteArray, payloadLen: Long) {
        require(payloadSha256.size == 32) { "digest must be 32 bytes" }
        out.write(payloadSha256)
        out.write(u64be(payloadLen))
    }

    /**
     * Read and validate magic, then the header JSON. Leaves the stream positioned
     * at the start of the payload. Throws [BackupFormatException] on any mismatch.
     */
    fun readHeader(input: InputStream): BackupManifest {
        val magic = readFully(input, MAGIC.size)
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] ||
            magic[2] != MAGIC[2] || magic[3] != MAGIC[3]
        ) {
            throw BackupFormatException("これはLDFAのバックアップファイルではありません。")
        }
        val version = magic[4].toInt() and 0xFF
        if (version > MAGIC[4].toInt()) {
            throw BackupFormatException("このバックアップは新しいバージョンのLDFAで作成されています。")
        }
        val lenBytes = readFully(input, 4)
        val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) {
            throw BackupFormatException("ファイルが壊れています。")
        }
        val headerBytes = readFully(input, headerLen.toInt())
        val json = try {
            JSONObject(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupFormatException("ファイルが壊れています。")
        }
        return BackupManifest.fromJson(json)
    }

    /** Safe backup file name (docs/… §5.7): keep JP + alnum + `-_`, `_` for the rest. */
    fun safeFileName(displayName: String, timestamp: String, scope: BackupManifest.Scope, encrypted: Boolean): String {
        val safe = sanitizeName(displayName)
        val scopeTag = if (scope == BackupManifest.Scope.DATA) "-data" else ""
        val encTag = if (encrypted) "-enc" else ""
        return "LDFA-$safe-$timestamp$scopeTag$encTag.ldfa"
    }

    /** Keep ASCII alnum, hiragana, katakana, kanji, `-`, `_`; replace others with `_`; cap 40. */
    fun sanitizeName(displayName: String): String {
        val sb = StringBuilder()
        for (ch in displayName) {
            sb.append(if (isSafeNameChar(ch)) ch else '_')
        }
        var s = sb.toString().trim('_')
        // Collapse runs of underscores introduced by the replacement.
        s = s.replace(Regex("_+"), "_")
        if (s.length > 40) s = s.substring(0, 40).trim('_')
        return s.ifBlank { "Debian" }
    }

    private fun isSafeNameChar(ch: Char): Boolean {
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_') return true
        val code = ch.code
        // Hiragana, Katakana, CJK Unified Ideographs, CJK Ext-A, halfwidth katakana.
        return code in 0x3040..0x309F || // Hiragana
            code in 0x30A0..0x30FF || // Katakana
            code in 0x4E00..0x9FFF || // CJK Unified Ideographs
            code in 0x3400..0x4DBF || // CJK Ext-A
            code in 0xFF66..0xFF9D    // Halfwidth Katakana
    }

    private const val MAX_HEADER_BYTES = 1L shl 20 // 1 MiB; headers are tiny

    private fun u32be(v: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()

    private fun u64be(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("unexpected end of backup file")
            off += r
        }
        return buf
    }
}
