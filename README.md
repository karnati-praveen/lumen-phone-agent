# Lumen (Aegis Engine v2.1.0 Production Monorepo)

100% offline, process-isolated, capability-sandboxed Android phone AI agent (Android 14–17) combining **Kotlin + Jetpack Compose Material 3**, an **isolated C++20 VLM/LLM engine** (`SmolVLM-500M` / `llama.cpp` + `mtmd` over Ashmem shared memory), **Guard-2M** micro-safety gating, and a **Rust Security Broker** over UniFFI.

## Architecture Highlights

1. **Process-Isolated Vision-Language Runtime (`android-app/app/src/main/cpp/`, `IsolatedBrainService.kt`)**
   - Runs `SmolVLM-500M` (`llama.cpp` + `mtmd`) inside `android:isolatedProcess="true"` (`com.aegis.core:aegis_brain_isolated`).
   - Transfers 768×768 RGB888 screenshots (~1.69 MB) via `ASharedMemory` zero-copy file descriptors and `explicit_bzero` RAII (`ashmem_guard.hpp`), with lock-free `std::atomic<bool>` decode cancellation and adaptive KV headroom checks (`HardwareSecurity.kt`).
2. **Rust Capability Broker & Multi-Stage Sanitizer (`rust-broker/src/`)**
   - Level-0 hard policy enforcement (`policy.rs`) blocking financial transfers/payment intents (`upi://`, `tez://`, `phonepe://`, `paytmmp://`, etc.) and privileged system destructive actions.
   - Unicode NFKC + confusable folding + instruction boundary wrapping (`sanitizer.rs`), monotonic-clock 5s single-use Ed25519 capability tokens (`token.rs`), rate limiting, and SHA-256 hash-chained audit logs (`audit.rs`).
3. **Resilient Multi-App Grounding & Edge Glyph Overlay (`AgentController.kt`, `AegisAccessibilityService.kt`, `ui/`)**
   - Supports multi-step workflows across real-world and system apps (Chrome/Amazon multi-listing comparison, YouTube multi-video comparison, DeskClock, Contacts, Settings, Calendar, Maps) with VLM visual glow feedback (`AegisEdgeGlyphComposeOverlay` + `AegisLiveStepBottomBar`).

## Repository Layout

```text
.
├── android-app/                 # Kotlin + Compose Material 3 app & C++20 JNI/CMake build
│   └── app/src/main/cpp/
│       └── patches/             # Local llama.cpp fd:// stream patch for isolated mtmd mmproj loading
├── rust-broker/                 # Rust security broker (UniFFI cdylib + prebuilt jniLibs)
├── eval-suite/                  # Red-team security, RAM, live Pixel 9, AndroidWorld & real-world benchmarks
└── scripts/                     # Setup scripts to fetch & patch third_party/llama.cpp
```

## Setup & Build

### 1. Fetch & Patch `llama.cpp`

`third_party/llama.cpp` is excluded from Git and pinned to commit `244640b7f26e2a7301b27a24edc4f7feaf97d52f` with `android-app/app/src/main/cpp/patches/llama-cpp-mtmd-fd-stream.patch`:

```powershell
# Windows (PowerShell)
.\scripts\setup_llama_cpp.ps1
```

```bash
# macOS / Linux
bash scripts/setup_llama_cpp.sh
```

### 2. Build Android App & Unit Tests

```bash
cd android-app
./gradlew testDebugUnitTest assembleDebug
```

### 3. Rebuild Rust Broker Native Libraries (Optional — Prebuilt `.so` Included)

Prebuilt `arm64-v8a` and `x86_64` `libaegis_broker.so` binaries are committed under `android-app/app/src/main/jniLibs/`. To rebuild from `rust-broker/`:

```bash
cd rust-broker
cargo test
cargo ndk -t arm64-v8a -t x86_64 -o ../android-app/app/src/main/jniLibs build --release
```

## Verification & Evaluation Suite

```bash
# Red-team security suite (150 attacks), cryptographic token/audit checks & Android 17 RAM budgets
python eval-suite/run_aegis_eval_suite.py

# Live device / Pixel 9 emulator benchmarks (requires adb + installed debug APK)
python eval-suite/run_live_pixel9_eval.py
python eval-suite/run_android_world_probe.py
python eval-suite/run_multistep_6to10_eval.py
python eval-suite/run_realworld_apps_eval.py
```
