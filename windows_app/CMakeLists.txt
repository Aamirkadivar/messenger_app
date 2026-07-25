cmake_minimum_required(VERSION 3.24)
project(messenger_app VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Qt6 Configuration
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

# Find required Qt6 modules
find_package(Qt6 REQUIRED COMPONENTS Core Gui Widgets Qml Quick QuickControls2 Network Sql)

# Qt6 Base Path Configuration
# Primary: D:/qt, fallback to common locations
set(QT_BASE_PATH "D:/qt")
if(NOT EXISTS "${QT_BASE_PATH}")
    if(EXISTS "C:/Qt")
        set(QT_BASE_PATH "C:/Qt")
    elseif(DEFINED ENV{QT_PATH})
        set(QT_BASE_PATH "$ENV{QT_PATH}")
    endif()
endif()

# Find Qt6 version directory
set(QT6_VERSION_DIR "")
if(EXISTS "${QT_BASE_PATH}")
    file(GLOB QT6_DIRS "${QT_BASE_PATH}/6.*")
    list(GET QT6_DIRS 0 QT6_VERSION_DIR)
endif()

# Try to find Qt6 WebSockets (optional)
set(QT6_WEBSOCKETS_FOUND OFF)
if(QT6_VERSION_DIR AND EXISTS "${QT6_VERSION_DIR}/mingw_64/lib/cmake/Qt6WebSockets")
    list(APPEND CMAKE_PREFIX_PATH "${QT6_VERSION_DIR}/mingw_64")
    message(STATUS "Qt6 path: ${QT6_VERSION_DIR}/mingw_64")
endif()
find_package(Qt6 COMPONENTS WebSockets)
if(TARGET Qt6::WebSockets)
    set(QT6_WEBSOCKETS_FOUND ON)
    message(STATUS "Qt6 WebSockets found")
else()
    message(WARNING "Qt6 WebSockets not found - WebSocket support will be disabled")
endif()

# Find libsodium from vcpkg_installed
# Try x64-mingw-static first, fall back to x64-windows
set(VCPKG_INSTALLED_DIR "${CMAKE_SOURCE_DIR}/vcpkg_installed")
set(SODIUM_MINGW_INCLUDE "${VCPKG_INSTALLED_DIR}/x64-mingw-static/include")
set(SODIUM_MINGW_LIB "${VCPKG_INSTALLED_DIR}/x64-mingw-static/lib")
set(SODIUM_WIN_INCLUDE "${VCPKG_INSTALLED_DIR}/x64-windows/include")
set(SODIUM_WIN_LIB "${VCPKG_INSTALLED_DIR}/x64-windows/lib")

# Windows Credential Manager support
if(WIN32)
    add_definitions(-DWIN32 -D_WINDOWS -D_CRT_SECURE_NO_WARNINGS)
    set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS ON)
endif()

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src)

# ---- Source Files ----
set(PROJECT_SOURCES
    src/main.cpp
    src/crypto/encryption.cpp
    src/models/user.cpp
    src/models/user.h
    src/models/message.h
    src/models/chat.h
    src/services/authservice.cpp
    src/services/authservice.h
    src/services/websocketservice.cpp
    src/services/websocketservice.h
    src/services/chatservice.cpp
    src/services/chatservice.h
    src/utils/config.h
    src/utils/credentialmanager.cpp
    src/utils/credentialmanager.h
    src/utils/traynotifier.cpp
    src/utils/traynotifier.h
)

# ---- QML Resources ----
set(PROJECT_QML
    qml/main.qml
    qml/Login.qml
    qml/Register.qml
    qml/ChatList.qml
    qml/ChatView.qml
    qml/NewChatDialog.qml
    qml/MessageBubble.qml
    qml/Settings.qml
    resources/resources.qrc
)

# ---- Executable ----
qt_add_executable(${PROJECT_NAME}
    ${PROJECT_SOURCES}
    ${PROJECT_QML}
)

