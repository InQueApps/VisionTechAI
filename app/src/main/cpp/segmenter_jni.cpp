/**
 * Native image segmentation (JNI) - no ML.
 * Uses K-means color clustering to produce a segmented overlay.
 * To use OpenCV instead: link OpenCV in CMake, replace segmentFrame() with
 * cv::Mat, cv::cvtColor, cv::kmeans or cv::grabCut, then fill output RGBA.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <vector>
#include <algorithm>

#define LOG_TAG "SegmenterJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static const int MAX_K = 10;
static const uint8_t ALPHA_OVERLAY = 200;

// Distinct colors for up to 10 clusters (R,G,B)
static const uint8_t CLUSTER_COLORS[MAX_K][3] = {
    {64, 64, 64}, {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0},
    {255, 0, 255}, {0, 255, 255}, {255, 128, 0}, {128, 0, 255}, {0, 255, 128},
};

static void initCentersFromGrid(const float* r, const float* g, const float* b,
                                int width, int height, int K,
                                float* centerR, float* centerG, float* centerB) {
    int cols = (K >= 4) ? 2 : 1;
    int rows = (K + cols - 1) / cols;
    if (rows <= 0) rows = 1;
    if (cols <= 0) cols = 1;
    for (int k = 0; k < K; k++) {
        int c = k % cols;
        int row = k / cols;
        int x = (width * (c + 1)) / (cols + 1);
        int y = (height * (row + 1)) / (rows + 1);
        int idx = y * width + x;
        int n = width * height;
        idx = std::min(idx, n - 1);
        centerR[k] = r[idx];
        centerG[k] = g[idx];
        centerB[k] = b[idx];
    }
}

// K-means on RGB; K and iterations passed from app (user-adjustable).
static void segmentFrame(const int32_t* pixelsIn, int32_t* pixelsOut,
                         int width, int height, int K, int iterations) {
    const int n = width * height;
    if (n <= 0 || K < 1 || K > MAX_K) return;
    K = std::min(K, MAX_K);
    iterations = std::max(1, std::min(iterations, 20));

    std::vector<float> r(n), g(n), b(n);
    for (int i = 0; i < n; i++) {
        uint32_t argb = static_cast<uint32_t>(pixelsIn[i]);
        b[i] = (argb >> 0) & 0xFF;
        g[i] = (argb >> 8) & 0xFF;
        r[i] = (argb >> 16) & 0xFF;
    }

    std::vector<float> centerR(K), centerG(K), centerB(K);
    initCentersFromGrid(r.data(), g.data(), b.data(), width, height, K,
                        centerR.data(), centerG.data(), centerB.data());

    std::vector<int> assign(n, 0);
    std::vector<float> sumR(K), sumG(K), sumB(K);
    std::vector<int> count(K);

    for (int iter = 0; iter < iterations; iter++) {
        std::fill(sumR.begin(), sumR.end(), 0.f);
        std::fill(sumG.begin(), sumG.end(), 0.f);
        std::fill(sumB.begin(), sumB.end(), 0.f);
        std::fill(count.begin(), count.end(), 0);

        for (int i = 0; i < n; i++) {
            float best = 1e9f;
            int bestK = 0;
            for (int k = 0; k < K; k++) {
                float dr = r[i] - centerR[k];
                float dg = g[i] - centerG[k];
                float db = b[i] - centerB[k];
                float d = dr*dr + dg*dg + db*db;
                if (d < best) { best = d; bestK = k; }
            }
            assign[i] = bestK;
            sumR[bestK] += r[i];
            sumG[bestK] += g[i];
            sumB[bestK] += b[i];
            count[bestK]++;
        }

        for (int k = 0; k < K; k++) {
            if (count[k] > 0) {
                centerR[k] = sumR[k] / count[k];
                centerG[k] = sumG[k] / count[k];
                centerB[k] = sumB[k] / count[k];
            }
        }
        if (iter < iterations - 1) {
            for (int k = 0; k < K; k++) {
                if (count[k] == 0) {
                    float bestDist = -1.f;
                    int bestIdx = 0;
                    for (int i = 0; i < n; i++) {
                        float dr = r[i] - centerR[assign[i]];
                        float dg = g[i] - centerG[assign[i]];
                        float db = b[i] - centerB[assign[i]];
                        float d = dr*dr + dg*dg + db*db;
                        if (d > bestDist) { bestDist = d; bestIdx = i; }
                    }
                    centerR[k] = r[bestIdx];
                    centerG[k] = g[bestIdx];
                    centerB[k] = b[bestIdx];
                }
            }
        }
    }

    for (int i = 0; i < n; i++) {
        int k = assign[i];
        uint8_t rr = CLUSTER_COLORS[k][0];
        uint8_t gg = CLUSTER_COLORS[k][1];
        uint8_t bb = CLUSTER_COLORS[k][2];
        pixelsOut[i] = (static_cast<uint32_t>(ALPHA_OVERLAY) << 24)
                     | (static_cast<uint32_t>(rr) << 16)
                     | (static_cast<uint32_t>(gg) << 8)
                     | bb;
    }
}

extern "C" {

static bool s_logged_once = false;

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_ImageSegmentationProcessor_nativeSegment(
    JNIEnv* env, jobject /* thiz */,
    jintArray javaPixelsIn, jintArray javaPixelsOut,
    jint width, jint height, jint numClusters, jint iterations) {
    if (javaPixelsIn == nullptr || javaPixelsOut == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    int n = width * height;
    if (env->GetArrayLength(javaPixelsIn) < n || env->GetArrayLength(javaPixelsOut) < n) {
        return JNI_FALSE;
    }
    jint* pixelsIn = env->GetIntArrayElements(javaPixelsIn, nullptr);
    jint* pixelsOut = env->GetIntArrayElements(javaPixelsOut, nullptr);
    if (pixelsIn == nullptr || pixelsOut == nullptr) {
        if (pixelsIn) env->ReleaseIntArrayElements(javaPixelsIn, pixelsIn, JNI_ABORT);
        if (pixelsOut) env->ReleaseIntArrayElements(javaPixelsOut, pixelsOut, JNI_ABORT);
        return JNI_FALSE;
    }
    if (!s_logged_once) {
        LOGI("nativeSegment %dx%d K=%d iter=%d", (int)width, (int)height, (int)numClusters, (int)iterations);
        s_logged_once = true;
    }
    segmentFrame(pixelsIn, pixelsOut, width, height, (int)numClusters, (int)iterations);
    env->ReleaseIntArrayElements(javaPixelsIn, pixelsIn, JNI_ABORT);
    env->ReleaseIntArrayElements(javaPixelsOut, pixelsOut, 0);
    return JNI_TRUE;
}

}  // extern "C"
