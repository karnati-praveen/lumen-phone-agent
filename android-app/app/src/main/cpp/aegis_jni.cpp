#include "brain_engine.hpp"
#include "guard_2m.hpp"
#include "ashmem_guard.hpp"

#include <android/asset_manager_jni.h>
#include <android/sharedmem_jni.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <memory>

namespace {
std::unique_ptr<aegis::BrainEngine> g_engine = std::make_unique<aegis::BrainEngine>();
std::unique_ptr<aegis::Guard2MEngine> g_guard = std::make_unique<aegis::Guard2MEngine>();
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeLoadModelFromFd(
    JNIEnv*, jobject, jint fd, jlong byteLen, jint slotId
) {
    return g_engine->load_model_from_fd(
        static_cast<int>(fd),
        static_cast<std::size_t>(byteLen),
        static_cast<aegis::ModelSlot>(slotId)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeLoadMmprojFromFd(
    JNIEnv*, jobject, jint fd, jlong byteLen
) {
    return g_engine->load_mmproj_from_fd(
        static_cast<int>(fd),
        static_cast<std::size_t>(byteLen)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeRunInference(
    JNIEnv* env,
    jobject,
    jstring reqUuid,
    jstring sanitizedPrompt,
    jobject sharedMemory,
    jlong frameBytes
) {
    const char* uuid_c = env->GetStringUTFChars(reqUuid, nullptr);
    const char* prompt_c = env->GetStringUTFChars(sanitizedPrompt, nullptr);
    std::string uuid_str = uuid_c ? uuid_c : "";
    std::string prompt_str = prompt_c ? prompt_c : "";
    if (uuid_c) env->ReleaseStringUTFChars(reqUuid, uuid_c);
    if (prompt_c) env->ReleaseStringUTFChars(sanitizedPrompt, prompt_c);

    const uint8_t* frame_ptr = nullptr;
    void* mapped_frame = nullptr;
    int dup_fd = -1;
    if (sharedMemory != nullptr && frameBytes == static_cast<jlong>(aegis::kAshmemFrameBytes)) {
        dup_fd = ASharedMemory_dupFromJava(env, sharedMemory);
        if (dup_fd >= 0) {
            mapped_frame = ::mmap(nullptr, static_cast<std::size_t>(frameBytes), PROT_READ, MAP_SHARED, dup_fd, 0);
            if (mapped_frame != MAP_FAILED) {
                frame_ptr = static_cast<const uint8_t*>(mapped_frame);
            }
        }
    }

    auto result = g_engine->run_inference(
        uuid_str, prompt_str, frame_ptr,
        frame_ptr ? static_cast<std::size_t>(frameBytes) : 0
    );

    if (mapped_frame && mapped_frame != MAP_FAILED) {
        ::munmap(mapped_frame, static_cast<std::size_t>(frameBytes));
    }
    if (dup_fd >= 0) {
        ::close(dup_fd);
    }
    return env->NewStringUTF(result.json_intent.c_str());
}

JNIEXPORT void JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeUnloadAndTrim(
    JNIEnv*, jobject, jboolean urgentTrimLow
) {
    g_engine->unload_and_trim(urgentTrimLow == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeCancelInference(JNIEnv*, jobject) {
    g_engine->cancel_inference();
}

JNIEXPORT void JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeSetThermalLevel(JNIEnv*, jobject, jint level) {
    g_engine->set_thermal_level(static_cast<aegis::ThermalLevel>(level));
}

JNIEXPORT jstring JNICALL
Java_com_aegis_brain_NativeBrainBridge_nativeGetTelemetryJson(JNIEnv* env, jobject) {
    std::string json = g_engine->telemetry_json();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_aegis_core_Guard2MEngine_nativeLoadGuard2M(JNIEnv* env, jobject, jobject assetManager) {
    AAssetManager* mgr = ::AAssetManager_fromJava(env, assetManager);
    return g_guard->load_from_asset(mgr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_aegis_core_Guard2MEngine_nativeEvaluateGuard2M(JNIEnv* env, jobject, jstring input) {
    const char* raw = env->GetStringUTFChars(input, nullptr);
    std::string text = raw ? raw : "";
    if (raw) env->ReleaseStringUTFChars(input, raw);
    auto verdict = g_guard->evaluate(text);
    return env->NewStringUTF(verdict.json.c_str());
}

} // extern "C"
