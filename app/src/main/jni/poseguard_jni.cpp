// PoseGuard ncnn JNI 桥
// 只暴露三件事：加载模型、跑一帧、释放。
// 输入由 Java 侧做好 letterbox 成 640x640 的 ARGB_8888 Bitmap，
// 输出按 YOLOv8-pose 的 [56, 8400] 直接拷成 float 数组交给 Kotlin 后处理。

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vector>
#include <algorithm>
#include <string>

#include "net.h"

#define LOG_TAG "PoseGuardJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ncnn::Net *g_net = nullptr;

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poseguard_monitor_PoseDetector_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jobject assetManager,
        jstring paramName, jstring binName) {

    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("AAssetManager 为空");
        return -1;
    }

    std::string param = jstr(env, paramName);
    std::string bin = jstr(env, binName);

    // 先确认文件真的在包里，避免 load_param 静默失败
    AAsset *probe = AAssetManager_open(mgr, param.c_str(), AASSET_MODE_UNKNOWN);
    if (!probe) {
        LOGE("param 不存在: %s", param.c_str());
        return -2;
    }
    AAsset_close(probe);
    probe = AAssetManager_open(mgr, bin.c_str(), AASSET_MODE_UNKNOWN);
    if (!probe) {
        LOGE("bin 不存在: %s", bin.c_str());
        return -3;
    }
    AAsset_close(probe);

    ncnn::Net *net = new ncnn::Net();
    net->opt.num_threads = 4;
    net->opt.use_vulkan_compute = false;
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = true;

    int ret = net->load_param(mgr, param.c_str());
    if (ret != 0) {
        LOGE("load_param 失败: %d (%s)", ret, param.c_str());
        delete net;
        return -4;
    }

    ret = net->load_model(mgr, bin.c_str());
    if (ret != 0) {
        LOGE("load_model 失败: %d (%s)", ret, bin.c_str());
        delete net;
        return -5;
    }

    g_net = net;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poseguard_monitor_PoseDetector_nativeDetect(
        JNIEnv *env, jobject /*thiz*/, jobject bitmap, jfloatArray outArr,
        jstring inputName, jstring outputName) {

    if (!g_net) return -1;
    if (!bitmap || !outArr) return -2;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -3;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return -4;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -5;
    }

    const int w = (int) info.width;
    const int h = (int) info.height;

    // RGBA -> 模型要的 RGB，0~255，NCHW 由 from_pixels 处理
    ncnn::Mat in = ncnn::Mat::from_pixels((const unsigned char *) pixels,
                                          ncnn::Mat::PIXEL_RGBA2RGB, w, h);
    AndroidBitmap_unlockPixels(env, bitmap);

    if (in.empty()) return -6;

    // YOLOv8 导出后已自带 /255 的 Div 层，这里只做通道归一化不做均值
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in.substract_mean_normalize(nullptr, norm);

    std::string inName = jstr(env, inputName);
    std::string outName = jstr(env, outputName);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.set_light_mode(true);

    int ret = ex.input(inName.c_str(), in);
    if (ret != 0) {
        LOGE("input 失败，名字可能不对: %s (ret=%d)", inName.c_str(), ret);
        return -10;
    }

    ncnn::Mat out;
    ret = ex.extract(outName.c_str(), out);
    if (ret != 0) {
        LOGE("extract 失败，输出名可能不对: %s (ret=%d)", outName.c_str(), ret);
        return -11;
    }

    const int outSize = (int) out.total();
    const int arrLen = (int) env->GetArrayLength(outArr);
    if (arrLen < outSize) {
        LOGE("输出数组太小: %d < %d", arrLen, outSize);
        return -12;
    }

    // [56, 8400] 通道优先布局，直接顺序拷贝，PosePost 按通道优先读
    std::vector<float> buf(outSize);
    if (out.elempack == 1) {
        for (int i = 0; i < outSize; i++) buf[i] = ((const float *) out.data)[i];
    } else {
        ncnn::Mat outFlat;
        ncnn::convert_packing(out, outFlat, 1);
        const int n = std::min(outSize, (int) outFlat.total());
        for (int i = 0; i < n; i++) buf[i] = ((const float *) outFlat.data)[i];
    }

    env->SetFloatArrayRegion(outArr, 0, outSize, buf.data());
    return outSize;
}

extern "C" JNIEXPORT void JNICALL
Java_com_poseguard_monitor_PoseDetector_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
}
