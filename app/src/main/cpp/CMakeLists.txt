cmake_minimum_required(VERSION 3.22.1)

project(e2b_speedlab_native LANGUAGES C CXX)

add_library(e2b_extreme SHARED
    extreme_benchmark.cpp
    extreme_alias.cpp
)

target_compile_features(e2b_extreme PRIVATE cxx_std_17)
target_compile_options(e2b_extreme PRIVATE -O3 -DNDEBUG -ffast-math)
target_link_libraries(e2b_extreme PRIVATE dl log)

add_library(e2b_nativegpu SHARED
    native_gpu_benchmark.cpp
)

target_compile_features(e2b_nativegpu PRIVATE cxx_std_17)
target_compile_options(e2b_nativegpu PRIVATE -O3 -DNDEBUG -ffast-math)
target_link_libraries(e2b_nativegpu PRIVATE dl log)

add_library(e2b_affinity SHARED
    cpu_affinity.cpp
)

target_compile_features(e2b_affinity PRIVATE cxx_std_17)
target_compile_options(e2b_affinity PRIVATE -O3 -DNDEBUG)
target_link_libraries(e2b_affinity PRIVATE log)
