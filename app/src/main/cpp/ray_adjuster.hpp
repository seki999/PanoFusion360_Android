#pragma once
#include <opencv2/core.hpp>
#include <vector>

namespace pano {
/** Matched pixel pairs (raw image coordinates) between cameras i and j. */
struct RayEdge { int i=0, j=0; std::vector<cv::Point2f> a, b; std::vector<double> w; /* optional per-match IRLS weights */ };
/** rms values are root-mean-square 2-D pixel transfer errors per matched point and direction. */
struct AdjustResult { bool ok=false; int iterations=0, edges=0; double rmsBefore=0, rmsAfter=0; };
/**
 * Levenberg–Marquardt over one focal per group and rotations of cameras 1..n-1 (camera 0 fixed).
 * [focals] (per group) and [rotations] (per camera) are read as the start point and overwritten.
 */
AdjustResult adjustRays(const std::vector<RayEdge>& edges, const std::vector<int>& group, const std::vector<cv::Point2d>& centers,
                        std::vector<double>& focals, std::vector<cv::Matx33d>& rotations, int maxIterations = 100);
}
