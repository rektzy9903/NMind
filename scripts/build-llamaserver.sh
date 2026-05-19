#!/usr/bin/env bash
# Build llama-server for Android ARM64 using Android NDK.
# Output: app/src/main/jniLibs/arm64-v8a/libllamaserver.so
#
# Requirements:
#   - Android NDK 25.1.8937393 at $ANDROID_NDK_HOME or auto-detected from SDK
#   - cmake, git, make in PATH

set -euo pipefail

LLAMA_TAG="b5188"
CACHE_DIR=".llama-cache"
OUTPUT_DIR="app/src/main/jniLibs/arm64-v8a"
OUTPUT_FILE="$OUTPUT_DIR/libllamaserver.so"
SOURCE_DIR="$CACHE_DIR/llama.cpp-$LLAMA_TAG"
BUILD_DIR="$CACHE_DIR/build-arm64"

# Find NDK
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ]; then
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.1.8937393"
    else
        echo "ERROR: Set ANDROID_NDK_HOME or ANDROID_HOME" >&2
        exit 1
    fi
fi
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
    echo "ERROR: NDK toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi

mkdir -p "$CACHE_DIR" "$OUTPUT_DIR"

# Check cache
if [ -f "$OUTPUT_FILE" ]; then
    CACHED_TAG=$(cat "$CACHE_DIR/built_tag" 2>/dev/null || echo "")
    if [ "$CACHED_TAG" = "$LLAMA_TAG" ]; then
        echo "✅ Using cached libllamaserver.so (tag $LLAMA_TAG)"
        exit 0
    fi
fi

# Download llama.cpp source
if [ ! -d "$SOURCE_DIR" ]; then
    echo "⬇️  Downloading llama.cpp $LLAMA_TAG..."
    curl -L "https://github.com/ggerganov/llama.cpp/archive/refs/tags/$LLAMA_TAG.tar.gz" \
        -o "$CACHE_DIR/llama-$LLAMA_TAG.tar.gz"
    tar -xzf "$CACHE_DIR/llama-$LLAMA_TAG.tar.gz" -C "$CACHE_DIR"
    mv "$CACHE_DIR/llama.cpp-${LLAMA_TAG#b}" "$SOURCE_DIR" 2>/dev/null || true
    # Some tags use numeric directory names
    if [ ! -d "$SOURCE_DIR" ]; then
        EXTRACTED=$(ls -d "$CACHE_DIR"/llama.cpp-* 2>/dev/null | head -1)
        [ -n "$EXTRACTED" ] && mv "$EXTRACTED" "$SOURCE_DIR"
    fi
fi

echo "🔨 Building llama-server for arm64-v8a..."
rm -rf "$BUILD_DIR"
cmake -S "$SOURCE_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_METAL=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_VULKAN=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_EXE_LINKER_FLAGS="-pie -fPIE"

cmake --build "$BUILD_DIR" --target llama-server -j$(nproc)

# Find and copy the binary
SERVER_BIN=$(find "$BUILD_DIR" -name "llama-server" -type f | head -1)
if [ -z "$SERVER_BIN" ]; then
    echo "ERROR: llama-server binary not found after build" >&2
    exit 1
fi

# Strip debug symbols to reduce size
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -f "$STRIP" ] && "$STRIP" "$SERVER_BIN" && echo "✂️  Stripped binary"

cp "$SERVER_BIN" "$OUTPUT_FILE"
echo "$LLAMA_TAG" > "$CACHE_DIR/built_tag"

SIZE=$(du -sh "$OUTPUT_FILE" | cut -f1)
echo "✅ Built: $OUTPUT_FILE ($SIZE)"
