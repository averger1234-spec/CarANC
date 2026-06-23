# CMakeLists.txt for CarANC native low-freq path prototype (CYCLE3_EXTRA)
# Placed under shared/src/androidMain/cpp/ for KMP androidMain native sources.
#
# Usage notes (see shared/build.gradle.kts for gradle wiring -- currently COMMENTED to keep pure-kotlin compile simple):
#   - Requires Android NDK (r25+ recommended).
#   - In shared/build.gradle.kts inside android { } add (uncomment):
#       externalNativeBuild {
#           cmake {
#               path = "src/androidMain/cpp/CMakeLists.txt"
#               version = "3.22.1"
#           }
#       }
#       ndk {
#           abiFilters 'armeabi-v7a', 'arm64-v8a'
#       }
#       defaultConfig {
#           externalNativeBuild {
#               cmake {
#                   arguments "-DANDROID_STL=c++_shared"
#                   cppFlags "-std=c++17 -O3 -ffast-math"
#               }
#           }
#       }
#
#   Then: ./gradlew :shared:assembleRelease  (will build .so into merged jni)
#   The kotlin actual does System.loadLibrary("caranc_lowband")
#   Output: libcaranc_lowband.so per ABI.
#
# This is PROTOTYPE only -- minimal to demonstrate build + JNI surface.
# No real DSP yet; the .cpp provides the declared natives as no-op returning 0.
# Expand in next cycle: full C++ LMS for rumble band.

cmake_minimum_required(VERSION 3.22.1)

project(caranc_lowband LANGUAGES CXX)

# Add the impl
add_library(
    caranc_lowband
    SHARED
    NativeLowBandLms.cpp
)

target_include_directories(caranc_lowband PRIVATE .)

# Link log for android (use __android_log_write etc if extended)
find_library(log-lib log)
target_link_libraries(caranc_lowband ${log-lib})

# Optimization flags for release (LMS hot path cares)
target_compile_options(caranc_lowband PRIVATE
    $<$<CONFIG:Release>:-O3 -ffast-math -fvisibility=hidden>
)

# Future: if using own FFT, add sources or find_package for kissfft/ne10 etc (small header-only preferred for ANC).
# Keep footprint tiny for in-car audio thread.
