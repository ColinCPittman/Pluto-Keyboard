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

# ---- libplutotranscribe.so: transcribe.cpp (submodule, pinned) for the Nemotron / Parakeet
# voice engine (see transcribe/transcribe_jni.cpp). transcribe.cpp vendors its own ggml, whose
# target names clash with llama.cpp's, so it is configured and built as a separate CMake build
# (same toolchain and flags) and its static libraries are linked here, all hidden behind the
# JNI exports like libfutoctxlm. Desktop builds are for the context harness, so there it is only
# built on request (--target plutotranscribe).
include(ExternalProject)
set(TRANSCRIBE_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/transcribe-install)
set(TRANSCRIBE_LIBS transcribe ggml ggml-cpu ggml-base)
set(TRANSCRIBE_ARCHIVES "")
foreach(lib ${TRANSCRIBE_LIBS})
    list(APPEND TRANSCRIBE_ARCHIVES ${TRANSCRIBE_PREFIX}/lib/${CMAKE_STATIC_LIBRARY_PREFIX}${lib}${CMAKE_STATIC_LIBRARY_SUFFIX})
endforeach()
set(TRANSCRIBE_CMAKE_ARGS
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=${TRANSCRIBE_PREFIX}
        -DCMAKE_INSTALL_LIBDIR=lib
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        "-DCMAKE_C_FLAGS=${CMAKE_C_FLAGS} -fvisibility=hidden -ffunction-sections -fdata-sections"
        "-DCMAKE_CXX_FLAGS=${CMAKE_CXX_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections"
        -DTRANSCRIBE_BUILD_TESTS=OFF
        -DTRANSCRIBE_BUILD_EXAMPLES=OFF
        -DTRANSCRIBE_BUILD_TOOLS=OFF
        -DTRANSCRIBE_BUILD_SHARED=OFF
        -DTRANSCRIBE_USE_OPENMP=OFF
        -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF
        -DTRANSCRIBE_GGML_BACKEND_DL=OFF
        -DTRANSCRIBE_INSTALL=ON
        -DGGML_NATIVE=OFF)
if(ANDROID)
    list(APPEND TRANSCRIBE_CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=${ANDROID_STL}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        list(APPEND TRANSCRIBE_CMAKE_ARGS "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16")
    endif()
    set(TRANSCRIBE_EXCLUDE OFF)
else()
    list(APPEND TRANSCRIBE_CMAKE_ARGS
            -DCMAKE_C_COMPILER=${CMAKE_C_COMPILER}
            -DCMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
    set(TRANSCRIBE_EXCLUDE ON)
endif()
# Built every time (a no-op when nothing changed) so a new transcribe.cpp pin is picked up. The
# install runs in the build step, which declares the archives, so Ninja sees when they change.
ExternalProject_Add(transcribe_ext
        SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/transcribe.cpp
        BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/transcribe-build
        CMAKE_ARGS ${TRANSCRIBE_CMAKE_ARGS}
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --config Release --target transcribe
              COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS ${TRANSCRIBE_ARCHIVES}
        BUILD_ALWAYS ON
        EXCLUDE_FROM_ALL ${TRANSCRIBE_EXCLUDE})

add_library(plutotranscribe SHARED transcribe/transcribe_jni.cpp)
set_target_properties(plutotranscribe PROPERTIES EXCLUDE_FROM_ALL ${TRANSCRIBE_EXCLUDE})
add_dependencies(plutotranscribe transcribe_ext)
target_include_directories(plutotranscribe PRIVATE transcribe.cpp/include)
target_link_libraries(plutotranscribe PRIVATE ${TRANSCRIBE_ARCHIVES})

if(ANDROID)
    target_link_libraries(plutotranscribe PRIVATE log m dl)
    target_link_options(plutotranscribe PRIVATE
            -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/transcribe/exports.map
            -Wl,--exclude-libs,ALL
            -Wl,--gc-sections
            -Wl,-z,max-page-size=16384)
else()
    find_package(JNI REQUIRED)
    target_include_directories(plutotranscribe PRIVATE ${JNI_INCLUDE_DIRS})
endif()

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
