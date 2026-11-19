#include "keymap.h"

#include <QCoreApplication>
#include <QHash>
#include <QKeySequence>
#include <QVariantMap>

namespace pkm {

namespace {

KeyBinding bind(const char *id, QStringList keys, const char *scope, const char *title)
{
    return { QString::fromLatin1(id), std::move(keys), QString::fromLatin1(scope),
             QString::fromLatin1(title) };
}

KeyBinding slate(KeyBinding b) { b.slateOnly = true; return b; }
KeyBinding appWide(KeyBinding b) { b.appWide = true; return b; }

std::vector<KeyBinding> buildBindings()
{
    std::vector<KeyBinding> t = {
        // ---- global (Main.qml)
        bind("palette", { "Ctrl+K", "Ctrl+P" }, "global", QT_TRANSLATE_NOOP("Keymap", "Search and commands")),
        bind("capture", { "Ctrl+N" }, "global", QT_TRANSLATE_NOOP("Keymap", "Quick capture")),
        bind("today", { "Ctrl+D", "Alt+Home" }, "global", QT_TRANSLATE_NOOP("Keymap", "Today")),
        bind("page.home", { "Ctrl+1" }, "global", QT_TRANSLATE_NOOP("Keymap", "Home")),
        bind("page.journal", { "Ctrl+2", "Ctrl+J" }, "global", QT_TRANSLATE_NOOP("Keymap", "Journal")),
        bind("page.library", { "Ctrl+3", "Ctrl+L" }, "global", QT_TRANSLATE_NOOP("Keymap", "Library")),
        bind("page.whiteboard", { "Ctrl+4" }, "global", QT_TRANSLATE_NOOP("Keymap", "Whiteboard")),
        bind("page.tasks", { "Ctrl+5" }, "global", QT_TRANSLATE_NOOP("Keymap", "Tasks")),
        bind("back", { "Alt+Left" }, "global", QT_TRANSLATE_NOOP("Keymap", "Back")),
        bind("forward", { "Alt+Right" }, "global", QT_TRANSLATE_NOOP("Keymap", "Forward")),
        slate(bind("tab.new", { "Ctrl+T" }, "global", QT_TRANSLATE_NOOP("Keymap", "New tab"))),
        slate(bind("tab.close", { "Ctrl+W" }, "global", QT_TRANSLATE_NOOP("Keymap", "Close tab"))),
        slate(bind("tab.next", { "Ctrl+Tab", "Ctrl+PgDown" }, "global", QT_TRANSLATE_NOOP("Keymap", "Next tab"))),
        slate(bind("tab.prev", { "Ctrl+Shift+Tab", "Ctrl+PgUp" }, "global", QT_TRANSLATE_NOOP("Keymap", "Previous tab"))),
        bind("sidebar", { "Ctrl+B" }, "global", QT_TRANSLATE_NOOP("Keymap", "Show or hide the sidebar")),
        bind("settings", { "Ctrl+," }, "global", QT_TRANSLATE_NOOP("Keymap", "Settings")),
        bind("lock", { "Ctrl+Shift+L" }, "global", QT_TRANSLATE_NOOP("Keymap", "Lock")),
        appWide(bind("privacy", { "Ctrl+Shift+H" }, "global", QT_TRANSLATE_NOOP("Keymap", "Hide private content"))),

        // ---- editor: caret in a text block (TextBlockView)
        bind("editor.bold", { "Ctrl+B" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Bold")),
        bind("editor.italic", { "Ctrl+I" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Italic")),
        bind("editor.underline", { "Ctrl+U" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Underline")),
        bind("editor.code", { "Ctrl+E" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Inline code")),
        bind("editor.duplicate", { "Ctrl+D" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Duplicate block")),
        bind("editor.selectAll", { "Ctrl+A" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Select all (twice: all blocks)")),
        bind("editor.indent", { "Tab" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Indent")),
        bind("editor.outdent", { "Shift+Tab" }, "editor", QT_TRANSLATE_NOOP("Keymap", "Outdent")),

        // ---- blocks: whole blocks selected (BlocksView)
        bind("blocks.copy", { "Ctrl+C" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Copy blocks")),
        bind("blocks.cut", { "Ctrl+X" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Cut blocks")),
        bind("blocks.paste", { "Ctrl+V" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Paste")),
        bind("blocks.selectAll", { "Ctrl+A" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Select all blocks")),
        bind("blocks.duplicate", { "Ctrl+D" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Duplicate blocks")),
        bind("blocks.extendUp", { "Shift+Up" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Extend selection up")),
        bind("blocks.extendDown", { "Shift+Down" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Extend selection down")),
        bind("blocks.indent", { "Tab" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Indent")),
        bind("blocks.outdent", { "Shift+Tab" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Outdent")),
        bind("blocks.delete", { "Del", "Backspace" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Delete blocks")),
        bind("blocks.clear", { "Esc" }, "blocks", QT_TRANSLATE_NOOP("Keymap", "Clear selection")),
    };
    // Alt+1…9: go to tab N (Firefox on Linux).
    for (int n = 1; n <= 9; ++n) {
        KeyBinding b = slate(bind("", { QStringLiteral("Alt+%1").arg(n) }, "global",
                                  QT_TRANSLATE_NOOP("Keymap", "Go to tab %1")));
        b.id = QStringLiteral("tab.%1").arg(n);
        b.arg = n;
        t.push_back(b);
    }
    return t;
}

QString normalized(const QString &keys)
{
    return QKeySequence::fromString(keys, QKeySequence::PortableText).toString(QKeySequence::PortableText);
}

QString translatedTitle(const KeyBinding &b)
{
    const QString t = QCoreApplication::translate("Keymap", b.title.toLatin1().constData());
    return b.arg > 0 ? t.arg(b.arg) : t;
}

} // namespace

const std::vector<KeyBinding> &keyBindings()
{
    static const std::vector<KeyBinding> table = buildBindings();
    return table;
}

QString findKeyConflict(const std::vector<KeyBinding> &bindings)
{
    QHash<QString, QString> seen;   // "scope|keys" -> id
    for (const KeyBinding &b : bindings) {
        for (const QString &k : b.keys) {
            const QString n = normalized(k);
            if (n.isEmpty())
                return QStringLiteral("%1: unparsable keys \"%2\" (%3)").arg(b.scope, k, b.id);
            const QString slot = b.scope + QLatin1Char('|') + n;
            const auto it = seen.constFind(slot);
            if (it != seen.constEnd())
                return QStringLiteral("%1: %2 (%3, %4)").arg(b.scope, n, it.value(), b.id);
            seen.insert(slot, b.id);
        }
    }
    return {};
}

QVariantList Keymap::bindings(const QString &scope) const
{
    QVariantList out;
    for (const KeyBinding &b : keyBindings()) {
        if (b.scope != scope)
            continue;
        out.append(QVariantMap {
            { QStringLiteral("id"), b.id },
            { QStringLiteral("keys"), b.keys },
            { QStringLiteral("title"), translatedTitle(b) },
            { QStringLiteral("slateOnly"), b.slateOnly },
            { QStringLiteral("appWide"), b.appWide },
        });
    }
    return out;
}

bool Keymap::owns(const QString &scope, int key, int modifiers) const
{
    Qt::KeyboardModifiers mods = Qt::KeyboardModifiers(modifiers) & ~Qt::KeypadModifier;
    if (key == Qt::Key_Backtab) {   // Shift+Tab arrives as Backtab
        key = Qt::Key_Tab;
        mods |= Qt::ShiftModifier;
    }
    const QKeySequence pressed(QKeyCombination(mods, Qt::Key(key)));
    for (const KeyBinding &b : keyBindings()) {
        if (b.scope != scope)
            continue;
        for (const QString &k : b.keys) {
            if (QKeySequence::fromString(k, QKeySequence::PortableText) == pressed)
                return true;
        }
    }
    return false;
}

QString Keymap::keysText(const QString &id) const
{
    for (const KeyBinding &b : keyBindings()) {
        if (b.id == id && !b.keys.isEmpty())
            return QKeySequence::fromString(b.keys.first(), QKeySequence::PortableText)
                .toString(QKeySequence::NativeText);
    }
    return {};
}

} // namespace pkm
