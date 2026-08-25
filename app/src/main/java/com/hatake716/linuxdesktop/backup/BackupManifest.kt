package com.hatake716.linuxdesktop.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * The plaintext header JSON of a `.ldfa` backup (docs/… §5.3). Kept as a plain
 * data model with org.json (de)serialization — no kotlinx.serialization dependency
 * is added, matching the "no extra AAR" decision that also picked gzip over zstd.
 *
 * Unknown fields are ignored on read so a header written by a newer LDFA still
 * parses (as long as the format version is understood).
 */
data class BackupManifest(
    val formatVersion: Int,
    val createdAt: String,
    val app: AppInfo,
    val sourceDevice: SourceDevice,
    val container: ContainerInfo,
    val scope: Scope,
    val payload: Payload,
    val encryption: Encryption?,
    val includes: Includes,
    val excludes: List<String>,
) {
    data class AppInfo(val versionName: String, val versionCode: Int, val applicationId: String)

    data class SourceDevice(val model: String, val androidSdk: Int, val abi: String)

    data class ContainerInfo(
        val id: String,
        val displayName: String,
        val distro: String,
        val guestArch: String,
        val prefix: String,
    )

    enum class Scope(val wire: String) {
        FULL("full"),
        DATA("data"),
        ;

        companion object {
            fun fromWire(v: String?): Scope = entries.firstOrNull { it.wire == v } ?: FULL
        }
    }

    data class Payload(
        val codec: String,
        val level: Int,
        val tarFormat: String,
        val uncompressedBytes: Long,
        val entryCount: Long,
    )

    /** Present only when the payload is encrypted; null for plaintext backups. */
    data class Encryption(
        val algorithm: String,
        val kdf: String,
        val saltB64: String,
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
        val chunkSize: Int,
        val noncePrefixB64: String,
    )

    data class Includes(val rootfs: Boolean, val hostMetadata: Boolean, val androidShared: Boolean)

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT_ID)
        put("format_version", formatVersion)
        put("created_at", createdAt)
        put("app", JSONObject().apply {
            put("version_name", app.versionName)
            put("version_code", app.versionCode)
            put("application_id", app.applicationId)
        })
        put("source_device", JSONObject().apply {
            put("model", sourceDevice.model)
            put("android_sdk", sourceDevice.androidSdk)
            put("abi", sourceDevice.abi)
        })
        put("container", JSONObject().apply {
            put("id", container.id)
            put("display_name", container.displayName)
            put("distro", container.distro)
            put("guest_arch", container.guestArch)
            put("prefix", container.prefix)
        })
        put("scope", scope.wire)
        put("payload", JSONObject().apply {
            put("codec", payload.codec)
            put("level", payload.level)
            put("tar_format", payload.tarFormat)
            put("uncompressed_bytes", payload.uncompressedBytes)
            put("entry_count", payload.entryCount)
        })
        put("encryption", encryption?.let {
            JSONObject().apply {
                put("algorithm", it.algorithm)
                put("kdf", it.kdf)
                put("salt_b64", it.saltB64)
                put("memory_kib", it.memoryKib)
                put("iterations", it.iterations)
                put("parallelism", it.parallelism)
                put("chunk_size", it.chunkSize)
                put("nonce_prefix_b64", it.noncePrefixB64)
            }
        } ?: JSONObject.NULL)
        put("includes", JSONObject().apply {
            put("rootfs", includes.rootfs)
            put("host_metadata", includes.hostMetadata)
            put("android_shared", includes.androidShared)
        })
        put("excludes", JSONArray(excludes))
    }

    fun toJsonBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val FORMAT_ID = "ldfa-backup"
        const val FORMAT_VERSION = 1

        /** Parse a header JSON. Throws [BackupFormatException] on a malformed body. */
        fun fromJson(json: JSONObject): BackupManifest {
            if (json.optString("format") != FORMAT_ID) {
                throw BackupFormatException("これはLDFAのバックアップファイルではありません。")
            }
            val app = json.getJSONObject("app")
            val dev = json.getJSONObject("source_device")
            val c = json.getJSONObject("container")
            val p = json.getJSONObject("payload")
            val inc = json.getJSONObject("includes")
            val enc = json.optJSONObject("encryption")
            val excludesArr = json.optJSONArray("excludes") ?: JSONArray()
            return BackupManifest(
                formatVersion = json.getInt("format_version"),
                createdAt = json.optString("created_at"),
                app = AppInfo(
                    versionName = app.optString("version_name"),
                    versionCode = app.optInt("version_code"),
                    applicationId = app.optString("application_id"),
                ),
                sourceDevice = SourceDevice(
                    model = dev.optString("model"),
                    androidSdk = dev.optInt("android_sdk"),
                    abi = dev.optString("abi"),
                ),
                container = ContainerInfo(
                    id = c.optString("id"),
                    displayName = c.optString("display_name"),
                    distro = c.optString("distro"),
                    guestArch = c.optString("guest_arch"),
                    prefix = c.optString("prefix"),
                ),
                scope = Scope.fromWire(json.optString("scope")),
                payload = Payload(
                    codec = p.optString("codec", "gzip"),
                    level = p.optInt("level", 6),
                    tarFormat = p.optString("tar_format", "ustar"),
                    uncompressedBytes = p.optLong("uncompressed_bytes"),
                    entryCount = p.optLong("entry_count"),
                ),
                encryption = enc?.let {
                    Encryption(
                        algorithm = it.optString("algorithm"),
                        kdf = it.optString("kdf"),
                        saltB64 = it.optString("salt_b64"),
                        memoryKib = it.optInt("memory_kib"),
                        iterations = it.optInt("iterations"),
                        parallelism = it.optInt("parallelism"),
                        chunkSize = it.optInt("chunk_size"),
                        noncePrefixB64 = it.optString("nonce_prefix_b64"),
                    )
                },
                includes = Includes(
                    rootfs = inc.optBoolean("rootfs", true),
                    hostMetadata = inc.optBoolean("host_metadata", true),
                    androidShared = inc.optBoolean("android_shared", false),
                ),
                excludes = (0 until excludesArr.length()).map { excludesArr.getString(it) },
            )
        }
    }
}

/** Thrown when a `.ldfa` file's structure is invalid; messages are user-facing JP. */
class BackupFormatException(message: String) : Exception(message)
