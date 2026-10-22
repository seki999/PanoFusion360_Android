#pragma once
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <functional>
#include <string>
#include <vector>
namespace pano {
using Progress = std::function<void(const std::string&,int,int)>;
struct Analysis { double sharpness, features, clipped, overlap, displacement; };
/** Bidirectional ratio-tested ORB matches with a RANSAC homography over image-centred coordinates. */
struct Verified { std::vector<cv::DMatch> matches; std::vector<uchar> inliers; cv::Mat H; int count=0; double displacement=0; };
cv::Mat read(const std::string& path);
Verified match(const std::vector<cv::KeyPoint>& a, const cv::Mat& da, cv::Size sa, const std::vector<cv::KeyPoint>& b, const cv::Mat& db, cv::Size sb);
Analysis analyze(const std::string&, const std::string&);
/** Per camera: initial (focal, yaw, pitch) then adjusted (focal, yaw, pitch) relative to camera 0; then matchMs, adjustMs, iterations, rms before, rms after. */
std::vector<double> diagnoseCameras(const std::vector<std::string>& paths, const std::vector<int>& groups);
double stitch(const std::vector<std::string>& paths, const std::vector<int>& groups, const std::string& output, int width, const std::string& mode, int anchorIndex, double referenceYaw, Progress);

/** Visual relocalisation of a new camera view against a committed revision's keyframes. */
struct Pose { bool ok=false; double yaw=0, pitch=0, roll=0, focal=0; int inliers=0, keyframe=-1, frameSide=0; double rotationError=0; cv::Matx33d R; };
class Localizer {
public:
    Localizer(const std::vector<std::string>& keyframes, const std::string& revisionDirectory);
    Pose localize(const std::string& query) const;
    /** Renders the stitched panorama as seen by a localized camera; alpha marks real coverage. */
    bool render(const Pose& pose, int width, int height, const std::string& output) const;
private:
    struct Key { std::vector<cv::KeyPoint> points; cv::Mat descriptors; cv::Size size; double scale=1, focal=0; cv::Matx33d R; };
    std::vector<Key> keys;
    cv::Mat panorama;
};
}
