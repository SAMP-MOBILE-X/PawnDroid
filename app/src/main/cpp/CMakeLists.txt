cmake_minimum_required(VERSION 3.18.1)
project(pawn_android LANGUAGES C)


option(PAWNDROID_PC_TEXT_COMPAT "Match stock PC 3.10.11 source text autodetection" ON)

option(PAWNDROID_PC_CODEGEN_COMPAT "Match stock PC 3.10.11 code generation" ON)


set(UPSTREAM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/omp-compiler")
set(PC "${UPSTREAM_ROOT}/source/compiler")
set(POSIX "${UPSTREAM_ROOT}/source/linux")
if(NOT EXISTS "${PC}/sc1.c" OR NOT EXISTS "${PC}/CMakeLists.txt")
    message(FATAL_ERROR "Missing openmultiplayer/compiler v3.10.11 source. Run scripts/prepare.ps1 in project root.")
endif()
file(READ "${PC}/CMakeLists.txt" UPSTREAM_VERSION)
if(NOT UPSTREAM_VERSION MATCHES "set\\(VERSION_BUILD[ \\t]+11\\)")
    message(FATAL_ERROR "Wrong compiler checkout: expected openmultiplayer/compiler v3.10.11")
endif()
set(VERSION_MAJOR 3)
set(VERSION_MINOR 10)
set(VERSION_BUILD 11)
set(VERSION_STR "3.10.11")
math(EXPR VERSION_INT "${VERSION_MAJOR} << 8 | ${VERSION_MINOR}")
configure_file("${PC}/version.h.in" "${CMAKE_CURRENT_BINARY_DIR}/version.h" @ONLY)

set(COMPILER_SRCS
  "${PC}/hashtable/wrap_hashtable.c"
  "${PC}/libpawnc.c"
  "${PC}/lstring.c"
  "${PC}/memfile.c"
  "${PC}/sc1.c"
  "${PC}/sc2.c"
  "${PC}/sc3.c"
  "${PC}/sc4.c"
  "${PC}/sc5.c"
  "${PC}/sc6.c"
  "${PC}/sc7.c"
  "${PC}/sci18n.c"
  "${PC}/sclist.c"
  "${PC}/scmemfil.c"
  "${PC}/scstate.c"
  "${PC}/scvars.c"
)

set_source_files_properties("${PC}/sc1.c" PROPERTIES COMPILE_DEFINITIONS NO_MAIN)
add_library(pawnc SHARED pawn_jni.c ${COMPILER_SRCS})
target_include_directories(pawnc PRIVATE "${PC}" "${POSIX}" "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_features(pawnc PRIVATE c_std_11)
target_compile_definitions(pawnc PRIVATE
  LINUX
  HAVE_STDINT_H HAVE_INTTYPES_H HAVE_UNISTD_H HAVE_ALLOCA_H HAVE_ENDIAN_H
  PAWNDROID_PC_TEXT_COMPAT=$<BOOL:${PAWNDROID_PC_TEXT_COMPAT}>
  PAWNDROID_PC_CODEGEN_COMPAT=$<BOOL:${PAWNDROID_PC_CODEGEN_COMPAT}>
  PAWN_CELL_SIZE=32
  sNAMEMAX=63
  _MAX_PATH=4096
)

target_compile_options(pawnc PRIVATE -O2 -UNDEBUG -fno-strict-aliasing -fwrapv -Wno-unused-parameter)
target_link_libraries(pawnc PRIVATE log)
target_link_options(pawnc PRIVATE -Wl,-Bsymbolic-functions)
message(STATUS "Native open.mp Pawn compiler: ${VERSION_STR} / Android runtime r6, PC text compatibility=${PAWNDROID_PC_TEXT_COMPAT}, PC code generation=${PAWNDROID_PC_CODEGEN_COMPAT}, sNAMEMAX=63, -O2, assertions enabled (NDK r26c)")
