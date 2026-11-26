#include "brain_engine.hpp"
#include "ashmem_guard.hpp"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstring>
#include <mutex>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace aegis {

namespace {

std::once_flag g_llama_init_once;

bool aegis_llama_abort_cb(void* data) {
    auto* flag = static_cast<std::atomic<bool>*>(data);
    return flag && flag->load(std::memory_order_acquire);
}

std::string to_lower_ascii(std::string s) {
    for (char& ch : s) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return s;
}

std::string json_escape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) >= 0x20) out += c;
                break;
        }
    }
    return out;
}

/// Extracts ONLY the trusted user goal prior to \nApps:, \nHistory:, or <AEGIS_UNTRUSTED_*>
/// so neither installed-app lists (e.g. Settings=..., Gmail=...) nor untrusted DOM blocks hijack fallback inference.
std::string extract_trusted_command(const std::string& prompt) {
    std::size_t cut = prompt.find("<AEGIS_UNTRUSTED_");
    for (const char* marker : { "\nStep:", "\nApps:", "\nHistory:" }) {
        auto pos = prompt.find(marker);
        if (pos != std::string::npos && (cut == std::string::npos || pos < cut)) {
            cut = pos;
        }
    }
    return (cut == std::string::npos) ? prompt : prompt.substr(0, cut);
}

std::string infer_action_from_prompt(const std::string& trusted_cmd) {
    std::string low = to_lower_ascii(trusted_cmd);
    if (low.find("uninstall") != std::string::npos) return "UNINSTALL_APP";
    if (low.find("install apk") != std::string::npos || low.find(".apk") != std::string::npos || low.find("sideload") != std::string::npos) return "INSTALL_APK";
    if (low.find("delete") != std::string::npos || low.find("wipe") != std::string::npos || low.find("erase") != std::string::npos) return "DELETE_FILE";
    if (low.find("grant permission") != std::string::npos) return "GRANT_PERMISSION";
    if (low.find("change setting") != std::string::npos) return "CHANGE_SETTING";
    if (low.find("export credentials") != std::string::npos || low.find("export password") != std::string::npos) return "EXPORT_CREDENTIALS";
    if (low.find("upi://") != std::string::npos || low.find("transfer money") != std::string::npos || low.find("pay ") != std::string::npos) return "INITIATE_PAYMENT";
    if (low.find("send") != std::string::npos || low.find("ask") != std::string::npos || low.find("reply") != std::string::npos) {
        return "SEND_MESSAGE";
    }
    if (low.find("email") != std::string::npos || low.find("mail") != std::string::npos) {
        return "SEND_EMAIL";
    }
    if (low.find("draft") != std::string::npos || low.find("write") != std::string::npos || low.find("note") != std::string::npos) {
        return "TYPE_DRAFT";
    }
    if (low.find("scroll") != std::string::npos) {
        return "SCROLL_DOWN";
    }
    if (low.find("tap") != std::string::npos || low.find("click") != std::string::npos || low.find("open") != std::string::npos) {
        return "CLICK_NODE";
    }
    return "READ_DOM";
}

std::string infer_package_from_prompt(const std::string& trusted_cmd) {
    std::string low = to_lower_ascii(trusted_cmd);
    if (low.find("phonepe") != std::string::npos) return "com.phonepe.app";
    if (low.find("google pay") != std::string::npos || low.find("gpay") != std::string::npos) return "com.google.android.apps.nbu.paisa.user";
    if (low.find("paytm") != std::string::npos) return "net.one97.paytm";
    if (low.find("bhim") != std::string::npos) return "in.org.npci.upiapp";
    if (low.find("settings") != std::string::npos) return "com.android.settings";
    if (low.find("uninstall") != std::string::npos || low.find(".apk") != std::string::npos) return "com.android.packageinstaller";
    if (low.find("play store") != std::string::npos) return "com.android.vending";
    if (low.find("youtube") != std::string::npos) return "com.google.android.youtube";
    if (low.find("calendar") != std::string::npos) return "com.google.android.calendar";
    if (low.find("clock") != std::string::npos || low.find("stopwatch") != std::string::npos || low.find("timer") != std::string::npos || low.find("alarm") != std::string::npos) return "com.google.android.deskclock";
    if (low.find("contact") != std::string::npos) return "com.google.android.contacts";
    if (low.find("whatsapp") != std::string::npos) return "com.whatsapp";
    if (low.find("gmail") != std::string::npos || low.find("email") != std::string::npos) return "com.google.android.gm";
    if (low.find("keep") != std::string::npos || low.find("note") != std::string::npos) return "com.google.android.keep";
    if (low.find("photos") != std::string::npos) return "com.google.android.apps.photos";
    if (low.find("chrome") != std::string::npos || low.find("browser") != std::string::npos) return "com.android.chrome";
    return "com.aegis.core";
}

