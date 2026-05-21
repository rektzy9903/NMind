#!/usr/bin/env bash
# Build llama-server for Android ARM64 and ARM32 using Android NDK.
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/libllamaserver.so
#   app/src/main/jniLibs/armeabi-v7a/libllamaserver.so
#
# Requirements:
#   - Android NDK 25.1.8937393 at $ANDROID_NDK_HOME or auto-detected from SDK
#   - cmake, git, make in PATH

set -euo pipefail

LLAMA_TAG="b5188"
CACHE_DIR=".llama-cache"
SOURCE_DIR="$CACHE_DIR/llama.cpp-$LLAMA_TAG"

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
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

mkdir -p "$CACHE_DIR"

# Download llama.cpp source (shared across both ABI builds)
if [ ! -d "$SOURCE_DIR" ]; then
    echo "⬇️  Downloading llama.cpp $LLAMA_TAG..."
    curl -L "https://github.com/ggerganov/llama.cpp/archive/refs/tags/$LLAMA_TAG.tar.gz" \
        -o "$CACHE_DIR/llama-$LLAMA_TAG.tar.gz"
    tar -xzf "$CACHE_DIR/llama-$LLAMA_TAG.tar.gz" -C "$CACHE_DIR"
    mv "$CACHE_DIR/llama.cpp-${LLAMA_TAG#b}" "$SOURCE_DIR" 2>/dev/null || true
    if [ ! -d "$SOURCE_DIR" ]; then
        EXTRACTED=$(ls -d "$CACHE_DIR"/llama.cpp-* 2>/dev/null | head -1)
        [ -n "$EXTRACTED" ] && mv "$EXTRACTED" "$SOURCE_DIR"
    fi
fi

build_abi() {
    local ABI="$1"
    local OUTPUT_DIR="app/src/main/jniLibs/$ABI"
    local OUTPUT_FILE="$OUTPUT_DIR/libllamaserver.so"
    local BUILD_DIR="$CACHE_DIR/build-$ABI"

    mkdir -p "$OUTPUT_DIR"

    # Check cache
    local CACHE_TAG_FILE="$CACHE_DIR/built_tag_$ABI"
    if [ -f "$OUTPUT_FILE" ]; then
        CACHED_TAG=$(cat "$CACHE_TAG_FILE" 2>/dev/null || echo "")
        if [ "$CACHED_TAG" = "$LLAMA_TAG" ]; then
            echo "✅ Using cached libllamaserver.so for $ABI (tag $LLAMA_TAG)"
            return
        fi
    fi

    echo "🔨 Building llama-server for $ABI..."
    rm -rf "$BUILD_DIR"

    # armeabi-v7a: disable llamafile (uses ARM64-only FP16 intrinsics not available on ARMv7)
    local EXTRA_FLAGS=""
    if [ "$ABI" = "armeabi-v7a" ]; then
        EXTRA_FLAGS="-DGGML_LLAMAFILE=OFF"
    fi

    cmake -S "$SOURCE_DIR" -B "$BUILD_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-29 \
        -DCMAKE_BUILD_TYPE=Release \
        -DGGML_METAL=OFF \
        -DGGML_CUDA=OFF \
        -DGGML_VULKAN=OFF \
        -DLLAMA_BUILD_SERVER=ON \
        -DLLAMA_CURL=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_EXE_LINKER_FLAGS="-pie -fPIE" \
        $EXTRA_FLAGS

    cmake --build "$BUILD_DIR" --target llama-server -j$(nproc)

    SERVER_BIN=$(find "$BUILD_DIR" -name "llama-server" -type f | head -1)
    if [ -z "$SERVER_BIN" ]; then
        echo "ERROR: llama-server binary not found after build for $ABI" >&2
        exit 1
    fi

    [ -f "$STRIP" ] && "$STRIP" "$SERVER_BIN" && echo "✂️  Stripped $ABI binary"

    cp "$SERVER_BIN" "$OUTPUT_FILE"
    echo "$LLAMA_TAG" > "$CACHE_TAG_FILE"

    SIZE=$(du -sh "$OUTPUT_FILE" | cut -f1)
    echo "✅ Built: $OUTPUT_FILE ($SIZE)"
}

build_abi "arm64-v8a"
build_abi "armeabi-v7a"
