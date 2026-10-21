#include "panorama_engine.hpp"
#include <jni.h>
#include <stdexcept>
static std::string str(JNIEnv* e,jstring s){if(!s)return {};const char* p=e->GetStringUTFChars(s,nullptr);if(!p)throw std::bad_alloc();std::string v(p);e->ReleaseStringUTFChars(s,p);return v;}
static void fail(JNIEnv* e,const char* text){ if(!e->ExceptionCheck()) e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),text); }
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_panofusion_app_NativePanorama_analyze(JNIEnv* e,jobject,jstring path,jstring prev) {
    try {auto a=pano::analyze(str(e,path),str(e,prev));double values[]={a.sharpness,a.features,a.clipped,a.overlap,a.displacement};auto out=e->NewDoubleArray(5);e->SetDoubleArrayRegion(out,0,5,values);return out;}
    catch(const std::exception& ex){fail(e,ex.what());return nullptr;}
}
extern "C" JNIEXPORT jdouble JNICALL Java_com_panofusion_app_NativePanorama_stitch(JNIEnv* e,jobject,jobjectArray files,jstring output,jint width,jstring mode,jobject callback) {
    try {
        std::vector<std::string> paths;
        for(int i=0;i<e->GetArrayLength(files);i++){auto p=(jstring)e->GetObjectArrayElement(files,i);paths.push_back(str(e,p));e->DeleteLocalRef(p);}
        auto type=e->GetObjectClass(callback);auto stage=e->GetMethodID(type,"stage","(Ljava/lang/String;II)V");auto cancelled=e->GetMethodID(type,"cancelled","()Z");
        return pano::stitch(paths,str(e,output),width,str(e,mode),[&](const std::string& name,int done,int total){
            if(e->CallBooleanMethod(callback,cancelled))throw std::runtime_error("E_CANCELLED: 用户已取消");
            auto text=e->NewStringUTF(name.c_str());e->CallVoidMethod(callback,stage,text,done,total);e->DeleteLocalRef(text);
            if(e->ExceptionCheck())throw std::runtime_error("E_CALLBACK: 处理已中断");
        });
    } catch(const std::bad_alloc&){fail(e,"E_MEMORY: 内存不足，请减少素材或输出尺寸");}
    catch(const std::exception& ex){fail(e,ex.what());}
    return -1;
}