# ---- Link Libraries ----
target_link_libraries(${PROJECT_NAME} PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Widgets
    Qt6::Qml
    Qt6::Quick
    Qt6::QuickControls2
    Qt6::Network
    Qt6::Sql
)

if(QT6_WEBSOCKETS_FOUND)
    target_link_libraries(${PROJECT_NAME} PRIVATE Qt6::WebSockets)
    add_definitions(-DHAVE_WEBSOCKETS)
endif()

# Link libsodium
set(SODIUM_FOUND OFF)
set(SODIUM_INCLUDE_DIR "")
set(SODIUM_LIB "")
set(SODIUM_DLL "")

# Try MinGW .a first
set(MINGW_A "${SODIUM_MINGW_LIB}/libsodium.a")
if(EXISTS "${MINGW_A}")
    set(SODIUM_FOUND ON)
    set(SODIUM_INCLUDE_DIR "${SODIUM_MINGW_INCLUDE}")
    set(SODIUM_LIB "${MINGW_A}")
    message(STATUS "Found libsodium MinGW static lib: ${MINGW_A}")
endif()

# Fall back to x64-windows .lib
if(NOT SODIUM_FOUND)
    set(WIN_LIB "${SODIUM_WIN_LIB}/libsodium.lib")
    if(EXISTS "${WIN_LIB}")
        set(SODIUM_FOUND ON)
        set(SODIUM_INCLUDE_DIR "${SODIUM_WIN_INCLUDE}")
        set(SODIUM_LIB "${WIN_LIB}")
        # Find the DLL
        set(SODIUM_DLL "${SODIUM_WIN_LIB}/libsodium-19.dll")
        if(NOT EXISTS "${SODIUM_DLL}")
            set(SODIUM_DLL "${SODIUM_WIN_LIB}/libsodium.dll")
        endif()
        if(NOT EXISTS "${SODIUM_DLL}")
            set(SODIUM_DLL "${SODIUM_WIN_LIB}/../bin/libsodium.dll")
        endif()
        if(NOT EXISTS "${SODIUM_DLL}")
            set(SODIUM_DLL "${SODIUM_WIN_LIB}/../bin/libsodium-19.dll")
        endif()
        # Try debug
        if(NOT EXISTS "${SODIUM_DLL}")
            set(SODIUM_DLL "${VCPKG_INSTALLED_DIR}/x64-windows/debug/bin/libsodium.dll")
        endif()
        if(NOT EXISTS "${SODIUM_DLL}")
            set(SODIUM_DLL "${VCPKG_INSTALLED_DIR}/x64-windows/debug/bin/libsodium-19.dll")
        endif()
        message(STATUS "Found libsodium x64-windows lib: ${WIN_LIB}")
    endif()
endif()

if(SODIUM_FOUND)
    target_link_libraries(${PROJECT_NAME} PRIVATE "${SODIUM_LIB}")
    target_include_directories(${PROJECT_NAME} PRIVATE "${SODIUM_INCLUDE_DIR}")
    add_definitions(-DHAVE_LIBSODIUM)

    # Copy libsodium DLL to output if found
    if(EXISTS "${SODIUM_DLL}")
        add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${SODIUM_DLL}"
                "$<TARGET_FILE_DIR:${PROJECT_NAME}>"
        )
        message(STATUS "libsodium DLL will be copied: ${SODIUM_DLL}")
    else()
        message(WARNING "libsodium DLL not found - you may need libsodium.dll at runtime")
    endif()
else()
    message(WARNING "libsodium not found in vcpkg_installed. Encryption features disabled.")
endif()

# ---- Windows-specific linking ----
if(WIN32)
    target_link_libraries(${PROJECT_NAME} PRIVATE Advapi32 Crypt32)
endif()

# ---- Compiler flags ----
if(MSVC)
    add_compile_options(/W4 /utf-8)
elseif(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" OR CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    add_compile_options(-Wall -Wextra -Wpedantic)
endif()

# ---- Install ----
install(TARGETS ${PROJECT_NAME}
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)