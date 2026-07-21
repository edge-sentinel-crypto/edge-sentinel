# Edge Sentinel — Native DIAG Helper
# Qualcomm DIAG interface — clean-room implementation
#
# Builds a JNI shared library that interfaces with the Qualcomm DIAG port
# (/dev/diag) for baseband message capture on rooted devices.

cmake_minimum_required(VERSION 3.22.1)
project("edgesentinel" LANGUAGES C)

add_library(diag-helper SHARED diag-helper.c)

target_link_libraries(diag-helper
    android
    log
)

# Treat warnings as errors for safety-critical code
target_compile_options(diag-helper PRIVATE -Wall -Wextra -Werror)
