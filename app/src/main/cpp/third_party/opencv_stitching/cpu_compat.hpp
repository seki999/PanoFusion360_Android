// CPU-only standalone build: disable upstream internal performance tracing.
#pragma once
#include <opencv2/core/cuda.hpp>
#define CV_INSTRUMENT_REGION() ((void)0)
