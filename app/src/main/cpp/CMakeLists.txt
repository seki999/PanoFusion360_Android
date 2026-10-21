cmake_minimum_required(VERSION 3.22.1)
project(panofusion LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(OpenCV REQUIRED CONFIG)
set(STITCHING_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv_stitching)
add_library(panofusion SHARED panorama_engine.cpp jni_bridge.cpp
    ${STITCHING_DIR}/autocalib.cpp ${STITCHING_DIR}/camera.cpp
    ${STITCHING_DIR}/matchers.cpp ${STITCHING_DIR}/motion_estimators.cpp
    ${STITCHING_DIR}/exposure_compensate.cpp ${STITCHING_DIR}/seam_finders.cpp
    ${STITCHING_DIR}/blenders.cpp ${STITCHING_DIR}/util.cpp
    ${STITCHING_DIR}/warpers.cpp)
target_link_libraries(panofusion OpenCV::opencv_java4 log)
target_link_options(panofusion PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(panofusion PRIVATE ${STITCHING_DIR})
