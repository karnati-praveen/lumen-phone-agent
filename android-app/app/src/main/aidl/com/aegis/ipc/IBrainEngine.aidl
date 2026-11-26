package com.aegis.ipc;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

/**
 * Strict 4-method Binder IPC contract between Process 1 (Trusted Shell + Rust Broker)
 * and Process 2 (Untrusted AI Brain running in android:isolatedProcess="true").
 */
interface IBrainEngine {
    /**
     * Passes a SHA-256 verified read-only ParcelFileDescriptor (MODE_READ_ONLY).
     * Process 2 maps weights via mmap(PROT_READ, MAP_PRIVATE) without storage permissions.
     */
    boolean loadModelFromFd(in ParcelFileDescriptor weightFd, long byteLength, int modelSlotId);

    /**
     * Loads multimodal vision projector (mmproj GGUF) from a verified read-only FD.
     */
    boolean loadVisionProjectorFromFd(in ParcelFileDescriptor mmprojFd, long byteLength);

    /**
     * Runs inference over sanitized DOM JSON + optional read-only 768x768 RGB888 SharedMemory (ashmem).
     * Process 1 zeroes the ashmem buffer immediately upon return.
     */
    String runInference(
        String requestUuid,
        String sanitizedPromptJson,
        in SharedMemory optionalRgbFrame,
        long frameBytes
    );

    /**
     * Invokes madvise(MADV_DONTNEED) and frees KV/compute scratchpads (<150 ms RSS drop).
     */
    void unloadAndTrim(boolean urgentTrimLow);

    /**
     * Sets atomic C++ abort flag inside token decode loop (<20 ms Kill Switch halt).
     */
    oneway void cancelInference(String activeRequestUuid);

    /**
     * Updates thermal throttling level (NORMAL=0, LIGHT=1, MODERATE=2, SEVERE=3).
     */
    oneway void setThermalLevel(int thermalLevel);

    /**
     * Returns live Process 2 isolation & memory/thermal telemetry JSON.
     */
    String getEngineTelemetry();
}
