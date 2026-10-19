#include "text_cluster_core.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace robotics::text {

struct WordBox {
    int left, top, right, bottom;
    int index;
    float centerY() const { return (top + bottom) * 0.5f; }
    int height() const { return bottom - top; }
};

void clusterWordsIntoLines(
    const int* left, const int* top, const int* right, const int* bottom,
    int count, int* outGroupIds) {
    if (count <= 0) return;
    if (count == 1) {
        outGroupIds[0] = 0;
        return;
    }

    std::vector<WordBox> words(static_cast<size_t>(count));
    for (int i = 0; i < count; i++) {
        words[i].left = left[i];
        words[i].top = top[i];
        words[i].right = right[i];
        words[i].bottom = bottom[i];
        words[i].index = i;
    }

    std::sort(words.begin(), words.end(), [](const WordBox& a, const WordBox& b) {
        float cyA = a.centerY();
        float cyB = b.centerY();
        const float lineTolerance = 0.4f;
        int avgH = (a.height() + b.height()) / 2;
        if (avgH <= 0) avgH = 1;
        float tol = lineTolerance * avgH;
        if (std::fabs(cyA - cyB) > tol) return cyA < cyB;
        return a.left < b.left;
    });

    int groupId = 0;
    outGroupIds[words[0].index] = groupId;
    for (int i = 1; i < count; i++) {
        float cyPrev = words[i - 1].centerY();
        float cyCur = words[i].centerY();
        int hPrev = words[i - 1].height();
        int hCur = words[i].height();
        int avgH = (hPrev + hCur) / 2;
        if (avgH <= 0) avgH = 1;
        float yTol = 0.5f * avgH;
        int xGap = words[i].left - words[i - 1].right;
        bool sameLine = std::fabs(cyCur - cyPrev) <= yTol && xGap < 4 * avgH;
        if (!sameLine) groupId++;
        outGroupIds[words[i].index] = groupId;
    }
}

}  // namespace robotics::text
