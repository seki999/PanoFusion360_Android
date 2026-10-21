#include "panorama_engine.hpp"
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <stdexcept>

namespace pano {
using namespace cv;
static constexpr int kWorkingSide=800;
static Mat shrink(const Mat& image,double& scale) {
    scale=std::min(1.0,double(kWorkingSide)/std::max(image.cols,image.rows));
    Mat out; if(scale<1)resize(image,out,Size(),scale,scale,INTER_AREA); else out=image;
    return out;
}
Localizer::Localizer(const std::vector<std::string>& keyframes,const std::string& directory) {
    FileStorage stored(directory+"/cameras.yml",FileStorage::READ);
    if(!stored.isOpened())throw std::runtime_error("E_LOCALIZE: 此版本没有相机参数，无法视觉定位");
    FileNode cameras=stored["cameras"];
    if(cameras.size()!=keyframes.size())throw std::runtime_error("E_LOCALIZE: 关键帧与相机参数数量不一致");
    auto orb=ORB::create(2000);
    int i=0;
    for(const auto& node:cameras) {
        Key key; Mat R; node["R"]>>R; node["focal"]>>key.focal;
        if(R.rows!=3||R.cols!=3)throw std::runtime_error("E_LOCALIZE: 相机参数损坏");
        R.convertTo(R,CV_64F); key.R=Matx33d(R);
        Mat image=read(keyframes[i++]),gray; cvtColor(shrink(image,key.scale),gray,COLOR_BGR2GRAY);
        key.size=gray.size(); orb->detectAndCompute(gray,noArray(),key.points,key.descriptors);
        keys.push_back(std::move(key));
    }
    panorama=imread(directory+"/panorama.png",IMREAD_UNCHANGED);
    if(panorama.empty()||panorama.channels()!=4)throw std::runtime_error("E_LOCALIZE: 全景结果无法读取");
}
Pose Localizer::localize(const std::string& query) const {
    Pose best; double scale; Mat gray;
    cvtColor(shrink(read(query),scale),gray,COLOR_BGR2GRAY);
    std::vector<KeyPoint> points; Mat descriptors;
    ORB::create(2000)->detectAndCompute(gray,noArray(),points,descriptors);
    if(points.size()<60)return best;
    for(size_t k=0;k<keys.size();k++) {
        const Key& key=keys[k];
        Verified v=match(points,descriptors,gray.size(),key.points,key.descriptors,key.size);
        if(v.H.empty()||v.count<25||v.count<=best.inliers)continue;
        // H maps centred query pixels to centred keyframe pixels. For a camera rotating about
        // its centre H ~ K_key * R_key^T * R_query * K_query^-1, so the query focal and rotation
        // follow from the column norms and an SVD projection onto SO(3).
        Matx33d H(v.H);
        double toKey=1.0/key.scale, keyFocal=key.focal;
        Matx33d M=Matx33d(1/keyFocal,0,0, 0,1/keyFocal,0, 0,0,1)*Matx33d(toKey,0,0, 0,toKey,0, 0,0,1)*H;
        if(determinant(M)<0)M*=-1;
        Vec3d c0(M(0,0),M(1,0),M(2,0)),c1(M(0,1),M(1,1),M(2,1)),c2(M(0,2),M(1,2),M(2,2));
        double focal=norm(c2)/((norm(c0)+norm(c1))/2);
        if(!std::isfinite(focal)||focal<0.3*gray.cols||focal>4.0*gray.cols)continue;
        Matx33d raw=M*Matx33d(focal,0,0, 0,focal,0, 0,0,1);
        raw*=1.0/std::cbrt(determinant(raw));
        SVD svd(Mat(raw),SVD::FULL_UV); Mat rotation=svd.u*svd.vt;
        if(determinant(rotation)<0)continue;
        Matx33d relative(rotation);
        double error=norm(raw-relative);
        // Large residuals mean the homography is not a pure rotation (translation, parallax or a wrong match).
        if(error>0.25)continue;
        Matx33d R=key.R*relative;
        Vec3d axis=R*Vec3d(0,0,1),right=R*Vec3d(1,0,0);
        best.ok=true; best.inliers=v.count; best.keyframe=int(k); best.focal=focal; best.rotationError=error; best.R=R;
        best.yaw=atan2(axis[0],axis[2])*180/CV_PI; best.pitch=-asin(std::max(-1.0,std::min(1.0,axis[1])))*180/CV_PI;
        best.roll=atan2(-right[1],std::hypot(right[0],right[2]))*180/CV_PI;
    }
    best.frameSide=std::max(gray.cols,gray.rows);
    return best;
}
bool Localizer::render(const Pose& pose,int width,int height,const std::string& output) const {
    if(!pose.ok||width<=0||height<=0)return false;
    // pose.focal is measured in the shrunken query frame; the render keeps the query aspect ratio.
    double focal=pose.focal*std::max(width,height)/double(pose.frameSide);
    Mat mapx(height,width,CV_32F),mapy(height,width,CV_32F);
    double W=panorama.cols,Hh=panorama.rows;
    for(int y=0;y<height;y++) {
        float* mx=mapx.ptr<float>(y); float* my=mapy.ptr<float>(y);
        for(int x=0;x<width;x++) {
            Vec3d d=pose.R*Vec3d(x+0.5-width/2.0,y+0.5-height/2.0,focal);
            double yaw=atan2(d[0],d[2]),pitch=-atan2(d[1],std::hypot(d[0],d[2]));
            mx[x]=float((yaw+CV_PI)/(2*CV_PI)*W-0.5); my[x]=float((CV_PI/2-pitch)/CV_PI*Hh-0.5);
        }
    }
    Mat view; remap(panorama,view,mapx,mapy,INTER_LINEAR,BORDER_WRAP);
    return imwrite(output,view);
}
}
