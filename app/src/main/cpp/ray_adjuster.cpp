#include "ray_adjuster.hpp"
#include <opencv2/calib3d.hpp>
#include <algorithm>
#include <numeric>

namespace pano {
using namespace cv;
namespace {
/**
 * Bundle adjustment for a camera rotating about its centre, minimising the symmetric pixel transfer
 * error of x_j ~ K_j R_jᵀ R_i K_i⁻¹ x_i.
 *
 * OpenCV's BundleAdjusterRay compares unit rays. That residual is degenerate: as the focal grows every
 * ray tends to the optical axis and identical rotations reach zero error, so OpenCV scales it by
 * sqrt(f_i f_j), which instead biases long sequences toward too short a focal (an over-wide panorama).
 * Pixel transfer has no such solution. In addition one focal is shared per intrinsics group (all frames
 * of a video come from one lens) and camera 0's rotation is fixed to remove the global gauge freedom.
 */
class RayCallback: public LMSolver::Callback {
public:
    RayCallback(const std::vector<RayEdge>& edges,const std::vector<int>& group,int groups,const std::vector<Point2d>& centers,const Matx33d& fixed)
        : edges(edges),group(group),groups(groups),centers(centers),fixed(fixed) {
        int n=int(group.size());
        byCamera.resize(n); byGroup.resize(groups);
        int row=0;
        for(size_t e=0;e<edges.size();e++) {
            offsets.push_back(row); row+=4*int(edges[e].a.size());
            byCamera[edges[e].i].push_back(int(e)); byCamera[edges[e].j].push_back(int(e));
            byGroup[group[edges[e].i]].push_back(int(e));
            if(group[edges[e].j]!=group[edges[e].i])byGroup[group[edges[e].j]].push_back(int(e));
        }
        rows=row;
    }
    int rowCount() const { return rows; }
    Matx33d rotation(const double* p,int camera) const {
        if(camera==0)return fixed;
        Matx31d r(p[groups+3*(camera-1)],p[groups+3*(camera-1)+1],p[groups+3*(camera-1)+2]);
        Matx33d R; Rodrigues(r,R); return R;
    }
    /** Transfer errors of edge [e]; [weighted] applies the per-match IRLS weights. */
    void residual(const double* p,int e,double* out,bool weighted=true) const {
        const RayEdge& edge=edges[e];
        Matx33d Ri=rotation(p,edge.i),Rj=rotation(p,edge.j);
        Matx33d ij=Rj.t()*Ri,ji=ij.t();
        double fi=p[group[edge.i]],fj=p[group[edge.j]];
        // Points that rotate behind the other camera get a large but finite penalty.
        auto transfer=[](const Matx33d& R,double fFrom,double fTo,double x,double y,double& u,double& v){
            Vec3d q=R*Vec3d(x/fFrom,y/fFrom,1); double z=std::max(q[2],1e-3); u=fTo*q[0]/z; v=fTo*q[1]/z; };
        for(size_t k=0;k<edge.a.size();k++) {
            double ax=edge.a[k].x-centers[edge.i].x,ay=edge.a[k].y-centers[edge.i].y;
            double bx=edge.b[k].x-centers[edge.j].x,by=edge.b[k].y-centers[edge.j].y,u,v;
            transfer(ij,fi,fj,ax,ay,u,v); out[4*k]=u-bx; out[4*k+1]=v-by;
            transfer(ji,fj,fi,bx,by,u,v); out[4*k+2]=u-ax; out[4*k+3]=v-ay;
            if(weighted&&!edge.w.empty())for(int c=0;c<4;c++)out[4*k+c]*=edge.w[k];
        }
    }
    bool compute(InputArray param,OutputArray err,OutputArray J) const override {
        Mat p=param.getMat().clone(); p=p.reshape(1,int(p.total()));
        const double* x=p.ptr<double>();
        err.create(rows,1,CV_64F); Mat e=err.getMat();
        for(size_t k=0;k<edges.size();k++)residual(x,int(k),e.ptr<double>()+offsets[k]);
        if(J.needed()) {
            int cols=int(p.total());
            J.create(rows,cols,CV_64F); Mat jac=J.getMat(); jac.setTo(0);
            std::vector<double> plus,minus;
            for(int c=0;c<cols;c++) {
                // Only edges touching this parameter change: a sparse central difference.
                const std::vector<int>& affected=c<groups?byGroup[c]:byCamera[1+(c-groups)/3];
                double v=p.at<double>(c),h=c<groups?std::max(1e-3,1e-4*v):1e-6;
                for(int k:affected) {
                    int n=4*int(edges[k].a.size()); plus.resize(n); minus.resize(n);
                    p.at<double>(c)=v+h; residual(p.ptr<double>(),k,plus.data());
                    p.at<double>(c)=v-h; residual(p.ptr<double>(),k,minus.data());
                    p.at<double>(c)=v;
                    for(int r=0;r<n;r++)jac.at<double>(offsets[k]+r,c)=(plus[r]-minus[r])/(2*h);
                }
            }
        }
        return true;
    }
private:
    const std::vector<RayEdge>& edges; const std::vector<int>& group; int groups;
    const std::vector<Point2d>& centers; Matx33d fixed;
    std::vector<int> offsets; std::vector<std::vector<int>> byCamera,byGroup; int rows=0;
};
}

namespace {
Mat pack(const std::vector<double>& focals,const std::vector<Matx33d>& rotations) {
    int groups=int(focals.size()),n=int(rotations.size());
    Mat p(groups+3*(n-1),1,CV_64F);
    for(int g=0;g<groups;g++)p.at<double>(g)=focals[g];
    for(int c=1;c<n;c++){Matx31d r;Rodrigues(rotations[c],r);for(int k=0;k<3;k++)p.at<double>(groups+3*(c-1)+k)=r(k);}
    return p;
}
/** Per-match unweighted error: the larger of the two transfer distances, in pixels. */
std::vector<std::vector<double>> errors(const std::vector<RayEdge>& edges,const RayCallback& callback,const Mat& p) {
    std::vector<std::vector<double>> out(edges.size()); std::vector<double> r;
    for(size_t e=0;e<edges.size();e++) {
        r.resize(4*edges[e].a.size()); callback.residual(p.ptr<double>(),int(e),r.data(),false);
        for(size_t k=0;k<edges[e].a.size();k++)out[e].push_back(std::max(std::hypot(r[4*k],r[4*k+1]),std::hypot(r[4*k+2],r[4*k+3])));
    }
    return out;
}
double median(std::vector<double> v) { if(v.empty())return 0; std::nth_element(v.begin(),v.begin()+v.size()/2,v.end()); return v[v.size()/2]; }
double rms(const RayCallback& callback,const Mat& p) { Mat e; callback.compute(p,e,noArray()); return std::sqrt(e.dot(e)/std::max(1,e.rows/2)); }
}

AdjustResult adjustRays(const std::vector<RayEdge>& edges,const std::vector<int>& group,const std::vector<Point2d>& centers,
                        std::vector<double>& focals,std::vector<Matx33d>& rotations,int maxIterations) {
    int n=int(group.size()),groups=int(focals.size());
    CV_Assert(n>=2 && int(rotations.size())==n && int(centers.size())==n && !edges.empty());
    AdjustResult result;
    // The homography-based start builds R = K⁻¹H⁻¹K, which is only approximately orthonormal for real
    // photos; project every start rotation (including the fixed one) onto SO(3).
    for(auto& R:rotations){SVD svd(Mat(R),SVD::FULL_UV);Mat r=svd.u*svd.vt;if(determinant(r)<0)r=-r;R=Matx33d(r);}
    Mat p=pack(focals,rotations);
    std::vector<RayEdge> active=edges;
    for(auto& e:active)e.w.assign(e.a.size(),1.0);
    auto flat=[](const std::vector<std::vector<double>>& v){std::vector<double> f;for(const auto& x:v)f.insert(f.end(),x.begin(),x.end());return f;};
    {
        RayCallback start(active,group,groups,centers,rotations[0]);
        result.rmsBefore=rms(start,p);
    }
    // RANSAC accepts some pairs that do not truly overlap (repetitive texture). Iteratively reweighted
    // least squares with a Cauchy weight relative to the median error removes their influence without
    // assuming the start (in particular its focal length) is already accurate.
    for(int round=0;round<4;round++) {
        RayCallback probe(active,group,groups,centers,rotations[0]);
        auto err=errors(active,probe,p);
        double delta=std::max(0.5,2.0*median(flat(err)));
        for(size_t e=0;e<active.size();e++)for(size_t k=0;k<err[e].size();k++)active[e].w[k]=1.0/std::sqrt(1+std::pow(err[e][k]/delta,2));
        Ptr<RayCallback> callback=makePtr<RayCallback>(active,group,groups,centers,rotations[0]);
        result.iterations+=LMSolver::create(callback,maxIterations)->run(p);
    }
    // Finally drop clear outliers (3 robust sigma) and refine unweighted, unless a camera would lose all matches.
    {
        RayCallback probe(active,group,groups,centers,rotations[0]);
        auto err=errors(active,probe,p);
        double limit=std::max(2.0,3*1.4826*median(flat(err)));
        std::vector<RayEdge> kept; std::vector<int> degree(n,0);
        for(size_t e=0;e<active.size();e++) {
            RayEdge edge; edge.i=active[e].i; edge.j=active[e].j;
            for(size_t k=0;k<err[e].size();k++)if(err[e][k]<limit){edge.a.push_back(active[e].a[k]);edge.b.push_back(active[e].b[k]);}
            if(edge.a.size()>=8){degree[edge.i]++;degree[edge.j]++;kept.push_back(std::move(edge));}
        }
        if(!kept.empty()&&std::count(degree.begin(),degree.end(),0)==0) {
            active=std::move(kept);
            Ptr<RayCallback> callback=makePtr<RayCallback>(active,group,groups,centers,rotations[0]);
            result.iterations+=LMSolver::create(callback,maxIterations)->run(p);
        }
        for(auto& e:active)e.w.clear();
        RayCallback solvedErr(active,group,groups,centers,rotations[0]);
        result.rmsAfter=rms(solvedErr,p);
    }
    result.edges=int(active.size());
    for(int g=0;g<groups;g++)focals[g]=p.at<double>(g);
    RayCallback solved(active,group,groups,centers,rotations[0]);
    for(int c=1;c<n;c++)rotations[c]=solved.rotation(p.ptr<double>(),c);
    result.ok=checkRange(p) && std::all_of(focals.begin(),focals.end(),[](double f){return std::isfinite(f)&&f>0;});
    return result;
}
}