WeightFormat detect_weight_format_header(const std::uint8_t* b, std::size_t len) noexcept {
    if (!b || len < 8) return WeightFormat::Unknown;
    if (b[0] == 'G' && b[1] == 'G' && b[2] == 'U' && b[3] == 'F') {
        return WeightFormat::Gguf;
    }
    if (b[4] == 'T' && b[5] == 'F' && b[6] == 'L' && b[7] == '3') {
        return WeightFormat::LiteRtTflite;
    }
    return WeightFormat::MockTest;
}

const char* format_name(WeightFormat fmt) noexcept {
    switch (fmt) {
        case WeightFormat::Gguf: return "GGUF";
        case WeightFormat::LiteRtTflite: return "LiteRT_TFL3";
        case WeightFormat::MockTest: return "MOCK_WEIGHTS";
        default: return "NONE";
    }
}

std::string extract_first_json_object(const std::string& text) {
    auto start = text.find('{');
    if (start == std::string::npos) return "";
    int depth = 0;
    bool in_str = false;
    bool esc = false;
    for (std::size_t i = start; i < text.size(); ++i) {
        char c = text[i];
        if (esc) { esc = false; continue; }
        if (c == '\\' && in_str) { esc = true; continue; }
        if (c == '"') { in_str = !in_str; continue; }
        if (!in_str) {
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substr(start, i - start + 1);
                }
            }
        }
    }
    return "";
}

std::string extract_json_string_field(const std::string& json_obj, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    auto kpos = json_obj.find(needle);
    if (kpos == std::string::npos) return "";
    auto colon = json_obj.find(':', kpos + needle.size());
    if (colon == std::string::npos) return "";
    auto q1 = json_obj.find('"', colon + 1);
    if (q1 == std::string::npos) return "";
    std::string val;
    bool esc = false;
    for (std::size_t i = q1 + 1; i < json_obj.size(); ++i) {
        char c = json_obj[i];
        if (esc) {
            val.push_back(c);
            esc = false;
        } else if (c == '\\') {
            esc = true;
        } else if (c == '"') {
            return val;
        } else {
            val.push_back(c);
        }
    }
    return "";
}

int extract_json_int_field(const std::string& json_obj, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    auto kpos = json_obj.find(needle);
    if (kpos == std::string::npos) return -1;
    auto colon = json_obj.find(':', kpos + needle.size());
    if (colon == std::string::npos) return -1;
    std::size_t i = colon + 1;
    while (i < json_obj.size() && (json_obj[i] == ' ' || json_obj[i] == '\t' || json_obj[i] == '"')) {
        ++i;
    }
    if (i >= json_obj.size() || !std::isdigit(static_cast<unsigned char>(json_obj[i]))) return -1;
    int val = 0;
    while (i < json_obj.size() && std::isdigit(static_cast<unsigned char>(json_obj[i]))) {
        val = val * 10 + (json_obj[i] - '0');
        ++i;
    }
    return val;
}

std::string static_system_prefix() {
    return "<|im_start|>system\n"
        "Aegis visual Android agent. Output compact JSON: action, package, target, x, y (0-999 coords), text."
        "<|im_end|>\n<|im_start|>user\n";
}

std::string format_user_suffix(const std::string& sanitized_prompt) {
    return sanitized_prompt + "<|im_end|>\n<|im_start|>assistant\n{\"action\":\"";
}

std::string format_agent_chat_prompt(llama_model* model, const std::string& sanitized_prompt) {
    static constexpr const char* kSystemPrompt =
        "Aegis visual Android agent. Output compact JSON: action, package, target, x, y (0-999 coords), text.";

    llama_chat_message msgs[2] = {
        { "system", kSystemPrompt },
        { "user", sanitized_prompt.c_str() },
    };

    const char* tmpl = ::llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) {
        int needed = ::llama_chat_apply_template(tmpl, msgs, 2, true, nullptr, 0);
        if (needed > 0) {
            std::string buf(static_cast<std::size_t>(needed) + 1, '\0');
            int written = ::llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), static_cast<int>(buf.size()));
            if (written > 0) {
                buf.resize(static_cast<std::size_t>(written));
                return buf;
            }
        }
    }

    return static_system_prefix() + format_user_suffix(sanitized_prompt);
}

