#pragma once

#include <android/asset_manager.h>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace aegis {

struct GuardVerdict {
    bool loaded = false;
    int predicted_class = 1; // 0=SAFE_AUTO, 1=HUMAN_CONFIRM, 2=BLOCK_THREAT
    float prob_safe = 0.0f;
    float prob_confirm = 1.0f;
    float prob_block = 0.0f;
    std::uint64_t latency_us = 0;
    std::string json;
};

/// 2.02M-parameter int8 neural Intent & Safety Guard for <2 ms on-device auto-confirmation.
class Guard2MEngine {
public:
    bool load_from_asset(AAssetManager* mgr);
    bool is_loaded() const noexcept;
    GuardVerdict evaluate(const std::string& text) const;

private:
    mutable std::mutex mu_;
    bool loaded_ = false;
    std::uint32_t vocab_ = 0;
    std::uint32_t d_emb_ = 0;
    std::uint32_t d_h1_ = 0;
    std::uint32_t d_h2_ = 0;
    std::uint32_t n_cls_ = 0;

    std::vector<float> emb_scales_, w1_scales_, w2_scales_, w3_scales_;
    std::vector<std::int8_t> emb_q_, w1_q_, w2_q_, w3_q_;
    std::vector<float> b1_, b2_, b3_;
};

} // namespace aegis
