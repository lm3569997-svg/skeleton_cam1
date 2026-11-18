// PoseGuard YOLO11-pose ncnn JNI 桥
//
// 输入：Java 侧已经 letterbox 成 640x640 的 ARGB_8888 Bitmap
// 输出：out0 的形状是 [56, 8400]，按通道优先顺序拷成 float 数组
//
// 关键点：参数用 in0 / out0，与 pnnx 从 ONNX 导出后的层名一致。

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <vector>

#include "net.h"

#define LOG_TAG "PoseGuardJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static ncnn::Net *g_net = nullptr;

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poseguard_lite_PoseEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jobject assetManager,
        jstring paramName, jstring binName) {

    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("AAssetManager 为 null");
        return -1;
    }

    std::string param = jstr(env, paramName);
    std::string bin = jstr(env, binName);

    AAsset *probe = AAssetManager_open(mgr, param.c_str(), AASSET_MODE_UNKNOWN);
    if (!probe) {
        LOGE("找不到 param: %s", param.c_str());
        return -2;
    }
    AAsset_close(probe);
    probe = AAssetManager_open(mgr, bin.c_str(), AASSET_MODE_UNKNOWN);
    if (!probe) {
        LOGE("找不到 bin: %s", bin.c_str());
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
        LOGE("load_param 失败: %d", ret);
        delete net;
        return -4;
    }

    ret = net->load_model(mgr, bin.c_str());
    if (ret != 0) {
        LOGE("load_model 失败: %d", ret);
        delete net;
        return -5;
    }

    g_net = net;
    LOGI("模型加载完成: %s", param.c_str());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poseguard_lite_PoseEngine_nativeDetect(
        JNIEnv *env, jobject /*thiz*/, jobject bitmap, jfloatArray outArr,
        jstring inputName, jstring outputName) {

    if (!g_net) return -1;
    if (!bitmap || !outArr) return -2;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -3;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap 格式不是 RGBA_8888: %d", info.format);
        return -4;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -5;
    }

    ncnn::Mat in = ncnn::Mat::from_pixels(
            reinterpret_cast<const unsigned char *>(pixels),
            ncnn::Mat::PIXEL_RGBA2RGB,
            static_cast<int>(info.width),
            static_cast<int>(info.height));

    AndroidBitmap_unlockPixels(env, bitmap);

    if (in.empty()) return -6;

    // YOLO11 导出的 ncnn 图里已经带 /255 的 Div 层，这里不再重复归一化
    std::string inName = jstr(env, inputName);
    std::string outName = jstr(env, outputName);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.set_light_mode(true);

    int ret = ex.input(inName.c_str(), in);
    if (ret != 0) {
        LOGE("input(%s) 失败: %d", inName.c_str(), ret);
        return -10;
    }

    ncnn::Mat out;
    ret = ex.extract(outName.c_str(), out);
    if (ret != 0) {
        LOGE("extract(%s) 失败: %d", outName.c_str(), ret);
        return -11;
    }

    ncnn::Mat flat;
    if (out.elempack != 1) {
        ncnn::convert_packing(out, flat, 1);
    } else {
        flat = out;
    }

    const int total = static_cast<int>(flat.total());
    const int arrLen = static_cast<int>(env->GetArrayLength(outArr));
    if (arrLen < total) {
        LOGE("输出数组太小: %d < %d", arrLen, total);
        return -12;
    }

    env->SetFloatArrayRegion(outArr, 0, total,
                             reinterpret_cast<const jfloat *>(flat.data));
    return total;
}

extern "C" JNIEXPORT void JNICALL
Java_com_poseguard_lite_PoseEngine_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
}
