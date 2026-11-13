# pkmcore — the reusable C++ core of YAOP.
#   pkmcore        object model, block editor ops, rich-text codec, merge (CRDT-ish), queries.
#                  Needs only Qt6 Core + Gui.
#   pkmcore_vault  optional: E2EE vault (XChaCha20, HKDF, BIP39), backup file, sync helpers.
#                  Needs libsodium + zstd. Turn off with -DPKMCORE_VAULT=OFF.
# Use it from another project with add_subdirectory(pkmcore) and link pkmcore (and pkmcore_vault).
cmake_minimum_required(VERSION 3.21)
project(pkmcore VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

option(PKMCORE_VAULT "Build the E2EE/sync module (libsodium + zstd)" ON)
option(PKMCORE_TESTS "Build pkmcore_tests" ON)
set(PKMCORE_SPEC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../spec" CACHE PATH "Contracts and fixtures (spec/)")

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui)

add_library(pkmcore STATIC
    editor/model.h editor/model_runs.cpp editor/model_blocks.cpp editor/model_object.cpp
    editor/merge.h editor/merge.cpp
    editor/blockops.h editor/blockops.cpp
    editor/spancodec.h editor/spancodec.cpp
    editor/query.h editor/query.cpp
)
target_include_directories(pkmcore PUBLIC editor)
target_link_libraries(pkmcore PUBLIC Qt6::Core Qt6::Gui)

if(PKMCORE_VAULT)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(SODIUM REQUIRED IMPORTED_TARGET libsodium)
    pkg_check_modules(LIBZSTD REQUIRED IMPORTED_TARGET libzstd)
    add_library(pkmcore_vault STATIC
        vault/vaultcrypto.h vault/vaultcrypto.cpp
        vault/bip39.h vault/bip39.cpp
        vault/backup.h vault/backup.cpp
        vault/remoteops.h vault/remoteops.cpp
        vault/nodeops.h vault/nodeops.cpp
        vault/syncpolicy.h vault/bootstrapsnapshot.h
    )
    target_include_directories(pkmcore_vault PUBLIC vault)
    target_link_libraries(pkmcore_vault PUBLIC Qt6::Core PkgConfig::SODIUM PkgConfig::LIBZSTD)
    # The BIP39 wordlist is baked into the binary (never downloaded): resource :/crypto/bip39_english.txt
    qt_add_resources(pkmcore_vault "pkmcore_wordlist"
        PREFIX "/" BASE "${PKMCORE_SPEC_DIR}/fixtures"
        FILES "${PKMCORE_SPEC_DIR}/fixtures/crypto/bip39_english.txt")
endif()

if(PKMCORE_TESTS)
    enable_testing()
    add_executable(pkmcore_tests tests/main.cpp tests/testing.h tests/editor_tests.cpp)
    target_link_libraries(pkmcore_tests PRIVATE pkmcore)
    target_include_directories(pkmcore_tests PRIVATE tests)
    target_compile_definitions(pkmcore_tests PRIVATE PKMCORE_SPEC_DIR="${PKMCORE_SPEC_DIR}/fixtures")
    if(PKMCORE_VAULT)
        target_sources(pkmcore_tests PRIVATE tests/vault_tests.cpp)
        target_link_libraries(pkmcore_tests PRIVATE pkmcore_vault)
        target_compile_definitions(pkmcore_tests PRIVATE PKMCORE_WITH_VAULT)
    endif()
    add_test(NAME pkmcore_tests COMMAND pkmcore_tests)
endif()

option(PKMCORE_EXAMPLES "Build examples/tour.cpp" ON)
if(PKMCORE_EXAMPLES)
    add_executable(pkmcore_tour examples/tour.cpp)
    target_link_libraries(pkmcore_tour PRIVATE pkmcore)
endif()
