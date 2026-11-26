#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct mtmd_context;

namespace aegis {

enum class ModelSlot : std::uint8_t {
    None = 0,
    Tier1MiniCpmV = 1,
    Tier2Qwen35_2B = 2,
    Tier2Gemma4E2B = 3,
    VoiceAsrQwen3 = 4,
    EmbeddingGemma2 = 5,
};

enum class ThermalLevel : std::uint8_t {
    Normal = 0,
    Light = 1,
    Moderate = 2,
    Severe = 3,
};

enum class WeightFormat : std::uint8_t {
    Unknown = 0,
    Gguf = 1,
    LiteRtTflite = 2,
    MockTest = 3,
};

struct InferenceResult {
    bool ok = false;
    bool aborted = false;
    std::string json_intent;
    std::uint64_t latency_ms = 0;
    std::uint64_t ttft_ms = 0;
    std::uint32_t tokens_generated = 0;
    std::uint32_t sessions_since_recycle = 0;
};

/// Process 2 (android:isolatedProcess="true") C++20 Inference Host backed by llama.cpp.
/// - Zero filesystem path opens: weights arrive strictly via read-only ParcelFileDescriptor.
/// - Real GGUFs (>= 1 MiB) load with llama_model_load_from_file_ptr(fdopen(dup(fd), "rb")).
/// - Small test buffers (< 1 MiB) use fast mmap mock verification for self-tests.
/// - Atomic cancelInference() aborts active llama_decode via ggml_abort_callback.
class BrainEngine {
public:
    static constexpr std::uint32_t kMaxSessionsBeforeRecycle = 25;
    static constexpr std::size_t kMinRealGgufBytes = 1024 * 1024;

    BrainEngine() = default;
    ~BrainEngine();

    bool load_model_from_fd(int fd, std::size_t byte_len, ModelSlot slot);
    bool load_mmproj_from_fd(int fd, std::size_t byte_len);
    void unload_and_trim(bool urgent_trim_low);
    void cancel_inference() noexcept;
    void set_thermal_level(ThermalLevel level) noexcept;

    InferenceResult run_inference(
        const std::string& request_uuid,
        const std::string& sanitized_prompt,
        const uint8_t* optional_rgb_frame,
        std::size_t frame_bytes,
        std::uint32_t max_tokens = 128
    );

    ModelSlot active_slot() const noexcept;
    WeightFormat weight_format() const noexcept;
    std::size_t mapped_bytes() const noexcept;
    std::uint32_t active_threads() const noexcept;
    std::uint32_t recycle_count() const noexcept;
    bool is_real_llama_loaded() const noexcept;
    bool is_vision_loaded() const noexcept;
    std::string telemetry_json() const;

private:
    void unmap_locked() noexcept;
    void warm_system_prefix_locked() noexcept;

    mutable std::mutex mu_;
    void* mapped_weights_ = nullptr;
    std::atomic<std::size_t> mapped_len_{0};
    std::FILE* model_fp_ = nullptr;
    llama_model* llama_model_ = nullptr;
    llama_context* llama_ctx_ = nullptr;
    int mmproj_dup_fd_ = -1;
    mtmd_context* mtmd_ctx_ = nullptr;
    std::int32_t prefix_tokens_count_ = 0;
    std::vector<std::uint8_t> rgb256_scratch_;
    std::atomic<bool> real_model_loaded_{false};
    std::atomic<bool> vision_loaded_{false};
    std::atomic<ModelSlot> active_slot_{ModelSlot::None};
    std::atomic<WeightFormat> weight_format_{WeightFormat::Unknown};
    std::atomic<bool> abort_flag_{false};
    std::atomic<ThermalLevel> thermal_{ThermalLevel::Normal};
    std::atomic<std::uint32_t> session_counter_{0};
    std::atomic<std::uint32_t> recycle_counter_{0};
    std::atomic<std::uint64_t> last_ttft_ms_{0};
    bool speculative_decoding_enabled_ = false; // Permanently false per Rule 6
};

} // namespace aegis
