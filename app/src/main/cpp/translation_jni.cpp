/**
 * Word clustering for text translation: group words into lines/sentences by location.
 * Input: word bounding boxes (left, top, right, bottom). Output: group id per word.
 * Words on the same line (similar y, sequential x) get the same group id.
 */

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <vector>
#include <cmath>

#define LOG_TAG "TranslationJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct WordBox {
    int left, top, right, bottom;
    int index;  // original index
    float centerY() const { return (top + bottom) * 0.5f; }
    float centerX() const { return (left + right) * 0.5f; }
    int height() const { return bottom - top; }
};

static void clusterWordsIntoLines(
    const int* left, const int* top, const int* right, const int* bottom,
    int count, int* outGroupIds) {
    if (count <= 0) return;
    if (count == 1) {
        outGroupIds[0] = 0;
        return;
    }

    std::vector<WordBox> words(count);
    for (int i = 0; i < count; i++) {
        words[i].left = left[i];
        words[i].top = top[i];
        words[i].right = right[i];
        words[i].bottom = bottom[i];
        words[i].index = i;
    }

    // Sort by vertical position (top + bottom)/2, then by horizontal (left)
    std::sort(words.begin(), words.end(), [](const WordBox& a, const WordBox& b) {
        float cyA = a.centerY();
        float cyB = b.centerY();
        const float lineTolerance = 0.4f;  // fraction of avg height to merge lines
        int avgH = (a.height() + b.height()) / 2;
        if (avgH <= 0) avgH = 1;
        float tol = lineTolerance * avgH;
        if (std::abs(cyA - cyB) > tol)
            return cyA < cyB;
        return a.left < b.left;
    });

    // Assign group ids: same line if centerY within tolerance and not too far in x
    int groupId = 0;
    outGroupIds[words[0].index] = groupId;
    int maxH = words[0].height();
    for (int i = 1; i < count; i++) {
        float cyPrev = words[i - 1].centerY();
        float cyCur = words[i].centerY();
        int hPrev = words[i - 1].height();
        int hCur = words[i].height();
        int avgH = (hPrev + hCur) / 2;
        if (avgH <= 0) avgH = 1;
        float yTol = 0.5f * avgH;
        int xGap = words[i].left - words[i - 1].right;
        // New line if y differs too much or x gap is very large (new phrase/block)
        bool sameLine = std::abs(cyCur - cyPrev) <= yTol && xGap < 4 * avgH;
        if (!sameLine) groupId++;
        outGroupIds[words[i].index] = groupId;
    }
}

extern "C" {

/**
 * Cluster words into lines/sentences by location.
 * left, top, right, bottom: arrays of length count (word bounding boxes).
 * outGroupIds: output array of length count; same value = same sentence/line.
 */
JNIEXPORT void JNICALL
Java_ab_inque_visiontechai_TextTranslatorNative_clusterWordsIntoLines(
    JNIEnv* env, jclass /* clazz */,
    jintArray left, jintArray top, jintArray right, jintArray bottom,
    jint count, jintArray outGroupIds) {
    if (count <= 0 || left == nullptr || top == nullptr || right == nullptr ||
        bottom == nullptr || outGroupIds == nullptr) {
        return;
    }
    if (env->GetArrayLength(left) < count || env->GetArrayLength(top) < count ||
        env->GetArrayLength(right) < count || env->GetArrayLength(bottom) < count ||
        env->GetArrayLength(outGroupIds) < count) {
        return;
    }
    jint* l = env->GetIntArrayElements(left, nullptr);
    jint* t = env->GetIntArrayElements(top, nullptr);
    jint* r = env->GetIntArrayElements(right, nullptr);
    jint* b = env->GetIntArrayElements(bottom, nullptr);
    jint* out = env->GetIntArrayElements(outGroupIds, nullptr);
    if (!l || !t || !r || !b || !out) {
        if (l) env->ReleaseIntArrayElements(left, l, JNI_ABORT);
        if (t) env->ReleaseIntArrayElements(top, t, JNI_ABORT);
        if (r) env->ReleaseIntArrayElements(right, r, JNI_ABORT);
        if (b) env->ReleaseIntArrayElements(bottom, b, JNI_ABORT);
        if (out) env->ReleaseIntArrayElements(outGroupIds, out, JNI_ABORT);
        return;
    }
    clusterWordsIntoLines(l, t, r, b, count, out);
    env->ReleaseIntArrayElements(left, l, JNI_ABORT);
    env->ReleaseIntArrayElements(top, t, JNI_ABORT);
    env->ReleaseIntArrayElements(right, r, JNI_ABORT);
    env->ReleaseIntArrayElements(bottom, b, JNI_ABORT);
    env->ReleaseIntArrayElements(outGroupIds, out, 0);
}

}  // extern "C"