llama_token fast_argmax_token(llama_context* ctx, const llama_vocab* vocab) noexcept {
    const float* logits = ::llama_get_logits_ith(ctx, -1);
    const int32_t n_vocab = ::llama_vocab_n_tokens(vocab);
    if (!logits || n_vocab <= 0) return LLAMA_TOKEN_NULL;
    llama_token best = 0;
    float best_val = logits[0];
    for (int32_t i = 1; i < n_vocab; ++i) {
        if (logits[i] > best_val) {
            best_val = logits[i];
            best = i;
        }
    }
    return best;
}

bool decode_batch_chunked(
    llama_context* ctx,
    const std::vector<llama_token>& tokens,
    llama_pos start_pos,
    int32_t batch_cap
) {
    llama_batch_ext* batch = ::llama_batch_ext_init(ctx);
    if (!batch) return false;
    bool ok = true;
    const int32_t total = static_cast<int32_t>(tokens.size());
    for (int32_t i = 0; i < total; i += batch_cap) {
        const int32_t chunk = std::min(total - i, batch_cap);
        ::llama_batch_ext_clear(batch);
        for (int32_t j = 0; j < chunk; ++j) {
            const int32_t idx = ::llama_batch_ext_add_token(batch, 0, tokens[static_cast<std::size_t>(i + j)]);
            if (idx < 0) { ok = false; break; }
            const llama_pos pos = start_pos + i + j;
            ::llama_batch_ext_set_pos(batch, idx, &pos);
            if (i + j == total - 1) {
                ::llama_batch_ext_set_output_logits(batch, idx, true);
            }
        }
        if (!ok || ::llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            ok = false;
            break;
        }
    }
    ::llama_batch_ext_free(batch);
    return ok;
}

void downsample_rgb768_to_256(const std::uint8_t* src, std::vector<std::uint8_t>& out) {
    constexpr std::size_t src_w = 768;
    constexpr std::size_t dst_w = 256;
    constexpr std::size_t dst_h = 256;
    out.resize(dst_w * dst_h * 3);
    for (std::size_t y = 0; y < dst_h; ++y) {
        const std::size_t sy = y * 3;
        std::uint8_t* dst = out.data() + y * dst_w * 3;
        for (std::size_t x = 0; x < dst_w; ++x) {
            const std::size_t sx = x * 3;
            for (std::size_t c = 0; c < 3; ++c) {
                std::uint32_t sum = 0;
                for (std::size_t dy = 0; dy < 3; ++dy) {
                    const std::uint8_t* row = src + (sy + dy) * src_w * 3;
                    for (std::size_t dx = 0; dx < 3; ++dx) {
                        sum += row[(sx + dx) * 3 + c];
                    }
                }
                dst[x * 3 + c] = static_cast<std::uint8_t>(sum / 9u);
            }
        }
    }
}

} // namespace

BrainEngine::~BrainEngine() {
    std::lock_guard<std::mutex> lock(mu_);
    unmap_locked();
}

void BrainEngine::unmap_locked() noexcept {
    if (mtmd_ctx_) {
        ::mtmd_free(mtmd_ctx_);
        mtmd_ctx_ = nullptr;
    }
    if (mmproj_dup_fd_ >= 0) {
        ::close(mmproj_dup_fd_);
        mmproj_dup_fd_ = -1;
    }
    vision_loaded_ = false;
    if (llama_ctx_) {
        ::llama_free(llama_ctx_);
        llama_ctx_ = nullptr;
    }
    if (llama_model_) {
        ::llama_model_free(llama_model_);
        llama_model_ = nullptr;
    }
    if (model_fp_) {
        std::fclose(model_fp_);
        model_fp_ = nullptr;
    }
    if (mapped_weights_ && mapped_weights_ != MAP_FAILED) {
        ::madvise(mapped_weights_, mapped_len_, MADV_DONTNEED);
        ::munmap(mapped_weights_, mapped_len_);
    }
    mapped_weights_ = nullptr;
    mapped_len_ = 0;
    prefix_tokens_count_ = 0;
    real_model_loaded_ = false;
    active_slot_ = ModelSlot::None;
    weight_format_ = WeightFormat::Unknown;
}

