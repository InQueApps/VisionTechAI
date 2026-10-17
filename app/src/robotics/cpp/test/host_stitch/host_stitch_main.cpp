// Host harness for the sphere stitcher: compiles the production sphere_jni.cpp
// unchanged and replays the same JNI call sequence the Android worker uses, so
// algorithm changes can be measured in seconds instead of device round-trips.
//
// Usage: host_stitch <job.txt> <out_dir>
//
// job.txt lines:
//   eq <w> <h>                capture equirect size passed to nativeInit
//   fov <h_deg> <v_deg>
//   export <w> <h>            final export resolution
//   pipeline production|direct
//   frame <argb_path> <w> <h> <qx> <qy> <qz> <qw> <overlap_gate>
//
// Frame files are raw little-endian int32 ARGB (Android Bitmap.getPixels layout).
// Outputs in out_dir: equirect.argb, poses.txt (index refined fast imu trusted),
// timing.json.

#include "../../panorama/sphere_jni.cpp"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct FakeArray {
    std::vector<int32_t> ints;
    std::vector<float> floats;
    bool isFloat = false;
};

FakeArray* arr(jarray a) { return reinterpret_cast<FakeArray*>(a); }

jsize JNICALL fakeGetArrayLength(JNIEnv*, jarray a) {
    FakeArray* f = arr(a);
    return static_cast<jsize>(f->isFloat ? f->floats.size() : f->ints.size());
}

jint* JNICALL fakeGetIntArrayElements(JNIEnv*, jintArray a, jboolean* isCopy) {
    if (isCopy) *isCopy = JNI_FALSE;
    return reinterpret_cast<jint*>(arr(a)->ints.data());
}

void JNICALL fakeReleaseIntArrayElements(JNIEnv*, jintArray, jint*, jint) {}

void JNICALL fakeGetFloatArrayRegion(JNIEnv*, jfloatArray a, jsize start, jsize len, jfloat* buf) {
    std::memcpy(buf, arr(a)->floats.data() + start, sizeof(float) * (size_t)len);
}

std::vector<FakeArray*> gOwnedArrays;

jfloatArray JNICALL fakeNewFloatArray(JNIEnv*, jsize len) {
    auto* f = new FakeArray();
    f->isFloat = true;
    f->floats.assign((size_t)len, 0.f);
    gOwnedArrays.push_back(f);
    return reinterpret_cast<jfloatArray>(f);
}

void JNICALL fakeSetFloatArrayRegion(JNIEnv*, jfloatArray a, jsize start, jsize len, const jfloat* buf) {
    std::memcpy(arr(a)->floats.data() + start, buf, sizeof(float) * (size_t)len);
}

struct FakeEnv {
    JNINativeInterface table{};
    JNIEnv env{};
    FakeEnv() {
        std::memset(&table, 0, sizeof(table));
        table.GetArrayLength = fakeGetArrayLength;
        table.GetIntArrayElements = fakeGetIntArrayElements;
        table.ReleaseIntArrayElements = fakeReleaseIntArrayElements;
        table.GetFloatArrayRegion = fakeGetFloatArrayRegion;
        table.NewFloatArray = fakeNewFloatArray;
        table.SetFloatArrayRegion = fakeSetFloatArrayRegion;
        env.functions = &table;
    }
};

struct FrameJob {
    std::string path;
    int w = 0, h = 0;
    float q[4] = {0, 0, 0, 1};
    float gate = 0.f;
};

struct Job {
    int eqW = 1024, eqH = 512;
    float fovH = 90.f, fovV = 90.f;
    int exportW = 4096, exportH = 2048;
    std::string pipeline = "production";
    std::vector<FrameJob> frames;
};

