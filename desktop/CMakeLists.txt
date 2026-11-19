cmake_minimum_required(VERSION 3.21)

project(YaopDesktop VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Qml Quick QuickControls2 Svg Network Pdf Test)
find_package(Qt6Keychain REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SODIUM REQUIRED IMPORTED_TARGET libsodium)
# SQLCipher (C API): local store and its indexes encrypted at rest (spec §15.5).
pkg_check_modules(SQLCIPHER REQUIRED IMPORTED_TARGET sqlcipher)

qt_standard_project_setup()

# The reusable core (model, editor ops, merge, query, vault crypto) lives in ../pkmcore.
set(PKMCORE_TESTS OFF CACHE BOOL "" FORCE)
set(PKMCORE_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(../pkmcore pkmcore)

qt_add_executable(yaop-desktop
    src/main.cpp
    src/driver.h
    src/driver.cpp
    src/applock.h
    src/applock.cpp
    src/onboarding.h
    src/onboarding.cpp
    src/store.h
    src/store.cpp
    src/searchindex.h
    src/searchindex.cpp
    src/cachecrypto.h
    src/cachecrypto.cpp
    src/outbox.h
    src/outbox.cpp
    src/syncclient.h
    src/syncclient.cpp
    src/workspace.h
    src/workspace.cpp
    src/workspace_internal.h
    src/workspace_objects.cpp
    src/workspace_search.cpp
    src/workspace_weblinks.cpp
    src/workspace_media.cpp
    src/workspace_sync.cpp
    src/workspace_backup.cpp
    src/editormodel.h
    src/editormodel.cpp
    src/providers.h
    src/providers.cpp
    src/texrender_internal.h
    src/texrender.cpp
    src/syntaxhighlight.h
    src/syntaxhighlight.cpp
    src/audioplayback.h
    src/audioplayback.cpp
    src/keymap.h
    src/keymap.cpp
    src/keyinput.h
    src/keyinput.cpp
    src/selftest.h
    src/selftest.cpp
    third_party/qrcodegen/qrcodegen.cpp
)

file(GLOB QML_FILES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/qml/*.qml" "qml/editor/*.qml")
file(GLOB ICON_FILES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/assets/icons/*.svg")
file(GLOB FONT_FILES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/assets/fonts/*.otf" "${CMAKE_CURRENT_SOURCE_DIR}/assets/fonts/*.ttf")

qt_add_resources(yaop-desktop "qml"
    PREFIX "/"
    BASE "${CMAKE_CURRENT_SOURCE_DIR}"
    FILES ${QML_FILES} "${CMAKE_CURRENT_SOURCE_DIR}/qml/qmldir"
)

qt_add_resources(yaop-desktop "assets"
    PREFIX "/"
    BASE "${CMAKE_CURRENT_SOURCE_DIR}"
    FILES ${ICON_FILES} ${FONT_FILES} "${CMAKE_CURRENT_SOURCE_DIR}/assets/yaop.svg"
)

target_link_libraries(yaop-desktop PRIVATE
    pkmcore
    pkmcore_vault
    Qt6::Core
    Qt6::Gui
    Qt6::Qml
    Qt6::Quick
    Qt6::QuickControls2
    Qt6::Svg
    Qt6::Network
    Qt6::Pdf
    Qt6::Test   # --drive keys: real key delivery through the shortcut map
    Qt6Keychain::Qt6Keychain
    PkgConfig::SODIUM
    PkgConfig::SQLCIPHER
)

set_target_properties(yaop-desktop PROPERTIES
    OUTPUT_NAME yaop
)

install(TARGETS yaop-desktop RUNTIME DESTINATION bin)
install(FILES yaop.desktop DESTINATION share/applications)
install(FILES assets/yaop.svg DESTINATION share/icons/hicolor/scalable/apps RENAME yaop.svg)

target_include_directories(yaop-desktop PRIVATE ../pkmcore/tests)
target_compile_definitions(yaop-desktop PRIVATE PKMCORE_SPEC_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../spec/fixtures")
target_compile_definitions(yaop-desktop PRIVATE PROJECT_VERSION_STR="${PROJECT_VERSION}")