bool BrainEngine::load_model_from_fd(int fd, std::size_t byte_len, ModelSlot slot) {
    if (fd < 0 || byte_len < 8 || slot == ModelSlot::None) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mu_);
    // Strict mutual exclusion: free/unmap previous model slot before loading next.
    unmap_locked();

    std::uint8_t magic[8] = {};
    if (::pread(fd, magic, sizeof(magic), 0) != static_cast<ssize_t>(sizeof(magic))) {
        return false;
    }
    const WeightFormat fmt = detect_weight_format_header(magic, sizeof(magic));

    // Real GGUF (>= 1 MiB) -> load with llama.cpp directly from duplicated FD FILE* (zero path open)
    if (fmt == WeightFormat::Gguf && byte_len >= kMinRealGgufBytes) {
        std::call_once(g_llama_init_once, []() {
            ::llama_backend_init();
        });

        int dup_fd = ::dup(fd);
        if (dup_fd < 0) return false;
        ::lseek(dup_fd, 0, SEEK_SET);
        std::FILE* fp = ::fdopen(dup_fd, "rb");
        if (!fp) {
            ::close(dup_fd);
            return false;
        }

        llama_model_params mparams = ::llama_model_default_params();
        mparams.n_gpu_layers = 0;
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        mparams.check_tensors = false;

        llama_model* model = ::llama_model_load_from_file_ptr(fp, mparams);
        if (!model) {
            std::fclose(fp);
            return false;
        }

        const bool compact_budget = (byte_len >= 700ULL * 1024ULL * 1024ULL);
        llama_context_params cparams = ::llama_context_default_params();
        cparams.n_ctx = (slot == ModelSlot::Tier1MiniCpmV) ? 1024 : (compact_budget ? 768 : 1024);
        cparams.n_batch = compact_budget ? 128 : 256;
        cparams.n_ubatch = compact_budget ? 128 : 256;
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
        const int32_t threads = static_cast<int32_t>(active_threads());
        cparams.n_threads = threads;
        cparams.n_threads_batch = threads;

        llama_context* ctx = ::llama_init_from_model(model, cparams);
        if (!ctx) {
            ::llama_model_free(model);
            std::fclose(fp);
            return false;
        }
        ::llama_set_abort_callback(ctx, aegis_llama_abort_cb, &abort_flag_);

        model_fp_ = fp;
        llama_model_ = model;
        llama_ctx_ = ctx;
        real_model_loaded_ = true;
        mapped_len_ = byte_len;
        active_slot_ = slot;
        weight_format_ = fmt;
        warm_system_prefix_locked();
        return true;
    }

    // Small mock test buffer (< 1 MiB) -> fast read-only mmap for self-test
    void* addr = ::mmap(nullptr, byte_len, PROT_READ, MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) {
        return false;
    }
    ::madvise(addr, byte_len, MADV_SEQUENTIAL);
    mapped_weights_ = addr;
    mapped_len_ = byte_len;
    real_model_loaded_ = false;
    active_slot_ = slot;
    weight_format_ = fmt;
    return true;
}

bool BrainEngine::load_mmproj_from_fd(int fd, std::size_t byte_len) {
    if (fd < 0 || byte_len < 1024) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mu_);
    if (!real_model_loaded_ || !llama_model_) {
        return false;
    }
    if (mtmd_ctx_) {
        ::mtmd_free(mtmd_ctx_);
        mtmd_ctx_ = nullptr;
    }
    if (mmproj_dup_fd_ >= 0) {
        ::close(mmproj_dup_fd_);
        mmproj_dup_fd_ = -1;
    }
    vision_loaded_ = false;

    int dup_fd = ::dup(fd);
    if (dup_fd < 0) {
        return false;
    }
    ::lseek(dup_fd, 0, SEEK_SET);
    std::string fd_path = "fd://" + std::to_string(dup_fd);

    mtmd_context_params mparams = ::mtmd_context_params_default();
    mparams.use_gpu = false;
    mparams.n_threads = static_cast<int>(active_threads());
    mparams.warmup = false;
    // Pin SmolVLM/Idefics3 to <=64 tokens/tile (512x512 = 64 patches) to avoid 2x2 upscaling tiles
    mparams.image_min_tokens = 64;
    mparams.image_max_tokens = 64;

    mtmd_context* mctx = ::mtmd_init_from_file(fd_path.c_str(), llama_model_, mparams);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "AegisVLM", "mtmd_init_from_file(%s, bytes=%zu) -> %s", fd_path.c_str(), byte_len, mctx ? "OK" : "FAILED");
#endif
    if (!mctx) {
        ::close(dup_fd);
        return false;
    }
    mtmd_ctx_ = mctx;
    mmproj_dup_fd_ = dup_fd;
    vision_loaded_ = true;
    return true;
}