bool loadJob(const std::string& path, Job& job) {
    std::ifstream in(path);
    if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string key;
        if (!(ss >> key) || key[0] == '#') continue;
        if (key == "eq") ss >> job.eqW >> job.eqH;
        else if (key == "fov") ss >> job.fovH >> job.fovV;
        else if (key == "export") ss >> job.exportW >> job.exportH;
        else if (key == "pipeline") ss >> job.pipeline;
        else if (key == "frame") {
            FrameJob f;
            ss >> f.path >> f.w >> f.h >> f.q[0] >> f.q[1] >> f.q[2] >> f.q[3] >> f.gate;
            job.frames.push_back(f);
        }
    }
    return !job.frames.empty();
}

bool readArgb(const FrameJob& f, FakeArray& out) {
    std::ifstream in(f.path, std::ios::binary);
    if (!in) return false;
    out.isFloat = false;
    out.ints.resize((size_t)f.w * f.h);
    in.read(reinterpret_cast<char*>(out.ints.data()), (std::streamsize)(out.ints.size() * 4));
    return (size_t)in.gcount() == out.ints.size() * 4;
}

using Clock = std::chrono::steady_clock;

struct Timing {
    std::vector<std::pair<std::string, double>> steps;
    Clock::time_point t = Clock::now();
    void mark(const std::string& name) {
        auto now = Clock::now();
        steps.emplace_back(name, std::chrono::duration<double, std::milli>(now - t).count());
        t = now;
    }
};

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: host_stitch <job.txt> <out_dir>\n");
        return 2;
    }
    Job job;
    if (!loadJob(argv[1], job)) {
        std::fprintf(stderr, "failed to load job %s\n", argv[1]);
        return 2;
    }
    const std::string outDir = argv[2];

    FakeEnv fake;
    JNIEnv* env = &fake.env;
    jobject self = nullptr;
    Timing timing;
    std::vector<double> frameMs;
    std::vector<int> frameStatus;

    Java_ab_inque_robotics_panorama_SphereNative_nativeReset(env, self);
    Java_ab_inque_robotics_panorama_SphereNative_nativeInit(
        env, self, job.eqW, job.eqH, job.fovH, job.fovV, 0.f, 0.f, 0.f, 0.f);
    Java_ab_inque_robotics_panorama_SphereNative_nativeSetMirrorCameraX(env, self, JNI_FALSE);
    timing.mark("native_init");

    for (const FrameJob& f : job.frames) {
        FakeArray px;
        if (!readArgb(f, px)) {
            std::fprintf(stderr, "failed to read frame %s\n", f.path.c_str());
            return 3;
        }
        auto t0 = Clock::now();
        jint status = Java_ab_inque_robotics_panorama_SphereNative_nativeAddFrameFast(
            env, self, reinterpret_cast<jintArray>(&px), f.w, f.h,
            f.q[0], f.q[1], f.q[2], f.q[3], f.gate, nullptr, JNI_TRUE);
        frameMs.push_back(std::chrono::duration<double, std::milli>(Clock::now() - t0).count());
        frameStatus.push_back((int)status);
    }
    timing.mark("frame_ingest_all");

    // Mirrors SphereProductionStitchPipeline: capture refine → pre-finish graph
    // cache → DONE preview refine → targeted finalization → export.
    auto setMode = [&](bool poseGraphOnly, bool fastFinish, int passes) {
        Java_ab_inque_robotics_panorama_SphereNative_nativeSetPoseGraphOnlyGlobal(
            env, self, poseGraphOnly ? JNI_TRUE : JNI_FALSE);
        Java_ab_inque_robotics_panorama_SphereNative_nativeSetFastFinishRefine(
            env, self, fastFinish ? JNI_TRUE : JNI_FALSE);
        Java_ab_inque_robotics_panorama_SphereNative_nativeSetGlobalRefinePasses(env, self, passes);
    };
    auto flush = [&] {
        Java_ab_inque_robotics_panorama_SphereNative_nativeFlushPendingFrameStores(env, self);
    };
    auto globalPass = [&] {
        if (Java_ab_inque_robotics_panorama_SphereNative_nativeHasRefineWorkPending(env, self, JNI_TRUE)) {
            Java_ab_inque_robotics_panorama_SphereNative_nativeRefinePending(env, self, JNI_TRUE);
        }
    };

    flush();
    if (job.pipeline == "production") {
        setMode(true, false, 0);
        globalPass();
        timing.mark("capture_refine");

        setMode(false, false, 0);
        Java_ab_inque_robotics_panorama_SphereNative_nativeBuildFeatureGraphCache(env, self);
        timing.mark("pre_finish_graph_cache");

        setMode(false, false, 0);
        Java_ab_inque_robotics_panorama_SphereNative_nativeRefineFeatureGraphReconcile(env, self);
        timing.mark("final_feature_graph");
        Java_ab_inque_robotics_panorama_SphereNative_nativeRefineTargetedPhotometric(env, self);
        timing.mark("final_targeted_photometric");
        Java_ab_inque_robotics_panorama_SphereNative_nativeRefineFeatureGraphReconcile(env, self);
        timing.mark("final_graph_reconcile");
        Java_ab_inque_robotics_panorama_SphereNative_nativeRefineConditionalParallax(env, self);
        timing.mark("final_conditional_parallax");
    }
    Java_ab_inque_robotics_panorama_SphereNative_nativePrepareExportEquirect(
        env, self, job.exportW, job.exportH);
    timing.mark("prepare_export");

    FakeArray outPx;
    outPx.ints.assign((size_t)job.exportW * job.exportH, 0);
    Java_ab_inque_robotics_panorama_SphereNative_nativeGetEquirect(
        env, self, reinterpret_cast<jintArray>(&outPx), job.exportW, job.exportH);
    timing.mark("get_equirect");

    {
        std::ofstream o(outDir + "/equirect.argb", std::ios::binary);
        o.write(reinterpret_cast<const char*>(outPx.ints.data()),
                (std::streamsize)(outPx.ints.size() * 4));
    }
    {
        std::ofstream o(outDir + "/poses.txt");
        for (size_t i = 0; i < sFrames.size(); i++) {
            const StoredFrame& f = sFrames[i];
            o << i << ' '
              << f.refinedQuat.x << ' ' << f.refinedQuat.y << ' ' << f.refinedQuat.z << ' ' << f.refinedQuat.w << ' '
              << f.fastCorrectedQuat.x << ' ' << f.fastCorrectedQuat.y << ' ' << f.fastCorrectedQuat.z << ' '
              << f.fastCorrectedQuat.w << ' '
              << f.imuQuat.x << ' ' << f.imuQuat.y << ' ' << f.imuQuat.z << ' ' << f.imuQuat.w << ' '
              << (f.poseTrusted ? 1 : 0) << '\n';
        }
    }
    {
        std::ofstream o(outDir + "/timing.json");
        o << "{\n  \"steps\": {";
        for (size_t i = 0; i < timing.steps.size(); i++) {
            o << (i ? ", " : "") << '"' << timing.steps[i].first << "\": " << timing.steps[i].second;
        }
        o << "},\n  \"frame_ms\": [";
        for (size_t i = 0; i < frameMs.size(); i++) o << (i ? ", " : "") << frameMs[i];
        o << "],\n  \"frame_status\": [";
        for (size_t i = 0; i < frameStatus.size(); i++) o << (i ? ", " : "") << frameStatus[i];
        o << "],\n  \"hole_fraction\": " << (sGaValid ? sGaHoleFraction : 0.f);
        o << ",\n  \"discarded\": [";
        for (size_t i = 0, k = 0; i < sGaFrames.size(); i++) {
            if (sGaFrames[i].discard) o << (k++ ? ", " : "") << i;
        }
        o << "],\n  \"stored_frames\": " << sFrames.size() << "\n}\n";
    }
    for (FakeArray* a : gOwnedArrays) delete a;
    return 0;
}
