#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace aegis {

constexpr std::size_t kFrameWidth = 768;
constexpr std::size_t kFrameHeight = 768;
constexpr std::size_t kFrameChannels = 3; // RGB888
constexpr std::size_t kAshmemFrameBytes = kFrameWidth * kFrameHeight * kFrameChannels; // 1,769,472 bytes (1.69 MB)

/// Compiler-barrier-safe zeroing that survives -O2/-O3 dead-store elimination.
inline void secure_zero(void* ptr, std::size_t len) noexcept {
    if (!ptr || len == 0) return;
#if defined(__GLIBC__) && !defined(__ANDROID__)
    explicit_bzero(ptr, len);
#else
    volatile std::uint8_t* p = static_cast<volatile std::uint8_t*>(ptr);
    while (len--) {
        *p++ = 0;
    }
#if defined(__clang__) || defined(__GNUC__)
    asm volatile("" : : "r"(ptr) : "memory");
#endif
#endif
}

/// RAII wrapper for Process 1 writable ashmem/memfd screenshot buffer.
/// Guarantees cryptographic-grade zeroing on normal exit, abort, or exception.
class ScopedAshmemFrame {
public:
    ScopedAshmemFrame(void* addr, std::size_t size, bool writable) noexcept
        : addr_(addr), size_(size), writable_(writable) {}

    ~ScopedAshmemFrame() {
        wipe_and_unmap();
    }

    ScopedAshmemFrame(const ScopedAshmemFrame&) = delete;
    ScopedAshmemFrame& operator=(const ScopedAshmemFrame&) = delete;

    void* data() const noexcept { return addr_; }
    std::size_t size() const noexcept { return size_; }

    void wipe_and_unmap() noexcept {
        if (addr_ && addr_ != MAP_FAILED) {
            if (writable_) {
                secure_zero(addr_, size_);
            }
            ::madvise(addr_, size_, MADV_DONTNEED);
            ::munmap(addr_, size_);
            addr_ = nullptr;
            size_ = 0;
        }
    }

private:
    void* addr_;
    std::size_t size_;
    bool writable_;
};

} // namespace aegis