void BrainEngine::warm_system_prefix_locked() noexcept {
    prefix_tokens_count_ = 0;
    if (!llama_model_ || !llama_ctx_) return;
    // SmolVLM-500M (slot 1) uses its native Idefics3/User: chat template + multimodal chunks
    // rather than ChatML <|im_start|>system tokens.
    if (active_slot_ == ModelSlot::Tier1MiniCpmV) {
        return;
    }
    const std::string prefix = static_system_prefix();
    const llama_vocab* vocab = ::llama_model_get_vocab(llama_model_);
    const int32_t n_needed = -::llama_tokenize(
        vocab, prefix.c_str(), static_cast<int32_t>(prefix.size()), nullptr, 0, true, true
    );
    if (n_needed <= 0 || n_needed >= 256) return;
    std::vector<llama_token> tokens(static_cast<std::size_t>(n_needed));
    if (::llama_tokenize(
            vocab, prefix.c_str(), static_cast<int32_t>(prefix.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true
        ) < 0) {
        return;
    }
    ::llama_memory_clear(::llama_get_memory(llama_ctx_), true);
    const int32_t threads = static_cast<int32_t>(active_threads());
    ::llama_set_n_threads(llama_ctx_, threads, threads);
    if (decode_batch_chunked(llama_ctx_, tokens, 0, 256)) {
        prefix_tokens_count_ = static_cast<std::int32_t>(tokens.size());
    } else {
        ::llama_memory_clear(::llama_get_memory(llama_ctx_), true);
    }
}

void BrainEngine::unload_and_trim(bool urgent_trim_low) {
    std::lock_guard<std::mutex> lock(mu_);
    if (urgent_trim_low) {
        abort_flag_.store(true, std::memory_order_release);
    }
    unmap_locked();
}

void BrainEngine::cancel_inference() noexcept {
    abort_flag_.store(true, std::memory_order_release);
}

void BrainEngine::set_thermal_level(ThermalLevel level) noexcept {
    thermal_.store(level, std::memory_order_release);
    if (level == ThermalLevel::Severe) {
        abort_flag_.store(true, std::memory_order_release);
    }
}

std::uint32_t BrainEngine::active_threads() const noexcept {
    const unsigned hw = std::thread::hardware_concurrency();
    const std::uint32_t max_normal = (hw >= 6) ? 6u : ((hw >= 4) ? 4u : 2u);
    switch (thermal_.load(std::memory_order_acquire)) {
        case ThermalLevel::Normal: return max_normal;
        case ThermalLevel::Light: return std::min<std::uint32_t>(3u, max_normal);
        case ThermalLevel::Moderate: return 2;
        case ThermalLevel::Severe: return 1;
    }
    return 2;
}

InferenceResult BrainEngine::run_inference(
    const std::string& request_uuid,
    const std::string& sanitized_prompt,
    const uint8_t* optional_rgb_frame,
    std::size_t frame_bytes,
    std::uint32_t max_tokens
) {
    InferenceResult res;
    auto start = std::chrono::steady_clock::now();

    std::lock_guard<std::mutex> lock(mu_);
    if (active_slot_ == ModelSlot::None || (!real_model_loaded_ && !mapped_weights_)) {
        res.ok = false;
        res.json_intent = R"({"error":"NO_MODEL_LOADED"})";
        return res;
    }
    if (thermal_.load(std::memory_order_acquire) == ThermalLevel::Severe) {
        res.ok = false;
        res.aborted = true;
        res.json_intent = R"({"error":"THERMAL_THROTTLE_PAUSE"})";
        return res;
    }

    if (session_counter_ >= kMaxSessionsBeforeRecycle) {
        if (mapped_weights_) {
            ::madvise(mapped_weights_, mapped_len_, MADV_DONTNEED);
        }
        if (llama_ctx_) {
            ::llama_memory_clear(::llama_get_memory(llama_ctx_), true);
            if (real_model_loaded_) {
                warm_system_prefix_locked();
            }
        }
        session_counter_ = 0;
        recycle_counter_++;
    }
    session_counter_++;
    res.sessions_since_recycle = session_counter_;
    abort_flag_.store(false, std::memory_order_release);

    std::uint64_t visual_hash = 0;
    if (optional_rgb_frame && frame_bytes == kAshmemFrameBytes) {
        std::uint64_t fnv = 14695981039346656037ULL;
        bool has_nonzero = false;
        for (std::size_t i = 0; i < frame_bytes; i += 997) {
            const std::uint8_t b = optional_rgb_frame[i];
            if (b != 0) has_nonzero = true;
            fnv = (fnv ^ b) * 1099511628211ULL;
        }
        visual_hash = has_nonzero ? (fnv == 0 ? 1ULL : fnv) : 0ULL;
    }

    const std::string trusted_cmd = extract_trusted_command(sanitized_prompt);
    std::string proposed_action = infer_action_from_prompt(trusted_cmd);
    std::string suggested_pkg = infer_package_from_prompt(trusted_cmd);
    std::string target_label;
    std::string payload_text;
    std::string raw_llm_output;
    std::string backend_name = "mock";
    int tap_x = -1;
    int tap_y = -1;
    std::uint64_t prefill_ms = 0;
    std::uint64_t decode_ms = 0;
    bool used_prefix_cache = false;
    bool used_vlm_vision = false;

    if (real_model_loaded_ && llama_model_ && llama_ctx_) {
        backend_name = "llama.cpp";
        const int32_t threads = static_cast<int32_t>(active_threads());
        ::llama_set_n_threads(llama_ctx_, threads, threads);

        const llama_vocab* vocab = ::llama_model_get_vocab(llama_model_);
        llama_pos n_pos = 0;
        bool prefill_ok = false;

        if (mtmd_ctx_ != nullptr && optional_rgb_frame != nullptr && frame_bytes == kAshmemFrameBytes) {
            downsample_rgb768_to_256(optional_rgb_frame, rgb256_scratch_);
            mtmd_bitmap* bmp = ::mtmd_bitmap_init(256, 256, rgb256_scratch_.data());
            if (bmp != nullptr) {
                const std::string marker(::mtmd_default_marker());
                std::string vlm_user = marker + "\n" + sanitized_prompt;
                std::string vlm_prompt = format_agent_chat_prompt(llama_model_, vlm_user);
                if (vlm_prompt.find(marker) == std::string::npos) {
                    vlm_prompt = "<|im_start|>User:" + vlm_user + "<end_of_utterance>\nAssistant:";
                }
                vlm_prompt += "{";
                mtmd_input_chunks* chunks = ::mtmd_input_chunks_init();
                mtmd_input_text txt{ vlm_prompt.c_str(), vlm_prompt.size(), true, true };
                const mtmd_bitmap* bmp_arr[1] = { bmp };
                int32_t tok_rc = ::mtmd_tokenize(mtmd_ctx_, chunks, &txt, bmp_arr, 1);
                if (tok_rc != 0) {
                    // Fallback to direct SmolVLM / Idefics3 marker prompt if model template stripped marker
                    ::mtmd_input_chunks_free(chunks);
                    chunks = ::mtmd_input_chunks_init();
                    vlm_prompt = "<|im_start|>User:" + vlm_user + "<end_of_utterance>\nAssistant:{";
                    txt = { vlm_prompt.c_str(), vlm_prompt.size(), true, true };
                    tok_rc = ::mtmd_tokenize(mtmd_ctx_, chunks, &txt, bmp_arr, 1);
                }
                int32_t eval_rc = -1;
                if (tok_rc == 0) {
                    ::llama_memory_clear(::llama_get_memory(llama_ctx_), true);
                    eval_rc = ::mtmd_helper_eval_chunks(
                        mtmd_ctx_, llama_ctx_, chunks, 0, 0, 256, true, &n_pos
                    );
                    if (eval_rc == 0) {
                        prefill_ok = true;
                        used_vlm_vision = true;
                        backend_name = "llama.cpp+mtmd-vlm";
                    }
                }
#ifdef __ANDROID__
                __android_log_print(ANDROID_LOG_INFO, "AegisVLM", "VLM inference tok_rc=%d eval_rc=%d n_pos=%d", tok_rc, eval_rc, static_cast<int>(n_pos));
#endif
                ::mtmd_input_chunks_free(chunks);
                ::mtmd_bitmap_free(bmp);
            }
        }

        raw_llm_output = used_vlm_vision ? "{" : "{\"action\":\"";
        if (!prefill_ok) {
            used_prefix_cache = (prefix_tokens_count_ > 0);
            llama_pos start_pos = 0;
            std::string prompt_text;
            bool add_bos = true;
            if (used_prefix_cache) {
                ::llama_memory_seq_rm(::llama_get_memory(llama_ctx_), 0, prefix_tokens_count_, -1);
                start_pos = prefix_tokens_count_;
                prompt_text = format_user_suffix(sanitized_prompt);
                add_bos = false;
            } else {
                ::llama_memory_clear(::llama_get_memory(llama_ctx_), true);
                prompt_text = format_agent_chat_prompt(llama_model_, sanitized_prompt) + "{\"action\":\"";
                add_bos = true;
            }

            int32_t n_needed = -::llama_tokenize(
                vocab, prompt_text.c_str(), static_cast<int32_t>(prompt_text.size()), nullptr, 0, add_bos, true
            );
            if (n_needed > 0 && n_needed < 900) {
                std::vector<llama_token> prompt_tokens(static_cast<std::size_t>(n_needed));
                if (::llama_tokenize(
                        vocab,
                        prompt_text.c_str(),
                        static_cast<int32_t>(prompt_text.size()),
                        prompt_tokens.data(),
                        static_cast<int32_t>(prompt_tokens.size()),
                        add_bos,
                        true
                    ) >= 0) {
                    if (decode_batch_chunked(llama_ctx_, prompt_tokens, start_pos, 256)) {
                        prefill_ok = true;
                        n_pos = start_pos + static_cast<llama_pos>(prompt_tokens.size());
                    } else if (abort_flag_.load(std::memory_order_acquire)) {
                        res.aborted = true;
                    }
                }
            }
        }

        if (prefill_ok) {
            auto t_prefill_end = std::chrono::steady_clock::now();
            prefill_ms = static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::milliseconds>(t_prefill_end - start).count()
            );

            llama_batch_ext* token_batch = ::llama_batch_ext_init(llama_ctx_);
            const std::uint32_t limit = std::min<std::uint32_t>(max_tokens, used_vlm_vision ? 32u : 36u);
            const auto decode_budget = std::chrono::milliseconds(used_vlm_vision ? 4500 : 4000);
            for (std::uint32_t step = 0; step < limit; ++step) {
                if (abort_flag_.load(std::memory_order_acquire)) {
                    res.aborted = true;
                    break;
                }
                if (std::chrono::steady_clock::now() - t_prefill_end > decode_budget) {
                    break;
                }
                const llama_token tok = fast_argmax_token(llama_ctx_, vocab);
                if (step == 0) {
                    res.ttft_ms = static_cast<std::uint64_t>(
                        std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start
                        ).count()
                    );
                    last_ttft_ms_.store(res.ttft_ms, std::memory_order_release);
                }
                if (tok == LLAMA_TOKEN_NULL || ::llama_vocab_is_eog(vocab, tok)) {
                    break;
                }
                char piece[256];
                int n = ::llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
                if (n > 0) {
                    raw_llm_output.append(piece, static_cast<std::size_t>(n));
                }
                res.tokens_generated++;
                if (!extract_first_json_object(raw_llm_output).empty()) {
                    break;
                }
                if (used_vlm_vision && step >= 8 && raw_llm_output.find(':') == std::string::npos) {
                    break;
                }
                if (!token_batch) {
                    break;
                }
                ::llama_batch_ext_clear(token_batch);
                const int32_t idx = ::llama_batch_ext_add_token(token_batch, 0, tok);
                if (idx < 0) {
                    break;
                }
                ::llama_batch_ext_set_pos(token_batch, idx, &n_pos);
                ::llama_batch_ext_set_output_logits(token_batch, idx, true);
                if (::llama_process(llama_ctx_, LLAMA_PROCESS_TYPE_DECODE, token_batch) != 0) {
                    if (abort_flag_.load(std::memory_order_acquire)) {
                        res.aborted = true;
                    }
                    break;
                }
                n_pos++;
            }
            if (token_batch) {
                ::llama_batch_ext_free(token_batch);
            }
            decode_ms = static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - t_prefill_end
                ).count()
            );
        }
    } else {
        for (std::uint32_t tok = 0; tok < std::min<std::uint32_t>(max_tokens, 64); ++tok) {
            if (abort_flag_.load(std::memory_order_acquire)) {
                res.aborted = true;
                res.tokens_generated = tok;
                break;
            }
            res.tokens_generated = tok + 1;
        }
    }

    auto end = std::chrono::steady_clock::now();
    res.latency_ms = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count()
    );

    if (res.aborted) {
        res.ok = false;
        res.json_intent = R"({"status":"USER_ABORTED"})";
        return res;
    }

    const std::string parsed_json = extract_first_json_object(raw_llm_output);
    if (!parsed_json.empty()) {
        const std::string a = extract_json_string_field(parsed_json, "action");
        const std::string p = extract_json_string_field(parsed_json, "package");
        const std::string t = extract_json_string_field(parsed_json, "target");
        const std::string txt = extract_json_string_field(parsed_json, "text");
        if (!a.empty()) proposed_action = a;
        if (!p.empty()) suggested_pkg = p;
        if (!t.empty()) target_label = t;
        if (!txt.empty()) payload_text = txt;
        const int gx = extract_json_int_field(parsed_json, "x");
        const int gy = extract_json_int_field(parsed_json, "y");
        if (gx > 0 && gx < 1000) tap_x = gx;
        if (gy > 0 && gy < 1000) tap_y = gy;
    }

    res.ok = true;
    res.json_intent = std::string(R"({"request_id":")") + json_escape(request_uuid) +
        R"(","engine":")" + backend_name +
        R"(","proposed_action":")" + json_escape(proposed_action) +
        R"(","suggested_package":")" + json_escape(suggested_pkg) +
        R"(","target":")" + json_escape(target_label) +
        R"(","payload_text":")" + json_escape(payload_text) +
        R"(","tap_x":)" + std::to_string(tap_x) +
        R"(,"tap_y":)" + std::to_string(tap_y) +
        R"(,"raw_output":")" + json_escape(raw_llm_output) +
        R"(","format":")" + format_name(weight_format_.load(std::memory_order_acquire)) +
        R"(","slot":)" + std::to_string(static_cast<int>(active_slot_.load(std::memory_order_acquire))) +
        R"(,"threads":)" + std::to_string(active_threads()) +
        R"(,"speculative_mtp":)" + (speculative_decoding_enabled_ ? "true" : "false") +
        R"(,"prompt_len":)" + std::to_string(sanitized_prompt.size()) +
        R"(,"tokens":)" + std::to_string(res.tokens_generated) +
        R"(,"ttft_ms":)" + std::to_string(res.ttft_ms) +
        R"(,"prefill_ms":)" + std::to_string(prefill_ms) +
        R"(,"decode_ms":)" + std::to_string(decode_ms) +
        R"(,"prefix_cached":)" + (used_prefix_cache ? "true" : "false") +
        R"(,"vlm_vision":)" + (used_vlm_vision ? "true" : "false") +
        R"(,"prefix_tokens":)" + std::to_string(prefix_tokens_count_) +
        R"(,"latency_ms":)" + std::to_string(res.latency_ms) +
        R"(,"visual_sig":)" + std::to_string(visual_hash) + "}";
    return res;
}

