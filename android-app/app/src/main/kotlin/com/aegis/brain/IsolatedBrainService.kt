package com.aegis.brain

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import com.aegis.ipc.IBrainEngine

object NativeBrainBridge {
    init {
        System.loadLibrary("aegis_brain")
    }

    external fun nativeLoadModelFromFd(fd: Int, byteLen: Long, slotId: Int): Boolean
    external fun nativeLoadMmprojFromFd(fd: Int, byteLen: Long): Boolean
    external fun nativeRunInference(reqUuid: String, sanitizedPrompt: String, sharedMemory: SharedMemory?, frameBytes: Long): String
    external fun nativeUnloadAndTrim(urgentTrimLow: Boolean)
    external fun nativeCancelInference()
    external fun nativeSetThermalLevel(level: Int)
    external fun nativeGetTelemetryJson(): String
}

/**
 * PROCESS 2: Untrusted AI Brain running under android:isolatedProcess="true".
 * Kernel UID sandbox guarantees ZERO internet sockets, ZERO storage path access, ZERO clipboard.
 */
class IsolatedBrainService : Service() {

    private val binder = object : IBrainEngine.Stub() {
        override fun loadModelFromFd(
            weightFd: ParcelFileDescriptor,
            byteLength: Long,
            modelSlotId: Int
        ): Boolean {
            return weightFd.use { pfd ->
                NativeBrainBridge.nativeLoadModelFromFd(pfd.fd, byteLength, modelSlotId)
            }
        }

        override fun loadVisionProjectorFromFd(
            mmprojFd: ParcelFileDescriptor,
            byteLength: Long
        ): Boolean {
            return mmprojFd.use { pfd ->
                NativeBrainBridge.nativeLoadMmprojFromFd(pfd.fd, byteLength)
            }
        }

        override fun runInference(
            requestUuid: String,
            sanitizedPromptJson: String,
            optionalRgbFrame: SharedMemory?,
            frameBytes: Long
        ): String {
            return NativeBrainBridge.nativeRunInference(requestUuid, sanitizedPromptJson, optionalRgbFrame, frameBytes)
        }

        override fun unloadAndTrim(urgentTrimLow: Boolean) {
            NativeBrainBridge.nativeUnloadAndTrim(urgentTrimLow)
        }

        override fun cancelInference(activeRequestUuid: String) {
            NativeBrainBridge.nativeCancelInference()
        }

        override fun setThermalLevel(thermalLevel: Int) {
            NativeBrainBridge.nativeSetThermalLevel(thermalLevel)
        }

        override fun getEngineTelemetry(): String {
            val nativeJson = NativeBrainBridge.nativeGetTelemetryJson()
            val pid = android.os.Process.myPid()
            val uid = android.os.Process.myUid()
            val isolated = android.os.Process.isIsolated()
            return """{"pid":$pid,"uid":$uid,"is_isolated":$isolated,"engine":$nativeJson}"""
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
            NativeBrainBridge.nativeUnloadAndTrim(true)
        }
    }
}
