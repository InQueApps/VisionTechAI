#include "robotics_test.h"

#include "../translation/text_cluster_core.h"

ROBOTICS_TEST(TextClusterCore_singleWord_groupZero) {
    int left[] = {10}, top[] = {20}, right[] = {50}, bottom[] = {40};
    int groups[1] = {-1};
    robotics::text::clusterWordsIntoLines(left, top, right, bottom, 1, groups);
    EXPECT_EQ(groups[0], 0);
    return true;
}

ROBOTICS_TEST(TextClusterCore_twoWordsSameLine_sameGroup) {
    int left[] = {10, 60};
    int top[] = {20, 22};
    int right[] = {50, 100};
    int bottom[] = {40, 42};
    int groups[2] = {-1, -1};
    robotics::text::clusterWordsIntoLines(left, top, right, bottom, 2, groups);
    EXPECT_EQ(groups[0], groups[1]);
    return true;
}

ROBOTICS_TEST(TextClusterCore_twoLines_differentGroups) {
    int left[] = {10, 10};
    int top[] = {20, 80};
    int right[] = {50, 50};
    int bottom[] = {40, 100};
    int groups[2] = {-1, -1};
    robotics::text::clusterWordsIntoLines(left, top, right, bottom, 2, groups);
    EXPECT_NE(groups[0], groups[1]);
    return true;
}

ROBOTICS_TEST(TextClusterCore_preservesOriginalIndices) {
    int left[] = {100, 10};
    int top[] = {20, 20};
    int right[] = {130, 40};
    int bottom[] = {40, 40};
    int groups[2] = {-1, -1};
    robotics::text::clusterWordsIntoLines(left, top, right, bottom, 2, groups);
    EXPECT_EQ(groups[0], groups[1]);
    return true;
}
