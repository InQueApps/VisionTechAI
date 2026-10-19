/**
 * Native image segmentation (JNI).
 *
 * Algorithm: SLIC-inspired K-means on 5D feature vectors (R, G, B, x, y)
 * with K-means++ initialization, temporal center tracking for frame-to-frame
 * color stability, and post-processing (label median filter + small region merge).
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <vector>
#include <algorithm>
#include <random>

#define LOG_TAG "SegmenterJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static const int MAX_K = 10;
static const uint8_t ALPHA_OVERLAY = 200;

/**
 * Spatial weight controls how much pixel position influences clustering
 * relative to color. 0 = pure color K-means, higher = more compact regions.
 */
static const float SPATIAL_WEIGHT = 0.35f;

static const int MIN_REGION_SIZE = 12;

static const uint8_t CLUSTER_COLORS[MAX_K][3] = {
    {231,  76,  60},   // Red
    { 46, 204, 113},   // Green
    { 52, 152, 219},   // Blue
    {241, 196,  15},   // Yellow
    {155,  89, 182},   // Purple
    {230, 126,  34},   // Orange
    { 26, 188, 156},   // Teal
    {236,  72, 153},   // Pink
    {149, 165, 166},   // Gray
    { 44,  62,  80},   // Dark
};

// ---- Temporal state ----
static float s_prevCenterR[MAX_K], s_prevCenterG[MAX_K], s_prevCenterB[MAX_K];
static float s_prevCenterX[MAX_K], s_prevCenterY[MAX_K];
static int   s_prevK = 0;
static bool  s_hasPrevCenters = false;

// ---- K-means++ initialization ----

static void initCentersKMeansPP(
    const float* r, const float* g, const float* b,
    const float* x, const float* y,
    int n, int K,
    float* cR, float* cG, float* cB, float* cX, float* cY)
{
    static std::mt19937 rng(42);

    std::uniform_int_distribution<int> idist(0, n - 1);
    int first = idist(rng);
    cR[0] = r[first]; cG[0] = g[first]; cB[0] = b[first];
    cX[0] = x[first]; cY[0] = y[first];

    std::vector<float> minDist(n, 1e18f);

    for (int k = 1; k < K; k++) {
        for (int i = 0; i < n; i++) {
            float dr = r[i] - cR[k - 1];
            float dg = g[i] - cG[k - 1];
            float db = b[i] - cB[k - 1];
            float dx = x[i] - cX[k - 1];
            float dy = y[i] - cY[k - 1];
            float d = dr*dr + dg*dg + db*db + dx*dx + dy*dy;
            if (d < minDist[i]) minDist[i] = d;
        }

        float total = 0;
        for (int i = 0; i < n; i++) total += minDist[i];

        if (total <= 0) {
            int idx = idist(rng);
            cR[k] = r[idx]; cG[k] = g[idx]; cB[k] = b[idx];
            cX[k] = x[idx]; cY[k] = y[idx];
            continue;
        }

        std::uniform_real_distribution<float> fdist(0, total);
        float threshold = fdist(rng);
        float cumulative = 0;
        int chosen = n - 1;
        for (int i = 0; i < n; i++) {
            cumulative += minDist[i];
            if (cumulative >= threshold) { chosen = i; break; }
        }
        cR[k] = r[chosen]; cG[k] = g[chosen]; cB[k] = b[chosen];
        cX[k] = x[chosen]; cY[k] = y[chosen];
    }
}

// ---- Temporal matching (greedy nearest in color space) ----

static void matchCentersToPrevious(
    int K,
    float* cR, float* cG, float* cB, float* cX, float* cY,
    int* assign, int n)
{
    if (!s_hasPrevCenters || s_prevK != K) return;

    // Cost = color distance only (spatial position shifts with camera motion)
    float cost[MAX_K][MAX_K];
    for (int i = 0; i < K; i++) {
        for (int j = 0; j < K; j++) {
            float dr = cR[i] - s_prevCenterR[j];
            float dg = cG[i] - s_prevCenterG[j];
            float db = cB[i] - s_prevCenterB[j];
            cost[i][j] = dr*dr + dg*dg + db*db;
        }
    }

    int mapping[MAX_K];
    bool usedPrev[MAX_K];
    std::memset(mapping, -1, sizeof(mapping));
    std::memset(usedPrev, 0, sizeof(usedPrev));

    for (int step = 0; step < K; step++) {
        float best = 1e18f;
        int bestI = 0, bestJ = 0;
        for (int i = 0; i < K; i++) {
            if (mapping[i] >= 0) continue;
            for (int j = 0; j < K; j++) {
                if (usedPrev[j]) continue;
                if (cost[i][j] < best) {
                    best = cost[i][j];
                    bestI = i;
                    bestJ = j;
                }
            }
        }
        mapping[bestI] = bestJ;
        usedPrev[bestJ] = true;
    }

    float tmpR[MAX_K], tmpG[MAX_K], tmpB[MAX_K], tmpX[MAX_K], tmpY[MAX_K];
    for (int k = 0; k < K; k++) {
        int t = mapping[k];
        tmpR[t] = cR[k]; tmpG[t] = cG[k]; tmpB[t] = cB[k];
        tmpX[t] = cX[k]; tmpY[t] = cY[k];
    }

    for (int i = 0; i < n; i++) {
        assign[i] = mapping[assign[i]];
    }

    std::memcpy(cR, tmpR, K * sizeof(float));
    std::memcpy(cG, tmpG, K * sizeof(float));
    std::memcpy(cB, tmpB, K * sizeof(float));
    std::memcpy(cX, tmpX, K * sizeof(float));
    std::memcpy(cY, tmpY, K * sizeof(float));
}

