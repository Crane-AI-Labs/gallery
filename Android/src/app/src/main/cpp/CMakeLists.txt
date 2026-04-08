cmake_minimum_required(VERSION 3.13)
project(llama_jni)

set(CMAKE_CXX_STANDARD 17)

# Path to llama.rn TurboQuant fork (submodule in this repo)
set(LLAMA_RN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama-rn-turbo-quant")

# Build all rnllama variants from source
add_subdirectory(
    "${LLAMA_RN_DIR}/android/src/main/rnllama"
    "${CMAKE_CURRENT_BINARY_DIR}/rnllama"
)

# Function to build our JNI wrapper linked against a specific rnllama variant
function(build_jni_wrapper target_name rnllama_target)
    add_library(${target_name} SHARED llama_jni.cpp)

    target_include_directories(${target_name} PRIVATE
        ${LLAMA_RN_DIR}/cpp
        ${LLAMA_RN_DIR}/cpp/common
        ${LLAMA_RN_DIR}/cpp/tools/mtmd
    )

    target_link_libraries(${target_name}
        ${rnllama_target}
        android
        log
    )
endfunction()

# Build JNI wrapper for each variant — matching llama.rn's architecture
build_jni_wrapper("llama_jni" "rnllama")

if (ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    build_jni_wrapper("llama_jni_v8" "rnllama_v8")
    build_jni_wrapper("llama_jni_v8_2" "rnllama_v8_2")
    build_jni_wrapper("llama_jni_v8_2_dotprod" "rnllama_v8_2_dotprod")
    build_jni_wrapper("llama_jni_v8_2_i8mm" "rnllama_v8_2_i8mm")
    build_jni_wrapper("llama_jni_v8_2_dotprod_i8mm" "rnllama_v8_2_dotprod_i8mm")
elseif (ANDROID_ABI AND ANDROID_ABI STREQUAL "x86_64")
    build_jni_wrapper("llama_jni_x86_64" "rnllama_x86_64")
endif()
