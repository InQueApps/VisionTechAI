/**
 * Word clustering for text translation: group words into lines/sentences by location.
 */

#include "text_cluster_core.h"

#include <jni.h>

extern "C" {

JNIEXPORT void JNICALL
Java_ab_inque_robotics_text_TextLineClustererNative_clusterWordsIntoLines(
    JNIEnv* env, jclass,
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
    robotics::text::clusterWordsIntoLines(l, t, r, b, count, out);
    env->ReleaseIntArrayElements(left, l, JNI_ABORT);
    env->ReleaseIntArrayElements(top, t, JNI_ABORT);
    env->ReleaseIntArrayElements(right, r, JNI_ABORT);
    env->ReleaseIntArrayElements(bottom, b, JNI_ABORT);
    env->ReleaseIntArrayElements(outGroupIds, out, 0);
}

}  // extern "C"