// ---- Post-processing ----

static void medianFilterLabels(int* labels, int width, int height, int K) {
    std::vector<int> out(width * height);
    int hist[MAX_K];

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            std::memset(hist, 0, K * sizeof(int));
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= height) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= width) continue;
                    hist[labels[ny * width + nx]]++;
                }
            }
            int best = labels[y * width + x];
            int bestCnt = 0;
            for (int k = 0; k < K; k++) {
                if (hist[k] > bestCnt) { bestCnt = hist[k]; best = k; }
            }
            out[y * width + x] = best;
        }
    }
    std::memcpy(labels, out.data(), width * height * sizeof(int));
}

static void mergeSmallRegions(int* labels, int width, int height, int K, int minSize) {
    int n = width * height;
    std::vector<bool> visited(n, false);
    std::vector<int> queue;
    static const int dx4[] = {0, 0, -1, 1};
    static const int dy4[] = {-1, 1, 0, 0};
    int neighborHist[MAX_K];

    for (int start = 0; start < n; start++) {
        if (visited[start]) continue;

        queue.clear();
        queue.push_back(start);
        visited[start] = true;
        int label = labels[start];
        int head = 0;

        while (head < (int)queue.size()) {
            int idx = queue[head++];
            int cx = idx % width;
            int cy = idx / width;
            for (int d = 0; d < 4; d++) {
                int nx = cx + dx4[d];
                int ny = cy + dy4[d];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    int nidx = ny * width + nx;
                    if (!visited[nidx] && labels[nidx] == label) {
                        visited[nidx] = true;
                        queue.push_back(nidx);
                    }
                }
            }
        }

        if ((int)queue.size() >= minSize) continue;

        std::memset(neighborHist, 0, K * sizeof(int));
        for (int idx : queue) {
            int cx = idx % width;
            int cy = idx / width;
            for (int d = 0; d < 4; d++) {
                int nx = cx + dx4[d];
                int ny = cy + dy4[d];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    int nidx = ny * width + nx;
                    if (labels[nidx] != label) {
                        neighborHist[labels[nidx]]++;
                    }
                }
            }
        }
        int bestNeighbor = label;
        int bestCnt = 0;
        for (int k = 0; k < K; k++) {
            if (neighborHist[k] > bestCnt) {
                bestCnt = neighborHist[k];
                bestNeighbor = k;
            }
        }
        if (bestNeighbor != label) {
            for (int idx : queue) labels[idx] = bestNeighbor;
        }
    }
}

// ---- Main segmentation ----

