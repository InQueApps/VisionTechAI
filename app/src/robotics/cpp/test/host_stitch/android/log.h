// Host build shim for <android/log.h>. Routes native stitch logs to stderr when
// SPHERE_HOST_LOG=1 is set; silent otherwise so timing runs stay quiet.
#pragma once

#include <cstdarg>
#include <cstdio>
#include <cstdlib>

enum {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
};

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    static const int enabled = [] {
        const char* v = std::getenv("SPHERE_HOST_LOG");
        return (v != nullptr && v[0] == '1') ? 1 : 0;
    }();
    if (!enabled && prio < ANDROID_LOG_WARN) return 0;
    if (!enabled) return 0;
    std::fprintf(stderr, "%c/%s: ", "??VDIWEF"[prio & 7], tag);
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    std::fputc('\n', stderr);
    return 0;
}
