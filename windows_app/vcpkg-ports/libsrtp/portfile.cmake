vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO cisco/libsrtp
    REF "v${VERSION}"
    SHA512 6768f7976e5cc14a3bf2e9fc32042cab0b964f616fe5654516643a649a5d5f2b9ecb9e996467dd6d337777a9051b83a6e95f3cdc27e945062ce6da1cf8a2d462
    PATCHES
        cmake-config.diff
        fix-runtime-destination.patch
)

vcpkg_check_features(OUT_FEATURE_OPTIONS FEATURE_OPTIONS
    FEATURES
        openssl ENABLE_OPENSSL
)

vcpkg_cmake_configure(
    SOURCE_PATH "${SOURCE_PATH}"
    OPTIONS
        "-DCMAKE_PROJECT_INCLUDE=${CMAKE_CURRENT_LIST_DIR}/cmake-project-include.cmake"
        -DLIBSRTP_TEST_APPS=OFF
        # libsrtp defaults this ON and then cannot build under MinGW: its
        # debug prints use "%08x" while MinGW's ntohl() returns u_long, which
        # trips -Werror=format and -Werror=format-extra-args. Setting the
        # project's own option is the only reliable route - the -Werror flags
        # libsrtp appends land after anything injected via CMAKE_C_FLAGS, so
        # a triplet-level -Wno-error gets overridden.
        -DENABLE_WARNINGS_AS_ERRORS=OFF
        ${FEATURE_OPTIONS}
)

vcpkg_cmake_install()
vcpkg_cmake_config_fixup(CONFIG_PATH lib/cmake/libSRTP)
vcpkg_fixup_pkgconfig()

file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/include")

vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE")