static void segmentFrame(const int32_t* pixelsIn, int32_t* pixelsOut,
                         int width, int height, int K, int iterations)
{
    const int n = width * height;
    if (n <= 0 || K < 1 || K > MAX_K) return;
    K = std::min(K, MAX_K);
    iterations = std::max(1, std::min(iterations, 20));

    std::vector<float> r(n), g(n), b(n), px(n), py(n);
    float xScale = 255.0f * SPATIAL_WEIGHT / std::max(1, width  - 1);
    float yScale = 255.0f * SPATIAL_WEIGHT / std::max(1, height - 1);

    for (int i = 0; i < n; i++) {
        uint32_t argb = static_cast<uint32_t>(pixelsIn[i]);
        b[i]  = (argb >>  0) & 0xFF;
        g[i]  = (argb >>  8) & 0xFF;
        r[i]  = (argb >> 16) & 0xFF;
        px[i] = (i % width)  * xScale;
        py[i] = (i / width)  * yScale;
    }

    // Initialize centers: temporal seed or K-means++
    std::vector<float> cR(K), cG(K), cB(K), cX(K), cY(K);

    if (s_hasPrevCenters && s_prevK == K) {
        std::memcpy(cR.data(), s_prevCenterR, K * sizeof(float));
        std::memcpy(cG.data(), s_prevCenterG, K * sizeof(float));
        std::memcpy(cB.data(), s_prevCenterB, K * sizeof(float));
        std::memcpy(cX.data(), s_prevCenterX, K * sizeof(float));
        std::memcpy(cY.data(), s_prevCenterY, K * sizeof(float));
    } else {
        initCentersKMeansPP(r.data(), g.data(), b.data(), px.data(), py.data(),
                            n, K, cR.data(), cG.data(), cB.data(), cX.data(), cY.data());
    }

    std::vector<int> assign(n, 0);
    std::vector<float> sumR(K), sumG(K), sumB(K), sumX(K), sumY(K);
    std::vector<int> count(K);

    for (int iter = 0; iter < iterations; iter++) {
        std::fill(sumR.begin(), sumR.end(), 0.f);
        std::fill(sumG.begin(), sumG.end(), 0.f);
        std::fill(sumB.begin(), sumB.end(), 0.f);
        std::fill(sumX.begin(), sumX.end(), 0.f);
        std::fill(sumY.begin(), sumY.end(), 0.f);
        std::fill(count.begin(), count.end(), 0);

        for (int i = 0; i < n; i++) {
            float best = 1e18f;
            int bestK = 0;
            for (int k = 0; k < K; k++) {
                float dr = r[i]  - cR[k];
                float dg = g[i]  - cG[k];
                float db = b[i]  - cB[k];
                float dx = px[i] - cX[k];
                float dy = py[i] - cY[k];
                float d = dr*dr + dg*dg + db*db + dx*dx + dy*dy;
                if (d < best) { best = d; bestK = k; }
            }
            assign[i] = bestK;
            sumR[bestK] += r[i];
            sumG[bestK] += g[i];
            sumB[bestK] += b[i];
            sumX[bestK] += px[i];
            sumY[bestK] += py[i];
            count[bestK]++;
        }

        for (int k = 0; k < K; k++) {
            if (count[k] > 0) {
                cR[k] = sumR[k] / count[k];
                cG[k] = sumG[k] / count[k];
                cB[k] = sumB[k] / count[k];
                cX[k] = sumX[k] / count[k];
                cY[k] = sumY[k] / count[k];
            }
        }

        if (iter < iterations - 1) {
            for (int k = 0; k < K; k++) {
                if (count[k] == 0) {
                    float bestDist = -1.f;
                    int bestIdx = 0;
                    for (int i = 0; i < n; i++) {
                        int ak = assign[i];
                        float dr = r[i]  - cR[ak];
                        float dg = g[i]  - cG[ak];
                        float db = b[i]  - cB[ak];
                        float dx = px[i] - cX[ak];
                        float dy = py[i] - cY[ak];
                        float d = dr*dr + dg*dg + db*db + dx*dx + dy*dy;
                        if (d > bestDist) { bestDist = d; bestIdx = i; }
                    }
                    cR[k] = r[bestIdx]; cG[k] = g[bestIdx]; cB[k] = b[bestIdx];
                    cX[k] = px[bestIdx]; cY[k] = py[bestIdx];
                }
            }
        }
    }

    // Temporal matching: remap cluster indices to match previous frame
    matchCentersToPrevious(K, cR.data(), cG.data(), cB.data(), cX.data(), cY.data(),
                           assign.data(), n);

    // Store centers for next frame
    std::memcpy(s_prevCenterR, cR.data(), K * sizeof(float));
    std::memcpy(s_prevCenterG, cG.data(), K * sizeof(float));
    std::memcpy(s_prevCenterB, cB.data(), K * sizeof(float));
    std::memcpy(s_prevCenterX, cX.data(), K * sizeof(float));
    std::memcpy(s_prevCenterY, cY.data(), K * sizeof(float));
    s_prevK = K;
    s_hasPrevCenters = true;

    // Post-processing: smooth labels then absorb tiny regions
    medianFilterLabels(assign.data(), width, height, K);
    mergeSmallRegions(assign.data(), width, height, K, MIN_REGION_SIZE);

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
Java_ab_inque_robotics_vision_segment_SegmenterNative_nativeSegment(
    JNIEnv* env, jobject /* thiz */,
    jintArray javaPixelsIn, jintArray javaPixelsOut,
    jint width, jint height, jint numClusters, jint iterations)
{
    if (javaPixelsIn == nullptr || javaPixelsOut == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    int n = width * height;
    if (env->GetArrayLength(javaPixelsIn) < n || env->GetArrayLength(javaPixelsOut) < n) {
        return JNI_FALSE;
    }
    jint* pixelsIn  = env->GetIntArrayElements(javaPixelsIn,  nullptr);
    jint* pixelsOut = env->GetIntArrayElements(javaPixelsOut, nullptr);
    if (pixelsIn == nullptr || pixelsOut == nullptr) {
        if (pixelsIn)  env->ReleaseIntArrayElements(javaPixelsIn,  pixelsIn,  JNI_ABORT);
        if (pixelsOut) env->ReleaseIntArrayElements(javaPixelsOut, pixelsOut, JNI_ABORT);
        return JNI_FALSE;
    }
    if (!s_logged_once) {
        LOGI("nativeSegment %dx%d K=%d iter=%d (SLIC + temporal)", (int)width, (int)height, (int)numClusters, (int)iterations);
        s_logged_once = true;
    }
    segmentFrame(pixelsIn, pixelsOut, width, height, (int)numClusters, (int)iterations);
    env->ReleaseIntArrayElements(javaPixelsIn,  pixelsIn,  JNI_ABORT);
    env->ReleaseIntArrayElements(javaPixelsOut, pixelsOut, 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_vision_segment_SegmenterNative_nativeReset(
    JNIEnv* /* env */, jobject /* thiz */)
{
    s_hasPrevCenters = false;
    s_prevK = 0;
    LOGI("nativeReset: temporal state cleared");
}

}  // extern "C"
