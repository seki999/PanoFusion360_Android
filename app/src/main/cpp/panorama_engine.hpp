#pragma once
#include <opencv2/core.hpp>
#include <functional>
#include <string>
#include <vector>
namespace pano {
using Progress = std::function<void(const std::string&,int,int)>;
struct Analysis { double sharpness, features, clipped, overlap, displacement; };
Analysis analyze(const std::string&, const std::string&);
double stitch(const std::vector<std::string>&, const std::string&, int, const std::string&, Progress);
}
