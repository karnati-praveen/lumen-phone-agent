package com.aegis.models

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aegis.ipc.IBrainEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val filename: String,
    val url: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    val slotId: Int,
    val mmprojFilename: String? = null,
    val mmprojUrl: String? = null,
    val mmprojExpectedBytes: Long = 0L,
    val mmprojExpectedSha256: String = ""
) {
    val sizeMb: Int get() = ((expectedBytes + mmprojExpectedBytes) / (1024L * 1024L)).toInt()
}

object ModelCatalog {
    val SMOLVLM_500M = ModelCatalogEntry(
        id = "smolvlm-500m-vlm",
        displayName = "SmolVLM 500M Vision (4 GB+ VLM)",
        filename = "SmolVLM-500M-Instruct-Q8_0.gguf",
        url = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/SmolVLM-500M-Instruct-Q8_0.gguf?download=true",
        expectedBytes = 436_806_912L,
        expectedSha256 = "9d4612de6a42214499e301494a3ecc2be0abdd9de44e663bda63f1152fad1bf4",
        slotId = 1,
        mmprojFilename = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
        mmprojUrl = "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf?download=true",
        mmprojExpectedBytes = 108_783_360L,
        mmprojExpectedSha256 = "d1eb8b6b23979205fdf63703ed10f788131a3f812c7b1f72e0119d5d81295150"
    )

    val QWEN_05B = ModelCatalogEntry(
        id = "qwen2.5-0.5b-q4",
        displayName = "Qwen 2.5 0.5B Instruct (Fast)",
        filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
        expectedBytes = 491_400_032L,
        expectedSha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        slotId = 2
    )

    val QWEN_15B_Q3 = ModelCatalogEntry(
        id = "qwen2.5-1.5b-q3",
        displayName = "Qwen 2.5 1.5B (6 GB Balanced)",
        filename = "qwen2.5-1.5b-instruct-q3_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q3_k_m.gguf?download=true",
        expectedBytes = 924_455_968L,
        expectedSha256 = "58cb5c05ecef48e82961f1a2be6544145ea26136f69dddda4bbbd092f0e4b993",
        slotId = 2
    )

    val QWEN_15B = ModelCatalogEntry(
        id = "qwen2.5-1.5b-q4",
        displayName = "Qwen 2.5 1.5B Instruct (Smart)",
        filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
        expectedBytes = 1_117_320_736L,
        expectedSha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        slotId = 2
    )

    val entries: List<ModelCatalogEntry> = listOf(QWEN_05B, QWEN_15B_Q3, QWEN_15B, SMOLVLM_500M)

    fun recommendedForRamGb(totalRamGb: Int): ModelCatalogEntry = SMOLVLM_500M

    fun findById(id: String?): ModelCatalogEntry =
        entries.firstOrNull { it.id == id } ?: SMOLVLM_500M
}

