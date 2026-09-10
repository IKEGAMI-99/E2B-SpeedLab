cmake_minimum_required(VERSION 3.22.1)

project(e2b_extreme LANGUAGES C CXX)

add_library(e2b_extreme SHARED extreme_benchmark.cpp)

target_compile_features(e2b_extreme PRIVATE cxx_std_17)
target_compile_options(e2b_extreme PRIVATE -O3 -DNDEBUG -ffast-math)

target_link_libraries(e2b_extreme PRIVATE dl log)
