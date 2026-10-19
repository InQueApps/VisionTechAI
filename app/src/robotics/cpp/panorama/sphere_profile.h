// Lightweight scoped timers for sphere stitch / feature hot paths.
// Logs with tag SphereProfile (filter: adb logcat -s SphereProfile).

#ifndef SPHERE_PROFILE_H
#define SPHERE_PROFILE_H

#include <android/log.h>
#include <chrono>
#include <cstdint>

#define SPHERE_PROFILE_TAG "SphereProfile"

static inline void sphereProfileLogUs(const char* step, int64_t us) {
    __android_log_print(
        ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
        "SphereProfile %s=%lldus (%.2fms)", step, (long long)us, us / 1000.0);
}

struct SphereProfileScope {
    const char* label;
    std::chrono::steady_clock::time_point start;
    explicit SphereProfileScope(const char* l)
        : label(l), start(std::chrono::steady_clock::now()) {}
    ~SphereProfileScope() {
        const auto us = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - start).count();
        sphereProfileLogUs(label, us);
    }
};

#define SPHERE_PROFILE_SCOPE(name) SphereProfileScope _sphere_profile_scope_##__LINE__(name)

#endif  // SPHERE_PROFILE_H
