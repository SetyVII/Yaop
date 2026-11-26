#pragma once

// One table for every keyboard shortcut (docs/keyboard.md §2.5). Main.qml builds its global
// Shortcuts from it; editor items ask `owns()` so their keys win over a global one (Ctrl+D =
// duplicate in the editor, Today elsewhere). The shortcut sheet (ShortcutSheet.qml) reads it too.

#include <QObject>
#include <QString>
#include <QStringList>
#include <QVariantList>
#include <vector>

namespace pkm {

struct KeyBinding {
    QString id;        // action id, e.g. "today", "tab.3"
    QStringList keys;  // portable text, e.g. "Ctrl+K"
    QString scope;     // global | editor | blocks | list
    QString title;     // untranslated; context "Keymap"
    int arg = 0;       // fills %1 in the title (tab number)
    bool slateOnly = false;
    bool appWide = false;   // Qt.ApplicationShortcut (works over popups too)
};

const std::vector<KeyBinding> &keyBindings();

// Pure (selftest): "scope: keys (a, b)" for the first key bound twice in one scope, or empty.
QString findKeyConflict(const std::vector<KeyBinding> &bindings);

class Keymap : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;

    // [{ id, keys, labels (native text), title, slateOnly, appWide }] for one scope.
    Q_INVOKABLE QVariantList bindings(const QString &scope) const;
    // True when `scope` binds this key press (call from Keys.onShortcutOverride).
    Q_INVOKABLE bool owns(const QString &scope, int key, int modifiers) const;
    // Native text of an action's first key ("Ctrl+K"), for hints in menus and the palette.
    Q_INVOKABLE QString keysText(const QString &id) const;
};

} // namespace pkm
