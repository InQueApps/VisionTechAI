#include "robotics_test.h"

#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_ab_inque_robotics_test_RoboticsCppTestNative_runAll(JNIEnv*, jclass) {
    return robotics::test::runAll();
}
