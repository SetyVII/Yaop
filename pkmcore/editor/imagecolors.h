#pragma once
// Dominant colors of an image as option ids of image.colors (spec/types.md). Local, no AI:
// every pixel of a small copy goes to its nearest reference color and the largest shares win.

#include <QImage>
#include <QStringList>

namespace pkm {

// Up to `max` ids ("red", "white", …) that cover at least `minShare` of the visible pixels,
// largest first. Empty for a null or fully transparent image.
QStringList dominantColorIds(const QImage &image, int max = 3, double minShare = 0.12);

// The reference color ids, in the order of spec/fixtures/types/standard.json.
QStringList referenceColorIds();

} // namespace pkm
