#include "guard_2m.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstring>

namespace aegis {

namespace {

std::uint32_t fnv1a_32(const std::string& s) noexcept {
    std::uint32_t h = 2166136261u;
    for (unsigned char c : s) {
        h ^= c;
        h *= 16777619u;
    }
    return h;
}

std::string normalize_ascii(const std::string& raw) {
    std::string out;
    out.reserve(raw.size() + 2);
    out.push_back(' ');
    bool prev_space = true;
    for (unsigned char ch : raw) {
        if (std::isspace(ch)) {
            if (!prev_space) {
                out.push_back(' ');
                prev_space = true;
            }
        } else {
            out.push_back(static_cast<char>(std::tolower(ch)));
            prev_space = false;
        }
    }
    if (out.back() != ' ') out.push_back(' ');
    return out;
}

std::vector<std::uint32_t> extract_unique_buckets(const std::string& text, std::uint32_t vocab) {
    const std::string s = normalize_ascii(text);
    std::vector<std::uint32_t> buckets;
    buckets.reserve(s.size() + 16);

    // Word unigrams
    std::size_t pos = 0;
    while (pos < s.size()) {
        while (pos < s.size() && s[pos] == ' ') ++pos;
        if (pos >= s.size()) break;
        std::size_t end = pos;
        while (end < s.size() && s[end] != ' ') ++end;
        buckets.push_back(fnv1a_32("w:" + s.substr(pos, end - pos)) % vocab);
        pos = end;
    }

    // Char 3-grams
    if (s.size() >= 3) {
        for (std::size_t i = 0; i + 3 <= s.size(); ++i) {
            buckets.push_back(fnv1a_32("c:" + s.substr(i, 3)) % vocab);
        }
    }
    if (buckets.empty()) {
        buckets.push_back(0);
    }
    std::sort(buckets.begin(), buckets.end());
    buckets.erase(std::unique(buckets.begin(), buckets.end()), buckets.end());
    return buckets;
}

void linear_int8_relu(
    const float* x,
    std::uint32_t in_dim,
    const std::int8_t* w_q,
    const float* w_scales,
    const float* bias,
    std::uint32_t out_dim,
    bool apply_relu,
    float* out
) noexcept {
    for (std::uint32_t r = 0; r < out_dim; ++r) {
        const std::int8_t* row = w_q + static_cast<std::size_t>(r) * in_dim;
        float acc = 0.0f;
        for (std::uint32_t c = 0; c < in_dim; ++c) {
            acc += static_cast<float>(row[c]) * x[c];
        }
        float val = acc * w_scales[r] + bias[r];
        if (apply_relu && val < 0.0f) {
            val = 0.0f;
        }
        out[r] = val;
    }
}

} // namespace

bool Guard2MEngine::load_from_asset(AAssetManager* mgr) {
    if (!mgr) return false;
    std::lock_guard<std::mutex> lock(mu_);
    if (loaded_) return true;

    AAsset* asset = ::AAssetManager_open(mgr, "aegis_guard_2m.bin", AASSET_MODE_BUFFER);
    if (!asset) return false;

    const auto len = static_cast<std::size_t>(::AAsset_getLength(asset));
    const auto* buf = static_cast<const std::uint8_t*>(::AAsset_getBuffer(asset));
    if (!buf || len < 28 || std::memcmp(buf, "AG2M", 4) != 0) {
        ::AAsset_close(asset);
        return false;
    }

    std::size_t off = 4;
    auto read_u32 = [&](std::uint32_t& dst) -> bool {
        if (off + 4 > len) return false;
        std::memcpy(&dst, buf + off, 4);
        off += 4;
        return true;
    };

    std::uint32_t ver = 0;
    if (!read_u32(ver) || ver != 1 ||
        !read_u32(vocab_) || !read_u32(d_emb_) ||
        !read_u32(d_h1_) || !read_u32(d_h2_) || !read_u32(n_cls_)) {
        ::AAsset_close(asset);
        return false;
    }

    auto read_qmat = [&](std::uint32_t rows, std::uint32_t cols, std::vector<float>& scales, std::vector<std::int8_t>& q) -> bool {
        const std::size_t s_bytes = static_cast<std::size_t>(rows) * sizeof(float);
        const std::size_t q_bytes = static_cast<std::size_t>(rows) * cols;
        if (off + s_bytes + q_bytes > len) return false;
        scales.resize(rows);
        std::memcpy(scales.data(), buf + off, s_bytes);
        off += s_bytes;
        q.resize(static_cast<std::size_t>(rows) * cols);
        std::memcpy(q.data(), buf + off, q_bytes);
        off += q_bytes;
        return true;
    };

    auto read_fvec = [&](std::uint32_t n, std::vector<float>& dst) -> bool {
        const std::size_t bytes = static_cast<std::size_t>(n) * sizeof(float);
        if (off + bytes > len) return false;
        dst.resize(n);
        std::memcpy(dst.data(), buf + off, bytes);
        off += bytes;
        return true;
    };

    const bool ok =
        read_qmat(vocab_, d_emb_, emb_scales_, emb_q_) &&
        read_qmat(d_h1_, d_emb_, w1_scales_, w1_q_) &&
        read_qmat(d_h2_, d_h1_, w2_scales_, w2_q_) &&
        read_qmat(n_cls_, d_h2_, w3_scales_, w3_q_) &&
        read_fvec(d_h1_, b1_) &&
        read_fvec(d_h2_, b2_) &&
        read_fvec(n_cls_, b3_);

    ::AAsset_close(asset);
    loaded_ = ok;
    return ok;
}

bool Guard2MEngine::is_loaded() const noexcept {
    std::lock_guard<std::mutex> lock(mu_);
    return loaded_;
}

GuardVerdict Guard2MEngine::evaluate(const std::string& text) const {
    auto t0 = std::chrono::steady_clock::now();
    GuardVerdict v;
    std::lock_guard<std::mutex> lock(mu_);
    if (!loaded_) {
        v.json = R"({"loaded":false,"label":"HUMAN_CONFIRM"})";
        return v;
    }

    const auto buckets = extract_unique_buckets(text, vocab_);
    std::vector<float> x(d_emb_, 0.0f);
    for (std::uint32_t b : buckets) {
        const float scale = emb_scales_[b];
        const std::int8_t* row = emb_q_.data() + static_cast<std::size_t>(b) * d_emb_;
        for (std::uint32_t c = 0; c < d_emb_; ++c) {
            x[c] += static_cast<float>(row[c]) * scale;
        }
    }
    const float inv_n = 1.0f / static_cast<float>(buckets.size());
    for (float& val : x) val *= inv_n;

    std::vector<float> h1(d_h1_);
    std::vector<float> h2(d_h2_);
    std::vector<float> logits(n_cls_);

    linear_int8_relu(x.data(), d_emb_, w1_q_.data(), w1_scales_.data(), b1_.data(), d_h1_, true, h1.data());
    linear_int8_relu(h1.data(), d_h1_, w2_q_.data(), w2_scales_.data(), b2_.data(), d_h2_, true, h2.data());
    linear_int8_relu(h2.data(), d_h2_, w3_q_.data(), w3_scales_.data(), b3_.data(), n_cls_, false, logits.data());

    const float max_l = *std::max_element(logits.begin(), logits.end());
    float sum_exp = 0.0f;
    float probs[3] = {};
    for (std::size_t i = 0; i < 3; ++i) {
        probs[i] = std::exp(logits[i] - max_l);
        sum_exp += probs[i];
    }
    for (std::size_t i = 0; i < 3; ++i) probs[i] /= sum_exp;

    int best = 0;
    if (probs[1] > probs[best]) best = 1;
    if (probs[2] > probs[best]) best = 2;

    auto t1 = std::chrono::steady_clock::now();
    v.loaded = true;
    v.predicted_class = best;
    v.prob_safe = probs[0];
    v.prob_confirm = probs[1];
    v.prob_block = probs[2];
    v.latency_us = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count()
    );

    const char* label = (best == 0) ? "SAFE_AUTO" : (best == 1 ? "HUMAN_CONFIRM" : "BLOCK_THREAT");
    v.json = std::string(R"({"loaded":true,"params":2016771,"label":")") + label +
        R"(","p_safe":)" + std::to_string(v.prob_safe) +
        R"(,"p_confirm":)" + std::to_string(v.prob_confirm) +
        R"(,"p_block":)" + std::to_string(v.prob_block) +
        R"(,"latency_us":)" + std::to_string(v.latency_us) + "}";
    return v;
}

} // namespace aegis
