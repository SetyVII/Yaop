#include "keyinput.h"

#include <QKeyEvent>
#include <QKeySequence>
#include <QQuickItem>
#include <QShortcutEvent>
#include <cstdio>

namespace pkm {

namespace {

QString describe(QObject *o)
{
    if (!o)
        return QStringLiteral("-");
    QString s = QString::fromLatin1(o->metaObject()->className());
    if (!o->objectName().isEmpty())
        s += QLatin1Char('#') + o->objectName();
    if (auto *item = qobject_cast<QQuickItem *>(o); item && item->parentItem())
        s += QStringLiteral(" in ") + QString::fromLatin1(item->parentItem()->metaObject()->className());
    return s;
}

QString keyText(const QKeyEvent *e)
{
    const QString k = QKeySequence(e->keyCombination()).toString(QKeySequence::PortableText);
    return k.isEmpty() ? QStringLiteral("key 0x%1").arg(e->key(), 0, 16) : k;
}

bool isModifierKey(const QEvent *event)
{
    const int k = static_cast<const QKeyEvent *>(event)->key();
    return k == Qt::Key_Control || k == Qt::Key_Shift || k == Qt::Key_Alt || k == Qt::Key_Meta
           || k == Qt::Key_AltGr || k == Qt::Key_Super_L || k == Qt::Key_Super_R;
}

} // namespace

bool KeyInput::eventFilter(QObject *watched, QEvent *event)
{
    if ((event->type() == QEvent::KeyPress || event->type() == QEvent::ShortcutOverride)
            && isModifierKey(event))
        return false;   // Ctrl/Shift/Alt alone: not navigation, and noise in the log
    switch (event->type()) {
    case QEvent::KeyPress:
        setKeyboardMode(true);
        if (m_log)
            std::fprintf(stderr, "KEY press    %-18s → %s\n",
                         qPrintable(keyText(static_cast<QKeyEvent *>(event))), qPrintable(describe(watched)));
        break;
    case QEvent::ShortcutOverride:
        if (m_log)
            std::fprintf(stderr, "KEY override %-18s → %s\n",
                         qPrintable(keyText(static_cast<QKeyEvent *>(event))), qPrintable(describe(watched)));
        break;
    case QEvent::Shortcut:
        if (m_log)
            std::fprintf(stderr, "KEY shortcut %-18s → %s\n",
                         qPrintable(static_cast<QShortcutEvent *>(event)->key().toString(QKeySequence::PortableText)),
                         qPrintable(describe(watched)));
        break;
    case QEvent::MouseButtonPress:
        setKeyboardMode(false);
        break;
    default:
        break;
    }
    return false;
}

void KeyInput::setKeyboardMode(bool on)
{
    if (m_keyboardMode == on)
        return;
    m_keyboardMode = on;
    emit keyboardModeChanged();
}

} // namespace pkm
