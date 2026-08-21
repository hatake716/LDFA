cmake_minimum_required(VERSION 3.22)
project(ldfa_embedded_x11)

get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(UPSTREAM_CPP "${REPOSITORY_ROOT}/vendor/termux-x11/lorie/src/main/cpp")
set(HARDENED_DIR "${CMAKE_CURRENT_BINARY_DIR}/ldfa-hardened-native")
set(HARDEN_SCRIPT "${REPOSITORY_ROOT}/embedded-x11/scripts/prepare_embedded_native.py")

execute_process(
    COMMAND python3 "${HARDEN_SCRIPT}" "${UPSTREAM_CPP}" "${HARDENED_DIR}"
    RESULT_VARIABLE HARDEN_RESULT
    OUTPUT_VARIABLE HARDEN_OUTPUT
    ERROR_VARIABLE HARDEN_ERROR
)
if(NOT HARDEN_RESULT EQUAL 0)
    message(FATAL_ERROR "Failed to prepare embedded Termux:X11 native sources: ${HARDEN_ERROR}")
endif()
message(STATUS "${HARDEN_OUTPUT}")

# Let upstream define all Xorg/Termux:X11 targets and dependency recipes first.
add_subdirectory("${UPSTREAM_CPP}" "${CMAKE_CURRENT_BINARY_DIR}/upstream")

# The renderer itself now runs in a normal Android application process, so use upstream renderer.cpp
# unchanged (including its Java Surface fallback). Only shell-entry-specific cmdentrypoint.cpp and
# activity.cpp receive small embedded-process safety/instrumentation patches.
get_target_property(LDFA_XLORIE_SOURCES Xlorie SOURCES)
set(LDFA_FILTERED_SOURCES)
foreach(SOURCE_FILE IN LISTS LDFA_XLORIE_SOURCES)
    if(SOURCE_FILE MATCHES "(^|/)lorie/(cmdentrypoint|activity)\\.cpp$")
        continue()
    endif()
    list(APPEND LDFA_FILTERED_SOURCES "${SOURCE_FILE}")
endforeach()
set_property(TARGET Xlorie PROPERTY SOURCES "${LDFA_FILTERED_SOURCES}")
target_sources(Xlorie PRIVATE
    "${HARDENED_DIR}/cmdentrypoint.cpp"
    "${HARDENED_DIR}/activity.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/embedded_server.cpp"
)
target_include_directories(Xlorie PRIVATE "${UPSTREAM_CPP}/lorie")