ModelSlot BrainEngine::active_slot() const noexcept {
    return active_slot_.load(std::memory_order_acquire);
}

WeightFormat BrainEngine::weight_format() const noexcept {
    return weight_format_.load(std::memory_order_acquire);
}

std::size_t BrainEngine::mapped_bytes() const noexcept {
    return mapped_len_.load(std::memory_order_acquire);
}

std::uint32_t BrainEngine::recycle_count() const noexcept {
    return recycle_counter_.load(std::memory_order_acquire);
}

bool BrainEngine::is_real_llama_loaded() const noexcept {
    return real_model_loaded_.load(std::memory_order_acquire);
}

bool BrainEngine::is_vision_loaded() const noexcept {
    return vision_loaded_.load(std::memory_order_acquire);
}

std::string BrainEngine::telemetry_json() const {
    const bool is_real = real_model_loaded_.load(std::memory_order_acquire);
    const bool is_vlm = vision_loaded_.load(std::memory_order_acquire);
    const std::size_t bytes = mapped_len_.load(std::memory_order_acquire);
    return std::string(R"({"slot":)") + std::to_string(static_cast<int>(active_slot_.load(std::memory_order_acquire))) +
        R"(,"engine":")" + (is_real ? (is_vlm ? "llama.cpp+mtmd-vlm" : "llama.cpp") : (bytes > 0 ? "mock" : "unloaded")) +
        R"(","vlm_vision":)" + (is_vlm ? "true" : "false") +
        R"(,"mapped_bytes":)" + std::to_string(bytes) +
        R"(,"format":")" + format_name(weight_format_.load(std::memory_order_acquire)) +
        R"(","threads":)" + std::to_string(active_threads()) +
        R"(,"thermal":)" + std::to_string(static_cast<int>(thermal_.load(std::memory_order_acquire))) +
        R"(,"sessions":)" + std::to_string(session_counter_.load(std::memory_order_acquire)) +
        R"(,"recycles":)" + std::to_string(recycle_counter_.load(std::memory_order_acquire)) +
        R"(,"last_ttft_ms":)" + std::to_string(last_ttft_ms_.load(std::memory_order_acquire)) +
        R"(,"speculative_mtp":)" + (speculative_decoding_enabled_ ? "true" : "false") + "}";
}

} // namespace aegis
