#include "imagecolors.h"

#include <QColor>
#include <QVector>
#include <algorithm>

namespace pkm {

namespace {

struct Ref { const char *id; int r, g, b; };

// One representative color per option of image.colors.
const Ref kRefs[] = {
    { "black", 0, 0, 0 },       { "blue", 30, 91, 255 },     { "cyan", 0, 188, 212 },
    { "green", 46, 158, 68 },   { "teal", 0, 128, 128 },     { "turquoise", 64, 224, 208 },
    { "indigo", 63, 43, 150 },  { "gray", 128, 128, 128 },   { "purple", 123, 44, 191 },
    { "brown", 123, 74, 38 },   { "light_beige", 242, 232, 213 }, { "violet", 181, 126, 220 },
    { "beige", 216, 195, 165 }, { "fuchsia", 255, 0, 255 },  { "gold", 212, 175, 55 },
    { "magenta", 194, 24, 91 }, { "orange", 255, 140, 26 },  { "pink", 247, 161, 196 },
    { "red", 214, 40, 40 },     { "white", 255, 255, 255 },  { "yellow", 255, 225, 53 },
};
constexpr int kRefCount = int(sizeof(kRefs) / sizeof(kRefs[0]));

// "Redmean" weighted RGB distance: cheap and close enough to perception for 21 buckets.
int nearest(int r, int g, int b)
{
    int best = 0;
    long bestD = -1;
    for (int i = 0; i < kRefCount; ++i) {
        const long rm = (r + kRefs[i].r) / 2;
        const long dr = r - kRefs[i].r, dg = g - kRefs[i].g, db = b - kRefs[i].b;
        const long d = ((512 + rm) * dr * dr >> 8) + 4 * dg * dg + ((767 - rm) * db * db >> 8);
        if (bestD < 0 || d < bestD) {
            bestD = d;
            best = i;
        }
    }
    return best;
}

} // namespace

QStringList referenceColorIds()
{
    QStringList out;
    for (const Ref &r : kRefs)
        out.append(QString::fromLatin1(r.id));
    return out;
}

QStringList dominantColorIds(const QImage &image, int max, double minShare)
{
    if (image.isNull() || max <= 0)
        return {};
    const QImage small = image.scaled(48, 48, Qt::KeepAspectRatio, Qt::FastTransformation)
                             .convertToFormat(QImage::Format_ARGB32);
    QVector<int> counts(kRefCount, 0);
    int total = 0;
    for (int y = 0; y < small.height(); ++y) {
        const QRgb *line = reinterpret_cast<const QRgb *>(small.constScanLine(y));
        for (int x = 0; x < small.width(); ++x) {
            if (qAlpha(line[x]) < 128)
                continue;
            ++counts[nearest(qRed(line[x]), qGreen(line[x]), qBlue(line[x]))];
            ++total;
        }
    }
    if (total == 0)
        return {};
    QVector<int> order(kRefCount);
    for (int i = 0; i < kRefCount; ++i)
        order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return counts[a] > counts[b]; });
    QStringList out;
    for (int i : order) {
        if (out.size() >= max || counts[i] < minShare * total)
            break;
        out.append(QString::fromLatin1(kRefs[i].id));
    }
    return out;
}

} // namespace pkm
