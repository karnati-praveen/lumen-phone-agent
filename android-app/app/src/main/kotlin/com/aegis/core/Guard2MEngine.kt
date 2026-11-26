package com.aegis.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject

data class GuardDecision(
    val loaded: Boolean,
    val label: String,
    val probSafe: Double,
    val probConfirm: Double,
    val probBlock: Double,
    val latencyUs: Long
)

/**
 * In-process 2.02M-parameter int8 neural Command & Safety Guard (<2 ms latency).
 * Automatically confirms benign navigation/utility actions while hard-blocking threats.
 */
object Guard2MEngine {
    @Volatile
    private var loadedFlag = false

    init {
        runCatching { System.loadLibrary("aegis_brain") }
    }

    private external fun nativeLoadGuard2M(assetManager: AssetManager): Boolean
    private external fun nativeEvaluateGuard2M(input: String): String

    fun ensureLoaded(context: Context): Boolean {
        if (loadedFlag) return true
        val ok = runCatching { nativeLoadGuard2M(context.assets) }.getOrDefault(false)
        loadedFlag = ok
        return ok
    }

    fun evaluate(
        context: Context,
        actionType: String,
        targetPackage: String,
        userCommand: String,
        payloadText: String
    ): GuardDecision {
        if (!ensureLoaded(context)) {
            return GuardDecision(false, "HUMAN_CONFIRM", 0.0, 1.0, 0.0, 0L)
        }
        val query = "$actionType $targetPackage $userCommand $payloadText".trim()
        val raw = runCatching { nativeEvaluateGuard2M(query) }.getOrDefault("{}")
        Log.i("AegisGuard2M", raw)
        return runCatching {
            val obj = JSONObject(raw)
            GuardDecision(
                loaded = obj.optBoolean("loaded", false),
                label = obj.optString("label", "HUMAN_CONFIRM"),
                probSafe = obj.optDouble("p_safe", 0.0),
                probConfirm = obj.optDouble("p_confirm", 1.0),
                probBlock = obj.optDouble("p_block", 0.0),
                latencyUs = obj.optLong("latency_us", 0L)
            )
        }.getOrElse {
            GuardDecision(false, "HUMAN_CONFIRM", 0.0, 1.0, 0.0, 0L)
        }
    }
}
