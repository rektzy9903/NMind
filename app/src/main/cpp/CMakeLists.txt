cmake_minimum_required(VERSION 3.22.1)
project("claudebridge")

set(LIBNODE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

if(NOT EXISTS "${LIBNODE}")
    message(FATAL_ERROR
        "libnode.so not found at ${LIBNODE}\n"
        "Run: bash scripts/download-libnode.sh")
endif()

# ── Prebuilt libnode.so from nodejs-mobile release ─────────────────────────────
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION "${LIBNODE}")

# ── JNI bridge: Kotlin calls NodeEngine.nativeStart(String[]) ─────────────────
add_library(nodebridge SHARED node_bridge.cpp)
target_link_libraries(nodebridge node android log)

# ── Standalone Node.js launcher ────────────────────────────────────────────────
# Named libnode-launcher.so so AGP packages it in lib/<abi>/ and Android
# extracts it to nativeLibraryDir where it can be executed (API 29+).
add_executable(node_launcher node_launcher.cpp)
target_link_libraries(node_launcher node)
set_target_properties(node_launcher PROPERTIES
    OUTPUT_NAME              "libnode-launcher"
    SUFFIX                   ".so"
    PREFIX                   ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

# ── fd-closer + exec shim (launches proot without inherited-fd leakage) ───────
# Closes all fds > 2 then execv's its argv[1]. Shipped as libfdexec.so so it
# lands in nativeLibraryDir (exec-capable). mksh can't do this — see fdexec.cpp.
add_executable(fdexec fdexec.cpp)
set_target_properties(fdexec PROPERTIES
    OUTPUT_NAME              "libfdexec"
    SUFFIX                   ".so"
    PREFIX                   ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
