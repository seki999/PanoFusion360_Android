#include "panorama_engine.hpp"
#include "ray_adjuster.hpp"
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/stitching/detail/matchers.hpp>
#include <opencv2/stitching/detail/motion_estimators.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <fstream>
#include <numeric>
#include <queue>
#include <map>
#include <stdexcept>

namespace pano {
using namespace cv;
Mat read(const std::string& p) {
    Mat image=imread(p, IMREAD_COLOR);
    if(image.empty()) throw std::runtime_error("E_IMAGE: 图片损坏或无法读取");
    if(image.total()>2100000) throw std::runtime_error("E_SIZE: 工作帧超过内存预算");
    return image;
}
Verified match(const std::vector<KeyPoint>& a, const Mat& da, Size sa, const std::vector<KeyPoint>& b, const Mat& db, Size sb) {
    Verified out;
    if(da.rows<2 || db.rows<2)return out;
    BFMatcher matcher(NORM_HAMMING);
    std::vector<std::vector<DMatch>> ab,ba;
    matcher.knnMatch(da,db,ab,2); matcher.knnMatch(db,da,ba,2);
    std::vector<Point2f> pa,pb;
    for(const auto& nn:ab) {
        if(nn.size()<2 || nn[0].distance>=0.75f*nn[1].distance)continue;
        const auto& m=nn[0]; const auto& reverse=ba[m.trainIdx];
        if(reverse.size()<2 || reverse[0].trainIdx!=m.queryIdx || reverse[0].distance>=0.75f*reverse[1].distance)continue;
        out.matches.push_back(m);
        pa.push_back(a[m.queryIdx].pt-Point2f(sa.width/2.f,sa.height/2.f));
        pb.push_back(b[m.trainIdx].pt-Point2f(sb.width/2.f,sb.height/2.f));
    }
    if(pa.size()<12)return out;
    out.H=findHomography(pa,pb,RANSAC,3.0,out.inliers,2000,0.995);
    std::vector<double> distances;
    for(size_t i=0;i<out.inliers.size();i++) if(out.inliers[i]) {out.count++;distances.push_back(norm(pa[i]-pb[i]));}
    if(!distances.empty()) {auto mid=distances.begin()+distances.size()/2;std::nth_element(distances.begin(),mid,distances.end());out.displacement=*mid;}
    if(out.count<18 || out.count<out.matches.size()*0.4 || !checkRange(out.H))out.H.release();
    return out;
}
Analysis analyze(const std::string& p, const std::string& prev) {
    Mat im=read(p),gray,lap; cvtColor(im,gray,COLOR_BGR2GRAY); Laplacian(gray,lap,CV_64F);
    Scalar mean,sd; meanStdDev(lap,mean,sd);
    auto orb=ORB::create(3000); std::vector<KeyPoint> kp;Mat desc;orb->detectAndCompute(gray,noArray(),kp,desc);
    Mat low,high;compare(gray,5,low,CMP_LE);compare(gray,250,high,CMP_GE);
    Analysis a{sd[0]*sd[0],double(kp.size()),double(countNonZero(low|high))/gray.total(),0,0};
    if(!prev.empty()) {Mat old=read(prev),d; std::vector<KeyPoint> k;orb->detectAndCompute(old,noArray(),k,d);
        auto v=match(k,d,old.size(),kp,desc,im.size());
        if(!v.H.empty()) {a.overlap=double(v.count)/std::max(1,std::min(d.rows,desc.rows));a.displacement=v.displacement;}
    }
    return a;
}
static void project(const Mat& source,const detail::CameraParams& camera,int width,Mat& warped,Mat& mask,const Mat* qualitySource=nullptr,Mat* qualityOutput=nullptr) {
    int height=width/2;
    Mat mapx(height,width,CV_32F),mapy(height,width,CV_32F);
    Mat transform=camera.K()*camera.R.t();Matx33d t;
    // Explicit assignment avoids ownership/lifetime ambiguity of Matx wrappers.
    for(int r=0;r<3;r++)for(int c=0;c<3;c++)t(r,c)=transform.at<double>(r,c);
    for(int y=0;y<height;y++) {
        double pitch=CV_PI/2-(y+0.5)*CV_PI/height,cp=cos(pitch);
        float* mx=mapx.ptr<float>(y);float* my=mapy.ptr<float>(y);
        for(int x=0;x<width;x++) {
            double yaw=(x+0.5)*2*CV_PI/width-CV_PI;
            Vec3d q=t*Vec3d(sin(yaw)*cp,-sin(pitch),cos(yaw)*cp);
            mx[x]=q[2]>0?float(q[0]/q[2]):-1;my[x]=q[2]>0?float(q[1]/q[2]):-1;
        }
    }
    remap(source,warped,mapx,mapy,INTER_LINEAR,BORDER_CONSTANT);
    if(qualitySource && qualityOutput)remap(*qualitySource,*qualityOutput,mapx,mapy,INTER_LINEAR,BORDER_CONSTANT);
    Mat valid(source.size(),CV_8U,Scalar(255));
    // Erode source bounds to keep interpolation from introducing a black rim.
    rectangle(valid,Rect(0,0,source.cols,source.rows),Scalar(0),2);
    remap(valid,mask,mapx,mapy,INTER_NEAREST,BORDER_CONSTANT);
}
struct Estimated { std::vector<Mat> images; std::vector<detail::CameraParams> initial, cameras; double matchMs=0, adjustMs=0; AdjustResult adjust; };
// An evenly spread sample of each pair's RANSAC inliers constrains the rotation model as well as
// all of them, and keeps the bundle adjustment bounded on long sequences.
static constexpr int kAdjustInliersPerPair=60;
/** Features, verified pairwise matches, connectivity, rotation-camera estimate and bundle adjustment. */
static Estimated estimateCameras(const std::vector<std::string>& paths,const std::vector<int>& groups,Progress progress) {
    if(paths.size()<2)throw std::runtime_error("E_MATCH: 普通视频/照片至少需要两张重叠关键帧");
    if(paths.size()>24)throw std::runtime_error("E_MEMORY_BUDGET: 首版每次最多 24 张关键帧，请禁用重复帧或缩短视频");
    setRNGSeed(0);
    int n=int(paths.size());
    if(groups.size()!=paths.size())throw std::runtime_error("E_ARGUMENT: 帧分组数量不一致");
    Estimated out; out.images.resize(n);
    std::vector<Mat>& images=out.images; std::vector<detail::ImageFeatures> features(n);
    auto orb=ORB::create(4000);
    for(int i=0;i<n;i++) {
        progress("特征提取",i,n); images[i]=read(paths[i]);
        detail::computeImageFeatures(orb,images[i],features[i]);features[i].img_idx=i;
        if(features[i].keypoints.size()<60)throw std::runtime_error("E_FEATURES: 画面纹理不足，请拍摄有建筑或地面纹理的重叠区域");
    }
    std::vector<detail::MatchesInfo> pairwise(n*n);
    std::vector<std::vector<int>> graph(n);
    int64 matchStart=getTickCount();
    int done=0,total=n*(n-1)/2;
    for(int i=0;i<n;i++) for(int j=i+1;j<n;j++) {
        progress("双向匹配与 RANSAC",done++,total);
        auto v=match(features[i].keypoints,features[i].descriptors.getMat(ACCESS_READ),images[i].size(),features[j].keypoints,features[j].descriptors.getMat(ACCESS_READ),images[j].size());
        auto& a=pairwise[i*n+j]; a.src_img_idx=i;a.dst_img_idx=j;
        a.matches=v.matches;a.inliers_mask=v.inliers;a.num_inliers=v.count;a.H=v.H;
        a.confidence=v.H.empty()?0: v.count/(8.0+0.3*v.matches.size());
        if(a.confidence>1.0) {graph[i].push_back(j);graph[j].push_back(i);}
        auto& b=pairwise[j*n+i];b=a;b.src_img_idx=j;b.dst_img_idx=i;
        if(!v.H.empty())b.H=v.H.inv();
        for(auto& m:b.matches)std::swap(m.queryIdx,m.trainIdx);
    }
    std::vector<bool> seen(n);std::queue<int> queue;queue.push(0);seen[0]=true;
    while(!queue.empty()){int i=queue.front();queue.pop();for(int j:graph[i])if(!seen[j]){seen[j]=true;queue.push(j);}}
    if(std::count(seen.begin(),seen.end(),true)!=n)throw std::runtime_error("E_DISCONNECTED: 无法自动匹配当前画面。请先拍摄一部分已完成区域，再缓慢转向缺失区域。可禁用问题素材后重试。");
    out.matchMs=(getTickCount()-matchStart)*1000.0/getTickFrequency();
    progress("相机参数估计",0,1);
    std::vector<detail::CameraParams>& cameras=out.cameras;
    detail::HomographyBasedEstimator estimator;
    if(!estimator(features,pairwise,cameras))throw std::runtime_error("E_CAMERA: 焦距或旋转估计失败");
    for(auto& c:cameras)c.R.convertTo(c.R,CV_32F);
    out.initial=cameras;
    for(auto& c:out.initial)c.R=c.R.clone();
    // Intrinsics groups (one per source asset) share a focal length: every frame of a video has the same lens.
    std::vector<int> group(n); std::map<int,int> ids;
    for(int i=0;i<n;i++){auto it=ids.emplace(groups[i],int(ids.size())).first;group[i]=it->second;}
    std::vector<double> focals(ids.size());
    for(size_t g=0;g<focals.size();g++){std::vector<double> f;for(int i=0;i<n;i++)if(group[i]==int(g))f.push_back(cameras[i].focal);
        std::nth_element(f.begin(),f.begin()+f.size()/2,f.end());focals[g]=f[f.size()/2];}
    std::vector<Matx33d> rotations(n); std::vector<Point2d> centers(n);
    for(int i=0;i<n;i++){Mat R;cameras[i].R.convertTo(R,CV_64F);rotations[i]=Matx33d(R);centers[i]=Point2d(images[i].cols/2.0,images[i].rows/2.0);}
    std::vector<RayEdge> edges;
    for(int i=0;i<n;i++) for(int j=i+1;j<n;j++) {
        const auto& m=pairwise[i*n+j];
        if(m.confidence<=1.0)continue;
        std::vector<int> inliers; for(size_t k=0;k<m.inliers_mask.size();k++)if(m.inliers_mask[k])inliers.push_back(int(k));
        RayEdge edge; edge.i=i; edge.j=j;
        int take=std::min<int>(kAdjustInliersPerPair,int(inliers.size()));
        for(int k=0;k<take;k++){const DMatch& d=m.matches[inliers[size_t(k)*inliers.size()/take]];
            edge.a.push_back(features[i].keypoints[d.queryIdx].pt);edge.b.push_back(features[j].keypoints[d.trainIdx].pt);}
        edges.push_back(std::move(edge));
    }
    progress("相机全局优化",0,1);
    int64 adjustStart=getTickCount();
    out.adjust=adjustRays(edges,group,centers,focals,rotations);
    if(!out.adjust.ok)throw std::runtime_error("E_OPTIMIZE: 匹配几何不一致，可能存在视差或重复纹理，请从原位置重新拍摄或禁用问题素材");
    for(int i=0;i<n;i++){cameras[i].focal=focals[group[i]];Mat(rotations[i]).convertTo(cameras[i].R,CV_32F);}
    out.adjustMs=(getTickCount()-adjustStart)*1000.0/getTickFrequency();
    return out;
}
std::vector<double> diagnoseCameras(const std::vector<std::string>& paths,const std::vector<int>& groups) {
    auto e=estimateCameras(paths,groups,[](const std::string&,int,int){});
    std::vector<double> out;
    auto axis=[&](const detail::CameraParams& c,const Mat& anchor){Mat R=anchor*c.R;R.convertTo(R,CV_64F);
        double x=R.at<double>(0,2),y=R.at<double>(1,2),z=R.at<double>(2,2);
        out.push_back(c.focal);out.push_back(atan2(x,z)*180/CV_PI);out.push_back(-asin(std::max(-1.0,std::min(1.0,y)))*180/CV_PI);};
    Mat a0=e.initial[0].R.t(),a1=e.cameras[0].R.t();
    for(size_t i=0;i<e.cameras.size();i++){axis(e.initial[i],a0);axis(e.cameras[i],a1);}
    out.push_back(e.matchMs);out.push_back(e.adjustMs);out.push_back(e.adjust.iterations);out.push_back(e.adjust.rmsBefore);out.push_back(e.adjust.rmsAfter);out.push_back(e.adjust.edges);
    return out;
}
double stitch(const std::vector<std::string>& paths,const std::vector<int>& groups,const std::string& output,int width,const std::string& mode,int anchorIndex,double referenceYaw,Progress progress) {
    if(width!=2048 && width!=4096)throw std::runtime_error("E_SIZE: 当前原生渲染支持 2K/4K");
    auto estimated=estimateCameras(paths,groups,progress);
    int n=int(paths.size());
    std::vector<Mat>& images=estimated.images;
    std::vector<detail::CameraParams>& cameras=estimated.cameras;
    // The persisted anchor frame keeps the project's reference stable across sessions;
    // the user-selected reference yaw then rotates the whole sphere about the vertical axis.
    if(anchorIndex<0||anchorIndex>=n)anchorIndex=0;
    double a=-referenceYaw*CV_PI/180;
    Mat yaw=(Mat_<float>(3,3)<<float(cos(a)),0.f,float(sin(a)),0.f,1.f,0.f,float(-sin(a)),0.f,float(cos(a)));
    Mat anchor=yaw*cameras[anchorIndex].R.t();
    FileStorage saved(output+"/cameras.yml",FileStorage::WRITE);
    saved<<"version"<<2<<"anchor"<<anchorIndex<<"referenceYaw"<<referenceYaw<<"cameras"<<"[";
    for(int i=0;i<n;i++) {
        auto& c=cameras[i];c.R=anchor*c.R;c.R.convertTo(c.R,CV_64F);
        if(!checkRange(c.R) || !std::isfinite(c.focal) || c.focal<images[i].cols*0.25 || c.focal>images[i].cols*5)throw std::runtime_error("E_GEOMETRY: 相机参数不稳定，拒绝融合");
        saved<<"{"<<"path"<<paths[i]<<"focal"<<c.focal<<"ppx"<<c.ppx<<"ppy"<<c.ppy<<"R"<<c.R<<"}";
    }
    saved<<"]";saved.release();
    std::vector<UMat> small(n),seams(n),originalMasks(n);std::vector<Point> corners(n,Point(0,0));
    for(int i=0;i<n;i++) {
        progress("球面预投影",i,n);
        Mat w,m; project(images[i],cameras[i],512,w,m);w.copyTo(small[i]);m.copyTo(seams[i]);m.copyTo(originalMasks[i]);
    }
    progress("曝光补偿",0,1);
    auto exposure=detail::ExposureCompensator::createDefault(detail::ExposureCompensator::GAIN);
    exposure->feed(corners,small,originalMasks);
    for(int i=0;i<n;i++){exposure->apply(i,Point(0,0),small[i],originalMasks[i]);small[i].convertTo(small[i],CV_32F);}
    progress("接缝搜索",0,1);
    detail::GraphCutSeamFinder seamFinder("COST_COLOR_GRAD");seamFinder.find(small,corners,seams);
    detail::MultiBandBlender blender(false,5);blender.prepare(Rect(0,0,width,width/2));
    Mat coverage(width/2,width,CV_8U,Scalar(0)),quality(width/2,width,CV_8U,Scalar(0));
    for(int i=0;i<n;i++) {
        progress("球面投影与多频带融合",i,n);
        Mat gray,lap,energy,localQuality,warpedQuality;
        cvtColor(images[i],gray,COLOR_BGR2GRAY);Laplacian(gray,lap,CV_32F);multiply(lap,lap,energy);blur(energy,energy,Size(31,31));sqrt(energy,energy);energy.convertTo(localQuality,CV_8U,8);
        localQuality.setTo(0,(gray<12)|(gray>243));
        Mat w,m;project(images[i],cameras[i],width,w,m,&localQuality,&warpedQuality);bitwise_or(coverage,m,coverage);
        warpedQuality.setTo(0,m==0);max(quality,warpedQuality,quality);
        exposure->apply(i,Point(0,0),w,m);
        Mat seam,dilated;dilate(seams[i].getMat(ACCESS_READ),dilated,Mat());resize(dilated,seam,m.size(),0,0,INTER_NEAREST);bitwise_and(seam,m,seam);
        Mat signedImage;w.convertTo(signedImage,CV_16S);blender.feed(signedImage,seam,Point(0,0));images[i].release();
    }
    Mat observed=coverage.clone();
    progress("输出融合结果",0,1);
    Mat blended,blendMask;blender.blend(blended,blendMask);Mat color;blended.convertTo(color,CV_8U);
    // Only actual blended and projected source pixels count as covered.
    bitwise_and(coverage,blendMask,coverage);
    if(mode=="180") { coverage.colRange(0,width/4).setTo(0);coverage.colRange(width*3/4,width).setTo(0); }
    std::vector<Mat> channels;split(color,channels);channels.push_back(coverage);Mat rgba;merge(channels,rgba);
    double numerator=0,denominator=0;
    for(int y=0;y<coverage.rows;y++) {
        double area=sin(CV_PI/2-y*CV_PI/coverage.rows)-sin(CV_PI/2-(y+1)*CV_PI/coverage.rows);
        for(int x=0;x<width;x++) {
            if(mode=="180"&&(x<width/4||x>=width*3/4))continue;
            denominator+=area;if(coverage.at<uchar>(y,x))numerator+=area;
        }
    }
    quality.setTo(0,coverage==0);
    Mat coveragePreview(coverage.size(),CV_8UC3,Scalar(55,55,55));
    coveragePreview.setTo(Scalar(75,175,65),(coverage>0)&(quality>=50));
    coveragePreview.setTo(Scalar(30,190,235),(coverage>0)&(quality<50));
    if(!imwrite(output+"/quality.png",quality)||!imwrite(output+"/observed.png",observed)||!imwrite(output+"/coverage-preview.png",coveragePreview))throw std::runtime_error("E_WRITE: 质量或覆盖数据保存失败");
    progress("保存图片和覆盖 Mask",0,1);
    if(!imwrite(output+"/panorama.png",rgba)||!imwrite(output+"/coverage.png",coverage))throw std::runtime_error("E_WRITE: 结果保存失败，请检查空间");
    progress("完成",1,1);
    return mode=="自定义"?-1:100*numerator/denominator;
}
}
