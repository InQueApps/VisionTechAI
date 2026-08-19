#pragma once

namespace robotics::text {

/** Cluster words into lines by bounding box position. outGroupIds length = count. */
void clusterWordsIntoLines(
    const int* left, const int* top, const int* right, const int* bottom,
    int count, int* outGroupIds);

}  // namespace robotics::text
