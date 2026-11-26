#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_DIR="$ROOT_DIR/android-app/app/src/main/cpp/third_party/llama.cpp"
PATCH_FILE="$ROOT_DIR/android-app/app/src/main/cpp/patches/llama-cpp-mtmd-fd-stream.patch"
LLAMA_COMMIT="244640b7f26e2a7301b27a24edc4f7feaf97d52f"

if [ ! -d "$LLAMA_DIR/.git" ]; then
  mkdir -p "$(dirname "$LLAMA_DIR")"
  git clone https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
fi

git -C "$LLAMA_DIR" fetch origin
git -C "$LLAMA_DIR" checkout "$LLAMA_COMMIT"

if ! git -C "$LLAMA_DIR" apply --reverse --check "$PATCH_FILE" >/dev/null 2>&1; then
  git -C "$LLAMA_DIR" apply "$PATCH_FILE"
  echo "Applied mtmd fd:// patch cleanly."
else
  echo "mtmd fd:// patch already applied."
fi
