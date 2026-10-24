package com.tercuman.keyboard.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.tercuman.keyboard.Settings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

object DefaultModel {
    const val DISPLAY_NAME = "TranslateGemma 4B (Q4_K_M)"
    const val FILE_NAME = "translategemma-4b-it.Q4_K_M.gguf"

    const val URL = "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/" +
        "d37868f759d2f817a43d28dd80cf9f982ffa62fd/translategemma-4b-it.Q4_K_M.gguf"
    const val SHA256 = "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330"
    const val SIZE_BYTES = 2_489_909_760L

    const val LICENSE_URL = "https://ai.google.dev/gemma/terms"
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Running(val downloaded: Long, val total: Long) : DownloadState()
    data class Paused(val downloaded: Long, val total: Long, val reason: String) : DownloadState()
    data object Verifying : DownloadState()
    data object Done : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}

class ModelStore(private val context: Context) {
    private val settings = Settings(context)
    private val dm = context.getSystemService(DownloadManager::class.java)

    private val dir: File
        get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }

    val defaultModelFile: File get() = File(dir, DefaultModel.FILE_NAME)
    private val partFile: File get() = File(dir, DefaultModel.FILE_NAME + ".part")
    private val verifiedMarker: File get() = File(dir, DefaultModel.FILE_NAME + ".ok")

    fun installedModel(): File? {
        settings.customModelPath?.let { return File(it) }
        val f = defaultModelFile
        return if (f.isFile && verifiedMarker.isFile && f.length() == DefaultModel.SIZE_BYTES) f else null
    }

    fun isInstalled(): Boolean = installedModel() != null

    fun startDownload(): Long {
        val existing = settings.downloadId
        if (existing >= 0 && queryRaw(existing) != null) return existing

        partFile.delete()
        val req = DownloadManager.Request(Uri.parse(DefaultModel.URL))
            .setTitle("Tercüman Klavye")
            .setDescription(DefaultModel.DISPLAY_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "models", partFile.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        settings.downloadId = id
        return id
    }

    fun cancelDownload() {
        val id = settings.downloadId
        if (id >= 0) dm.remove(id)
        settings.downloadId = -1
        partFile.delete()
    }

    fun pollDownload(onVerifying: () -> Unit = {}): DownloadState {
        if (isInstalled()) return DownloadState.Done
        val id = settings.downloadId
        if (id < 0) return DownloadState.Idle
        val q = queryRaw(id) ?: run {
            settings.downloadId = -1
            return DownloadState.Failed("Download was cancelled.")
        }
        return when (q.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                onVerifying()
                finishDownload()
            }
            DownloadManager.STATUS_FAILED -> {
                dm.remove(id)
                settings.downloadId = -1
                DownloadState.Failed("Download failed (code ${q.reason}). Check the internet connection and try again.")
            }
            DownloadManager.STATUS_PAUSED -> DownloadState.Paused(q.downloaded, q.total, pauseReason(q.reason))
            else -> DownloadState.Running(q.downloaded, q.total.takeIf { it > 0 } ?: DefaultModel.SIZE_BYTES)
        }
    }

    private fun finishDownload(): DownloadState {
        val id = settings.downloadId
        val f = partFile
        if (!f.isFile) {
            settings.downloadId = -1
            return DownloadState.Failed("Downloaded file is missing.")
        }
        val ok = runCatching { f.inputStream().use { sha256(it) } == DefaultModel.SHA256 }.getOrDefault(false)
        if (!ok) {
            dm.remove(id)
            f.delete()
            settings.downloadId = -1
            return DownloadState.Failed("Downloaded file is damaged (checksum mismatch). Please download again.")
        }
        defaultModelFile.delete()
        if (!f.renameTo(defaultModelFile)) {
            return DownloadState.Failed("Could not save the model file.")
        }
        verifiedMarker.writeText(DefaultModel.SHA256)

        settings.downloadId = -1
        runCatching { dm.remove(id) }
        return DownloadState.Done
    }

    @Throws(IOException::class)
    fun importModel(uri: Uri, onProgress: (Long) -> Unit): File {
        val target = File(dir, "custom.gguf")
        val tmp = File(dir, "custom.gguf.tmp")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file.")
        input.use { inp ->
            val header = ByteArray(4)
            if (inp.read(header) != 4 || String(header, Charsets.US_ASCII) != "GGUF") {
                throw IOException("This is not a GGUF model file.")
            }
            tmp.outputStream().use { out ->
                out.write(header)
                val buf = ByteArray(1 shl 20)
                var total = 4L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        }
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("Could not save the model file.")
        settings.customModelPath = target.absolutePath
        return target
    }

    fun useDefaultModel() {
        settings.customModelPath?.let { File(it).delete() }
        settings.customModelPath = null
    }

    fun deleteDefaultModel() {
        defaultModelFile.delete()
        verifiedMarker.delete()
    }

    fun freeSpaceBytes(): Long = dir.usableSpace

    fun isExternalStorageUsable(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    private data class Raw(val status: Int, val reason: Int, val downloaded: Long, val total: Long)

    private fun queryRaw(id: Long): Raw? {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            fun col(name: String) = c.getColumnIndexOrThrow(name)
            return Raw(
                status = c.getInt(col(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(col(DownloadManager.COLUMN_REASON)),
                downloaded = c.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
        return null
    }

    private fun pauseReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for internet connection…"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for WiFi…"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection lost, retrying…"
        else -> "Paused"
    }

    companion object {
        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
