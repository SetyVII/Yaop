#pragma once

// Watches input for the whole app (docs/keyboard.md §2.6, §8 F0):
// - `keyboardMode`: true after a key press, false after a click, so focus rings only show when
//   you navigate with the keyboard;
// - `--keylog`: prints every key press, which object it was delivered to and which Shortcut
//   fired, to see why a key "misses".

#include <QObject>

namespace pkm {

class KeyInput : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool keyboardMode READ keyboardMode NOTIFY keyboardModeChanged)
public:
    explicit KeyInput(bool log, QObject *parent = nullptr) : QObject(parent), m_log(log) {}

    bool keyboardMode() const { return m_keyboardMode; }
    // Mouse presses so far: coming back to the window by clicking somewhere wins over restoring
    // the caret where it was (Main.resumeFocus).
    Q_INVOKABLE int pressCount() const { return m_pressCount; }
    bool eventFilter(QObject *watched, QEvent *event) override;

signals:
    void keyboardModeChanged();

private:
    void setKeyboardMode(bool on);

    bool m_log = false;
    bool m_keyboardMode = false;
    int m_pressCount = 0;
};

} // namespace pkm
