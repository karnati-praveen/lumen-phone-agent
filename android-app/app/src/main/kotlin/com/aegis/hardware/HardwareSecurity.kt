package com.aegis.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore

enum class HardwareProfile(
    val code: String,
    val label: String,
    val totalRamGb: Int,
    val safeCeilingMb: Int,
    val idleCapMb: Int,
    val idleTtlSeconds: Int,
    val coreDiskGb: Double,
    val tier1Model: String,
    val tier2Model: String,
    val maxContextTokens: Int,
    val allowGlassBlurShaders: Boolean
) {
    PROFILE_A("A", "4 GB Budget", 4, 1100, 45, 30, 1.26, "Qwen 2.5 0.5B (Q4_K_M)", "Single-Tier Unified", 2048, false),
    PROFILE_B("B", "6 GB Mid-Range", 6, 1700, 45, 45, 2.71, "Qwen 2.5 1.5B (Q3_K_M · 881 MB)", "Qwen 2.5 0.5B Fallback", 2048, false),
    PROFILE_C("C", "8 GB Mainstream", 8, 2350, 45, 60, 2.21, "Qwen 2.5 1.5B (Q4_K_M)", "Qwen 2.5 1.5B Unified", 4096, true),
    PROFILE_D("D", "12 GB+ Flagship", 12, 3600, 50, 90, 2.73, "Qwen 2.5 1.5B (Q4_K_M)", "Gemma 4 E2B Unified", 8192, true)
}

data class MemoryAdmissionDecision(
    val allowed: Boolean,
    val recommendedContextTokens: Int,
    val availableMb: Int,
    val reason: String
)

object HardwareProfiler {
    fun detectProfile(context: Context): HardwareProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = (info.totalMem / (1024L * 1024L)).toInt()
        return when {
            totalMb <= 4_600 -> HardwareProfile.PROFILE_A
            totalMb <= 6_700 -> HardwareProfile.PROFILE_B
            totalMb <= 10_000 -> HardwareProfile.PROFILE_C
            else -> HardwareProfile.PROFILE_D
        }
    }

    fun estimatePeakMb(mappedBytes: Long, contextTokens: Int): Int {
        val weightsMb = if (mappedBytes > 0L) (mappedBytes / (1024L * 1024L)).toInt() else 520
        // mmap pages + Q8_0 KV cache + ggml compute scratch (~140-220 MB overhead)
        val kvAndScratchMb = 140 + (contextTokens / 256) * 16
        return weightsMb + kvAndScratchMb
    }

    /**
     * Pre-inference Memory Admission Controller (closes Android 17 MemoryLimiter:AnonSwap risk).
     * Automatically downgrades KV context window if headroom < 320 MB before waking Tier 2.
     */
    fun checkMemoryAdmission(
        context: Context,
        profile: HardwareProfile,
        estimatedPeakMb: Int
    ): MemoryAdmissionDecision {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val availMb = (info.availMem / (1024L * 1024L)).toInt()

        if (estimatedPeakMb > profile.safeCeilingMb) {
            return MemoryAdmissionDecision(false, 768, availMb, "Exceeds ${profile.safeCeilingMb} MB hard RAM ceiling (<27.5%)")
        }
        if (info.lowMemory || availMb < 160) {
            return MemoryAdmissionDecision(false, 512, availMb, "System low-memory pressure ($availMb MB free) — forcing TRIM_LOW")
        }
        val ctx = when {
            availMb < 260 -> 512
            availMb < 420 && profile.maxContextTokens > 768 -> 768
            else -> profile.maxContextTokens
        }
        return MemoryAdmissionDecision(true, ctx, availMb, "Admitted within Android 17 safe budget")
    }
}

/**
 * Hardware-backed AES-256-GCM key wrapper for the persisted ChaCha20-Poly1305 audit vault key.
 */
object VaultKeyManager {
    private const val VAULT_MASTER_ALIAS = "aegis_vault_aes256_gcm_master"
    private const val KEY_FILE_NAME = "aegis_vault.key"

    fun getOrCreateVaultKeyHex(context: Context): String {
        val keyFile = java.io.File(context.filesDir, KEY_FILE_NAME)
        ensureAesMasterKey()
        if (keyFile.exists() && keyFile.length() > 12L) {
            val unsealed = runCatching { unsealKey(keyFile.readBytes()) }.getOrNull()
            if (unsealed != null && unsealed.size == 32) {
                return unsealed.joinToString("") { "%02x".format(it) }
            }
        }
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        keyFile.writeBytes(sealKey(raw))
        return raw.joinToString("") { "%02x".format(it) }
    }

    fun deletePersistedKey(context: Context) {
        java.io.File(context.filesDir, KEY_FILE_NAME).delete()
    }

    private fun ensureAesMasterKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(VAULT_MASTER_ALIAS)) return
        val kg = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            VAULT_MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        kg.generateKey()
    }

    private fun sealKey(raw: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secretKey = ks.getKey(VAULT_MASTER_ALIAS, null) as javax.crypto.SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(raw)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    private fun unsealKey(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ciphertext = blob.copyOfRange(1 + ivLen, blob.size)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secretKey = ks.getKey(VAULT_MASTER_ALIAS, null) as javax.crypto.SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}

class ThermalMonitor(private val context: Context, private val onLevelChanged: (Int) -> Unit) {
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cb = PowerManager.OnThermalStatusChangedListener { status ->
            val mapped = when {
                status >= PowerManager.THERMAL_STATUS_SEVERE -> 3
                status >= PowerManager.THERMAL_STATUS_MODERATE -> 2
                status >= PowerManager.THERMAL_STATUS_LIGHT -> 1
                else -> 0
            }
            onLevelChanged(mapped)
        }
        listener = cb
        pm.addThermalStatusListener(cb)
    }

    fun stop() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        listener?.let { pm.removeThermalStatusListener(it) }
        listener = null
    }
}
