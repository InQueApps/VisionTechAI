#pragma once

#include <cstdio>
#include <cstdlib>
#include <vector>

namespace robotics::test {

struct TestCase {
    const char* name;
    bool (*run)();
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> cases;
    return cases;
}

struct Registrar {
    Registrar(const char* name, bool (*run)()) {
        registry().push_back({name, run});
    }
};

#define ROBOTICS_TEST(test_name) \
    bool robotics_test_body_##test_name(); \
    bool robotics_test_run_##test_name() { return robotics_test_body_##test_name(); } \
    static robotics::test::Registrar robotics_test_reg_##test_name( \
        #test_name, robotics_test_run_##test_name); \
    bool robotics_test_body_##test_name()

#define EXPECT_TRUE(expr) \
    do { \
        if (!(expr)) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected true: %s\n", __FILE__, __LINE__, #expr); \
            return false; \
        } \
    } while (0)

#define EXPECT_FALSE(expr) EXPECT_TRUE(!(expr))

#define EXPECT_EQ(a, b) \
    do { \
        auto _a = (a); \
        auto _b = (b); \
        if (!(_a == _b)) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected equal: %s vs %s\n", \
                __FILE__, __LINE__, #a, #b); \
            return false; \
        } \
    } while (0)

#define EXPECT_NE(a, b) \
    do { \
        auto _a = (a); \
        auto _b = (b); \
        if (_a == _b) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected not equal: %s\n", \
                __FILE__, __LINE__, #a); \
            return false; \
        } \
    } while (0)

#define EXPECT_NEAR(a, b, eps) \
    do { \
        auto _a = (a); \
        auto _b = (b); \
        auto _d = (_a) > (_b) ? (_a) - (_b) : (_b) - (_a); \
        if (_d > (eps)) { \
            std::fprintf(stderr, "  FAIL %s:%d: %s (~%g) not near %s (~%g)\n", \
                __FILE__, __LINE__, #a, (double)(_a), #b, (double)(_b)); \
            return false; \
        } \
    } while (0)

#define EXPECT_LE(a, b) \
    do { \
        if (!((a) <= (b))) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected %s <= %s\n", \
                __FILE__, __LINE__, #a, #b); \
            return false; \
        } \
    } while (0)

#define EXPECT_GE(a, b) \
    do { \
        if (!((a) >= (b))) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected %s >= %s\n", \
                __FILE__, __LINE__, #a, #b); \
            return false; \
        } \
    } while (0)

#define EXPECT_LT(a, b) \
    do { \
        if (!((a) < (b))) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected %s < %s\n", \
                __FILE__, __LINE__, #a, #b); \
            return false; \
        } \
    } while (0)

#define EXPECT_GT(a, b) \
    do { \
        if (!((a) > (b))) { \
            std::fprintf(stderr, "  FAIL %s:%d: expected %s > %s\n", \
                __FILE__, __LINE__, #a, #b); \
            return false; \
        } \
    } while (0)

inline int runAll() {
    int failures = 0;
    for (const auto& t : registry()) {
        std::printf("RUN  %s\n", t.name);
        if (!t.run()) {
            std::printf("FAIL %s\n", t.name);
            failures++;
        }
    }
    std::printf("robotics_cpp_tests: %zu tests, %d failures\n",
        registry().size(), failures);
    return failures;
}

}  // namespace robotics::test
