cmake_minimum_required(VERSION 3.18.1)



# Host-side unit tests (desktop). Android app build uses the full CMakeLists below.
# Use CMAKE_SYSTEM_NAME — ANDROID is not always defined when this file is parsed.

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")

    project(vision_robotics_host_tests LANGUAGES CXX)

    set(CMAKE_CXX_STANDARD 17)

    set(CMAKE_CXX_STANDARD_REQUIRED ON)



    set(ROBOTICS_CORE_SOURCES

        resample/resample_core.cpp

        translation/text_cluster_core.cpp

        video/video_core.cpp

    )

    set(ROBOTICS_TEST_SOURCES

        test/resample_core_test.cpp

        test/text_cluster_core_test.cpp

        test/video_core_test.cpp

        test/sphere_math_test.cpp

    )



    add_executable(robotics_cpp_tests

        test/test_main.cpp

        ${ROBOTICS_TEST_SOURCES}

        ${ROBOTICS_CORE_SOURCES}

    )

    target_include_directories(robotics_cpp_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    enable_testing()

    add_test(NAME robotics_cpp_tests COMMAND robotics_cpp_tests)

    return()

endif()



project("vision_robotics")



set(ROBOTICS_CORE_SOURCES

    resample/resample_core.cpp

    translation/text_cluster_core.cpp

    video/video_core.cpp

)



set(ROBOTICS_TEST_SOURCES

    test/resample_core_test.cpp

    test/text_cluster_core_test.cpp

    test/video_core_test.cpp

    test/sphere_math_test.cpp

)



function(configure_vision_lib target_name source_file)

    add_library(${target_name} SHARED ${source_file})

    target_compile_options(${target_name} PRIVATE -Wall -Wextra -O2)

    if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")

        target_link_options(${target_name} PRIVATE "LINKER:-z,max-page-size=16384")

    endif()

    find_library(log-lib log)

    target_link_libraries(${target_name} ${log-lib})

endfunction()



configure_vision_lib(vision_segmenter segmenter/segmenter_jni.cpp)

configure_vision_lib(vision_nightvision nightvision/nightvision_jni.cpp)

configure_vision_lib(vision_depth depth/depth_jni.cpp)

configure_vision_lib(vision_accumulator accumulator/accumulator_jni.cpp)

configure_vision_lib(vision_edge edge/edge_jni.cpp)

configure_vision_lib(vision_opticalflow opticalflow/opticalflow_jni.cpp)



add_library(vision_translator SHARED

    translation/translation_jni.cpp

    translation/text_cluster_core.cpp)

target_compile_options(vision_translator PRIVATE -Wall -Wextra -O2)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")

    target_link_options(vision_translator PRIVATE "LINKER:-z,max-page-size=16384")

endif()

find_library(log-lib-translator log)

target_link_libraries(vision_translator ${log-lib-translator})



add_library(vision_resample SHARED

    resample/resample_jni.cpp

    resample/resample_core.cpp)

target_compile_options(vision_resample PRIVATE -Wall -Wextra -O2)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")

    target_link_options(vision_resample PRIVATE "LINKER:-z,max-page-size=16384")

endif()

find_library(log-lib-resample log)

target_link_libraries(vision_resample ${log-lib-resample})



add_library(vision_video SHARED

    video/video_jni.cpp

    video/video_core.cpp)

target_compile_options(vision_video PRIVATE -Wall -Wextra -O2)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")

    target_link_options(vision_video PRIVATE "LINKER:-z,max-page-size=16384")

endif()

find_library(log-lib-video log)

target_link_libraries(vision_video ${log-lib-video})



add_library(vision_sphere SHARED panorama/sphere_jni.cpp)

target_compile_options(vision_sphere PRIVATE -Wall -Wextra -O2)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")

    target_link_options(vision_sphere PRIVATE "LINKER:-z,max-page-size=16384")

endif()

find_library(log-lib-sphere log)

target_link_libraries(vision_sphere ${log-lib-sphere} -lm)



add_library(robotics_cpp_tests SHARED

    test/robotics_test_jni.cpp

    ${ROBOTICS_TEST_SOURCES}

    ${ROBOTICS_CORE_SOURCES}

)

target_include_directories(robotics_cpp_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(robotics_cpp_tests PRIVATE -Wall -Wextra -O2)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")

    target_link_options(robotics_cpp_tests PRIVATE "LINKER:-z,max-page-size=16384")

endif()

find_library(log-lib-tests log)

target_link_libraries(robotics_cpp_tests ${log-lib-tests})

