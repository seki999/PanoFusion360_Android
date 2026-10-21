# OpenCV stitching CPU module 4.12.0

Source: https://github.com/opencv/opencv/tree/4.12.0/modules/stitching/src

OpenCV official Android AAR exposes the base modules and stitching headers, but does not export the required stitching/detail implementations. The app compiles the necessary official C++ sources into its JNI library and links the same-version official AAR for core/features/calib3d/imgproc operations.

Local build adaptations:

- `precomp.hpp` includes `cpu_compat.hpp` instead of OpenCV's unavailable internal build header.
- Internal optional performance trace hooks are disabled. No numerical algorithm is replaced.
- Generated OpenCL kernel header includes are removed for this CPU-only build. HAVE_OPENCL/HAVE_CUDA are not defined.
- CMake selects autocalib, camera, matchers, motion_estimators, exposure_compensate, seam_finders, blenders, util and warpers. Unused upstream files are retained for source provenance.

Each source retains its original copyright/license header. See LICENSE (OpenCV repository Apache-2.0) and the per-file BSD-style notices. Application APK includes these notices in assets.
