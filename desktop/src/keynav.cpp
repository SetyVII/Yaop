#include "keynav.h"

#include <QEventPoint>
#include <QQuickItem>
#include <QVariant>
#include <algorithm>
#include <cmath>
#include <functional>

namespace pkm {

namespace {

// Gap between two ranges, 0 when they overlap.
qreal rangeGap(qreal a0, qreal a1, qreal b0, qreal b1)
{
    return std::max<qreal>(0, std::max(a0, b0) - std::min(a1, b1));
}

QRectF sceneRect(const QQuickItem *it)
{
    return it->mapRectToScene(QRectF(0, 0, it->width(), it->height()));
}

bool isAncestor(const QQuickItem *maybeParent, const QQuickItem *it)
{
    for (const QQuickItem *p = it ? it->parentItem() : nullptr; p; p = p->parentItem())
        if (p == maybeParent)
            return true;
    return false;
}

void collectStops(const KeyNav &nav, QQuickItem *root, QList<QQuickItem *> &out)
{
    std::function<void(QQuickItem *)> walk = [&](QQuickItem *it) {
        if (!it->isVisible() || !it->isEnabled() || it->opacity() <= 0.01)
            return;
        if (it != root && nav.isStop(it)) {
            out.append(it);
            return;   // a card's own buttons are reached with Tab or the mouse
        }
        for (QQuickItem *c : it->childItems())
            walk(c);
    };
    walk(root);
}

// The left-button TapHandler of an item that shows a pointing hand: what a click on it does.
// Most clickable things in the QML are plain Rectangles with these two handlers.
QObject *clickHandler(const QQuickItem *it)
{
    QObject *tap = nullptr;
    bool hand = false;
    for (QObject *c : it->children()) {
        if (c->inherits("QQuickHoverHandler"))
            hand = hand || (c->property("enabled").toBool()
                            && c->property("cursorShape").toInt() == Qt::PointingHandCursor);
        else if (!tap && c->inherits("QQuickTapHandler") && c->property("enabled").toBool()
                 && (c->property("acceptedButtons").toInt() & Qt::LeftButton))
            tap = c;
    }
    return hand ? tap : nullptr;
}

bool isFlickable(const QQuickItem *it) { return it->inherits("QQuickFlickable"); }

// Moves a Flickable's content by `delta` on one axis, clamped; true when it moved.
bool scrollBy(QQuickItem *f, bool vertical, qreal delta)
{
    const char *pos = vertical ? "contentY" : "contentX";
    const qreal size = vertical ? f->height() : f->width();
    const qreal content = f->property(vertical ? "contentHeight" : "contentWidth").toReal();
    const qreal origin = f->property(vertical ? "originY" : "originX").toReal();
    const qreal now = f->property(pos).toReal();
    const qreal maxPos = origin + std::max<qreal>(0, content - size);
    const qreal next = std::clamp(now + delta, origin, maxPos);
    if (std::abs(next - now) < 0.5)
        return false;
    f->setProperty(pos, next);
    return true;
}

} // namespace

int pickNeighbor(const QRectF &from, const QList<QRectF> &cands, int dir)
{
    const bool vertical = dir >= 2;
    const int sign = (dir == 1 || dir == 3) ? 1 : -1;
    const QPointF fc = from.center();
    int best = -1;
    qreal bestScore = 0;
    for (int i = 0; i < cands.size(); ++i) {
        const QRectF &c = cands.at(i);
        const QPointF cc = c.center();
        // must lie that way: its centre past ours and its near edge not behind ours
        const qreal along = vertical ? (cc.y() - fc.y()) * sign : (cc.x() - fc.x()) * sign;
        const qreal edge = vertical ? (sign > 0 ? c.top() - from.top() : from.bottom() - c.bottom())
                                    : (sign > 0 ? c.left() - from.left() : from.right() - c.right());
        if (along < 1 || edge < 1)
            continue;
        const qreal gap = vertical ? (sign > 0 ? c.top() - from.bottom() : from.top() - c.bottom())
                                   : (sign > 0 ? c.left() - from.right() : from.left() - c.right());
        const qreal side = vertical ? rangeGap(from.left(), from.right(), c.left(), c.right())
                                    : rangeGap(from.top(), from.bottom(), c.top(), c.bottom());
        const qreal offset = vertical ? std::abs(cc.x() - fc.x()) : std::abs(cc.y() - fc.y());
        // being in the same column/row matters most, then the distance, then the centre offset
        const qreal score = std::max<qreal>(0, gap) + 3 * side + 0.05 * offset + 0.01 * along;
        if (best < 0 || score < bestScore) {
            best = i;
            bestScore = score;
        }
    }
    return best;
}

bool KeyNav::isStop(QQuickItem *item) const
{
    if (!item || item->width() <= 0 || item->height() <= 0)
        return false;
    if (item->activeFocusOnTab() && item->property("focusRing").toBool())
        return true;
    return clickHandler(item) != nullptr;
}

bool KeyNav::activate(QQuickItem *item) const
{
    QObject *tap = item ? clickHandler(item) : nullptr;
    if (!tap)
        return false;
    const QPointF centre(item->width() / 2, item->height() / 2);
    QEventPoint point(0, QEventPoint::Released, item->mapToScene(centre), item->mapToGlobal(centre));
    return QMetaObject::invokeMethod(tap, "tapped", Q_ARG(QEventPoint, point), Q_ARG(Qt::MouseButton, Qt::LeftButton));
}

QQuickItem *KeyNav::neighbor(QQuickItem *root, QQuickItem *from, int dir) const
{
    if (!root || !from || dir < 0 || dir > 3)
        return nullptr;
    QList<QQuickItem *> stops;
    collectStops(*this, root, stops);
    QList<QQuickItem *> kept;
    QList<QRectF> rects;
    for (QQuickItem *s : stops) {
        if (s == from || isAncestor(from, s) || isAncestor(s, from))
            continue;   // a card's own buttons are reached with Tab
        kept.append(s);
        rects.append(sceneRect(s));
    }
    const int i = pickNeighbor(sceneRect(from), rects, dir);
    return i < 0 ? nullptr : kept.at(i);
}

QQuickItem *KeyNav::first(QQuickItem *root) const
{
    if (!root)
        return nullptr;
    QList<QQuickItem *> stops;
    collectStops(*this, root, stops);
    QQuickItem *best = nullptr;
    QRectF bestRect;
    for (QQuickItem *s : stops) {
        const QRectF r = sceneRect(s);
        if (!best || r.top() < bestRect.top() - 1 || (std::abs(r.top() - bestRect.top()) <= 1 && r.left() < bestRect.left())) {
            best = s;
            bestRect = r;
        }
    }
    return best;
}

QQuickItem *KeyNav::last(QQuickItem *root) const
{
    if (!root)
        return nullptr;
    QList<QQuickItem *> stops;
    collectStops(*this, root, stops);
    QQuickItem *best = nullptr;
    QRectF bestRect;
    for (QQuickItem *s : stops) {
        const QRectF r = sceneRect(s);
        if (!best || r.bottom() > bestRect.bottom() + 1 || (std::abs(r.bottom() - bestRect.bottom()) <= 1 && r.right() > bestRect.right())) {
            best = s;
            bestRect = r;
        }
    }
    return best;
}

void KeyNav::reveal(QQuickItem *item) const
{
    if (!item)
        return;
    constexpr qreal margin = 24;
    for (QQuickItem *f = item->parentItem(); f; f = f->parentItem()) {
        if (!isFlickable(f))
            continue;
        const QRectF r = item->mapRectToItem(f, QRectF(0, 0, item->width(), item->height()));
        if (r.top() < margin)
            scrollBy(f, true, r.top() - margin);
        else if (r.bottom() > f->height() - margin)
            scrollBy(f, true, std::min(r.bottom() - f->height() + margin, r.top() - margin));
        if (r.left() < 0)
            scrollBy(f, false, r.left() - margin);
        else if (r.right() > f->width())
            scrollBy(f, false, std::min(r.right() - f->width() + margin, r.left() - margin));
    }
}

bool KeyNav::scrollToward(QQuickItem *from, int dir) const
{
    const bool vertical = dir >= 2;
    const qreal sign = (dir == 1 || dir == 3) ? 1 : -1;
    for (QQuickItem *f = from ? from->parentItem() : nullptr; f; f = f->parentItem()) {
        if (isFlickable(f) && scrollBy(f, vertical, sign * 0.8 * (vertical ? f->height() : f->width())))
            return true;
    }
    return false;
}

} // namespace pkm
