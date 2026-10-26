# libfutoctxlm.so: llama.cpp (submodule, pinned) + a thin JNI scoring API for the
# voice-input context stage (see contextlm/contextlm_jni.cpp).
#
# Android: built by voiceinput-shared's externalNativeBuild (arm64-v8a).
# Desktop (verification only, see tools/context-harness):
#   cmake -S voiceinput-shared/src/main/cpp -B <build> -G Ninja -DCMAKE_BUILD_TYPE=Release \
#         -DJAVA_HOME=<jdk> [-DGGML_NATIVE=ON]
cmake_minimum_required(VERSION 3.22)
project(futoctxlm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Always optimize: ggml is unusably slow at -O0 (debug variants too).
foreach(cfg DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_C_FLAGS_${cfg} "-O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_${cfg} "-O3 -DNDEBUG")
endforeach()

# ---- llama.cpp / ggml: static, CPU only, no tools, no GPU backends, no networking
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
if(ANDROID)
    # Same configuration as the phone benchmark build (tools/context-bench, build-ctxrun-android):
    # dotprod + fp16 arithmetic, Q4_0 weights repacked for the ARM kernels at load time.
    # The app checks /proc/cpuinfo for asimddp/asimdhp before loading this library.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    endif()
endif()

# Hidden visibility for everything built from here on (ggml, llama, our code); the
# linker script below then exports only the JNI entry points.
add_compile_options(-fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)

add_subdirectory(llama.cpp llama EXCLUDE_FROM_ALL)

add_library(futoctxlm SHARED contextlm/contextlm_jni.cpp)
target_link_libraries(futoctxlm PRIVATE llama ggml)
target_include_directories(futoctxlm PRIVATE llama.cpp/include llama.cpp/ggml/include)

if(ANDROID)
    target_link_libraries(futoctxlm PRIVATE log)
    target_link_options(futoctxlm PRIVATE
            -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/contextlm/exports.map
            -Wl,--exclude-libs,ALL
            -Wl,--gc-sections
            -Wl,-z,max-page-size=16384)
else()
    find_package(JNI REQUIRED)
    target_include_directories(futoctxlm PRIVATE ${JNI_INCLUDE_DIRS})
    if(MINGW)
        # self-contained DLL (no libstdc++/winpthread DLLs needed next to it)
        target_link_options(futoctxlm PRIVATE -static -static-libgcc -static-libstdc++)
    elseif(UNIX)
        target_link_options(futoctxlm PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/contextlm/exports.map)
    endif()
endif()