object ModelRepository {
    const val WORK_NAME = "aegis_gguf_download"

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, entry: ModelCatalogEntry): File =
        File(modelsDir(context), entry.filename)

    fun mmprojFile(context: Context, entry: ModelCatalogEntry): File? =
        entry.mmprojFilename?.let { File(modelsDir(context), it) }

    fun getReadyModel(context: Context): ModelCatalogEntry? {
        // Prefer VLM / larger downloaded model if verified on disk, else smaller fast model
        for (entry in ModelCatalog.entries.reversed()) {
            val f = modelFile(context, entry)
            val m = mmprojFile(context, entry)
            val mainReady = f.exists() && f.length() == entry.expectedBytes
            val mmprojReady = (m == null) || (m.exists() && m.length() == entry.mmprojExpectedBytes)
            if (mainReady && mmprojReady) {
                return entry
            }
        }
        return null
    }

    fun enqueueDownload(context: Context, entry: ModelCatalogEntry) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to entry.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun deleteDownloadedModels(context: Context) {
        modelsDir(context).listFiles()?.forEach { it.delete() }
    }

    private val loadLock = Any()

    fun loadReadyModelIntoBrain(context: Context, engine: IBrainEngine): ModelCatalogEntry? = synchronized(loadLock) {
        val entry = getReadyModel(context) ?: return null
        val tel = runCatching { engine.getEngineTelemetry() }.getOrDefault("")
        if (tel.contains("\"engine\":\"llama.cpp+mtmd-vlm\"") && tel.contains("\"vlm_vision\":true")) {
            return entry
        }
        val file = modelFile(context, entry)
        val ok = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                engine.loadModelFromFd(pfd, file.length(), entry.slotId)
            }
        }.getOrDefault(false)
        if (!ok) return null
        val mmFile = mmprojFile(context, entry)
        if (mmFile != null && mmFile.exists()) {
            runCatching {
                ParcelFileDescriptor.open(mmFile, ParcelFileDescriptor.MODE_READ_ONLY).use { mpfd ->
                    engine.loadVisionProjectorFromFd(mpfd, mmFile.length())
                }
            }
        }
        return entry
    }
}

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
        val entry = ModelCatalog.findById(modelId)
        val targetFile = ModelRepository.modelFile(applicationContext, entry)

        val mainErr = downloadAndVerifyFile(
            entryId = entry.id,
            url = entry.url,
            targetFile = targetFile,
            expectedBytes = entry.expectedBytes,
            expectedSha256 = entry.expectedSha256,
            totalMb = entry.sizeMb,
            label = "GGUF"
        )
        if (mainErr != null) {
            return@withContext Result.failure(workDataOf(KEY_STATUS to mainErr))
        }

        val mmFile = ModelRepository.mmprojFile(applicationContext, entry)
        if (mmFile != null && !entry.mmprojUrl.isNullOrBlank()) {
            val mmErr = downloadAndVerifyFile(
                entryId = entry.id,
                url = entry.mmprojUrl,
                targetFile = mmFile,
                expectedBytes = entry.mmprojExpectedBytes,
                expectedSha256 = entry.mmprojExpectedSha256,
                totalMb = entry.sizeMb,
                label = "Vision Projector"
            )
            if (mmErr != null) {
                return@withContext Result.failure(workDataOf(KEY_STATUS to mmErr))
            }
        }

        Result.success(
            workDataOf(
                KEY_MODEL_ID to entry.id,
                KEY_STATUS to "Downloaded & verified (${entry.sizeMb} MB)"
            )
        )
    }

    private suspend fun downloadAndVerifyFile(
        entryId: String,
        url: String,
        targetFile: File,
        expectedBytes: Long,
        expectedSha256: String,
        totalMb: Int,
        label: String
    ): String? {
        if (targetFile.exists() && targetFile.length() == expectedBytes) {
            if (verifySha256(targetFile, expectedSha256)) {
                return null
            }
            targetFile.delete()
        }

        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var existingBytes = if (partFile.exists()) partFile.length() else 0L
        if (existingBytes > expectedBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = openWithRedirects(url, existingBytes)
            val code = conn.responseCode
            if (code == 416 || (existingBytes > 0L && code == 200)) {
                partFile.delete()
                existingBytes = 0L
                conn.disconnect()
                conn = openWithRedirects(url, 0L)
            }
            val finalCode = conn.responseCode
            if (finalCode !in 200..299) {
                return "$label HTTP error $finalCode"
            }

            var downloaded = existingBytes
            var lastReportMs = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partFile, existingBytes > 0L).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) return "Download cancelled"
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 400L || downloaded >= expectedBytes) {
                            lastReportMs = now
                            val pct = ((downloaded * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                            setProgressAsync(
                                workDataOf(
                                    KEY_MODEL_ID to entryId,
                                    KEY_PROGRESS_PCT to pct,
                                    KEY_DOWNLOADED_MB to (downloaded / (1024L * 1024L)).toInt(),
                                    KEY_TOTAL_MB to totalMb,
                                    KEY_STATUS to "Downloading $label ($pct%)"
                                )
                            )
                        }
                    }
                    output.fd.sync()
                }
            }

            setProgressAsync(
                workDataOf(
                    KEY_MODEL_ID to entryId,
                    KEY_PROGRESS_PCT to 100,
                    KEY_DOWNLOADED_MB to totalMb,
                    KEY_TOTAL_MB to totalMb,
                    KEY_STATUS to "Verifying $label SHA-256..."
                )
            )

            if (!hasGgufMagic(partFile) || !verifySha256(partFile, expectedSha256)) {
                partFile.delete()
                return "$label SHA-256 or GGUF header check failed"
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            null
        } catch (t: Throwable) {
            t.message ?: "$label download failed"
        } finally {
            conn?.disconnect()
        }
    }

    private fun openWithRedirects(initialUrl: String, rangeStart: Long): HttpURLConnection {
        var currentUrl = initialUrl
        for (hop in 0 until 8) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AegisPhoneAgent/3.0")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect without Location")
                currentUrl = URL(URL(currentUrl), loc).toString()
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun hasGgufMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        FileInputStream(file).use { fis ->
            val hdr = ByteArray(4)
            if (fis.read(hdr) != 4) return false
            return hdr[0] == 'G'.code.toByte() &&
                hdr[1] == 'G'.code.toByte() &&
                hdr[2] == 'U'.code.toByte() &&
                hdr[3] == 'F'.code.toByte()
        }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS_PCT = "progress_pct"
        const val KEY_DOWNLOADED_MB = "downloaded_mb"
        const val KEY_TOTAL_MB = "total_mb"
        const val KEY_STATUS = "status_text"
    }
}
