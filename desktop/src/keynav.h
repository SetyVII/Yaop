#pragma once

// Arrow keys between the items of a page or the sidebar (docs/keyboard.md §5). Instead of a
// handler per list, every focusable item (`activeFocusOnTab` + `property bool focusRing: true`)
// is a stop, and an arrow moves to the nearest stop that way, as on a TV remote. Lists, grids,
// the day strip and the sidebar all behave the same with no code of their own.

#include <QList>
#include <QObject>
#include <QRectF>

class QQuickItem;

namespace pkm {

// dir: 0 left, 1 right, 2 up, 3 down (Main.qml uses the same numbers).
// Pure (selftest): index of the rect in `cands` nearest to `from` in that direction, or -1.
int pickNeighbor(const QRectF &from, const QList<QRectF> &cands, int dir);

class KeyNav : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;

    // True for an item arrows can stop on: a focusable control with a focus ring, or anything
    // clickable (a left-button TapHandler and a pointing-hand HoverHandler).
    Q_INVOKABLE bool isStop(QQuickItem *item) const;
    // Enter on a clickable stop: runs its TapHandler as a click would. False if it has none.
    Q_INVOKABLE bool activate(QQuickItem *item) const;
    // The nearest stop under `root` from `from` in `dir` (not inside `from`), or null.
    Q_INVOKABLE QQuickItem *neighbor(QQuickItem *root, QQuickItem *from, int dir) const;
    // The top-left / bottom-right stop under `root`, or null.
    Q_INVOKABLE QQuickItem *first(QQuickItem *root) const;
    Q_INVOKABLE QQuickItem *last(QQuickItem *root) const;
    // Scrolls every Flickable around `item` so it is in view.
    Q_INVOKABLE void reveal(QQuickItem *item) const;
    // Scrolls the nearest Flickable around `from` one page in `dir` (lazy ListView delegates
    // appear); false when it cannot scroll further.
    Q_INVOKABLE bool scrollToward(QQuickItem *from, int dir) const;
};

} // namespace pkm
