package com.aegis.core

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import com.aegis.brain.IsolatedBrainService
import com.aegis.hardware.HardwareProfiler
import com.aegis.hardware.HardwareProfile
import com.aegis.hardware.VaultKeyManager
import com.aegis.ipc.IBrainEngine
import com.aegis.models.ModelCatalogEntry
import com.aegis.models.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.aegis_broker.AegisBroker
import uniffi.aegis_broker.createBroker
import java.io.File

class AegisApplication : Application() {

    @Volatile
    lateinit var broker: AegisBroker
        private set

    lateinit var detectedProfile: HardwareProfile
        private set

    @Volatile
    var brainEngine: IBrainEngine? = null
        private set

    val brainBoundState = mutableStateOf(false)
    val lastBrainNoteState = mutableStateOf("Standby")
    val brainTelemetryState = mutableStateOf("{}")
    val readyModelState = mutableStateOf<ModelCatalogEntry?>(null)

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val brainConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val engine = IBrainEngine.Stub.asInterface(service)
            brainEngine = engine
            brainBoundState.value = true
            lastBrainNoteState.value = "Ready"
            appScope.launch {
                val loaded = ModelRepository.loadReadyModelIntoBrain(this@AegisApplication, engine)
                if (loaded != null) {
                    readyModelState.value = loaded
                    lastBrainNoteState.value = "${loaded.displayName} active"
                }
                refreshBrainTelemetry()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            brainEngine = null
            brainBoundState.value = false
            lastBrainNoteState.value = "Reconnecting..."
            brainTelemetryState.value = "{}"
            ensureBrainBound()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Process 2 (android:isolatedProcess="true") is kernel-sandboxed with zero
        // Keystore or filesystem access; only Process 1 initializes the Rust Broker & Vault.
        if (android.os.Process.isIsolated()) return
        detectedProfile = HardwareProfiler.detectProfile(this)
        initBroker()
        ensureBrainBound()
    }

    fun ensureBrainBound() {
        if (brainEngine != null && brainBoundState.value) return
        runCatching {
            bindService(
                Intent(this, IsolatedBrainService::class.java),
                brainConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        }
    }

    fun refreshBrainTelemetry() {
        brainTelemetryState.value = runCatching {
            brainEngine?.getEngineTelemetry() ?: "{}"
        }.getOrElse { "{}" }
    }

    fun initBroker() {
        val vaultFile = File(filesDir, "aegis_vault.enc")
        val keyHex = VaultKeyManager.getOrCreateVaultKeyHex(this)
        broker = createBroker(vaultFile.absolutePath, keyHex)
    }

    fun resetVault() {
        File(filesDir, "aegis_vault.enc").delete()
        VaultKeyManager.deletePersistedKey(this)
        initBroker()
    }
}
