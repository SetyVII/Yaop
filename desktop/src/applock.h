#pragma once

// Local app lock (spec §15.5): a PIN gate in front of the UI, asked on start and after
// N idle minutes. The PIN is stored only as an argon2id string. Scope note: this guards the UI;
// the vault key still lives in the OS keychain (wrapping it under the PIN is a later step).

#include <QObject>
#include <QTimer>

namespace pkm {

class AppLock : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool locked READ locked NOTIFY lockedChanged)
    Q_PROPERTY(bool hasPin READ hasPin NOTIFY configChanged)
    Q_PROPERTY(int timeoutMinutes READ timeoutMinutes WRITE setTimeoutMinutes NOTIFY configChanged)
    Q_PROPERTY(int retryInSeconds READ retryInSeconds NOTIFY lockedChanged)
public:
    explicit AppLock(QObject *parent = nullptr);

    bool locked() const { return m_locked; }
    bool hasPin() const;
    int timeoutMinutes() const { return m_timeoutMin; }
    void setTimeoutMinutes(int minutes);
    int retryInSeconds() const;

    // Sets or replaces the PIN (4+ chars). When a PIN exists, `current` must verify.
    Q_INVOKABLE bool setPin(const QString &newPin, const QString &current = QString());
    // Removes the PIN (requires it).
    Q_INVOKABLE bool clearPin(const QString &current);
    Q_INVOKABLE bool unlock(const QString &pin);
    Q_INVOKABLE void lockNow();
    // Any key press / click: restarts the idle countdown.
    Q_INVOKABLE void activity();

    bool eventFilter(QObject *watched, QEvent *event) override;

    // Pure helper (selftest): has the idle time reached the limit?
    static bool idleExpired(qint64 idleMs, int timeoutMinutes) { return timeoutMinutes > 0 && idleMs >= qint64(timeoutMinutes) * 60000; }
    // Back-off after failed unlocks: 0 for the first 3, then 2^(n-3) s capped at 60.
    static int backoffSeconds(int failures);

signals:
    void lockedChanged();
    void configChanged();

private:
    void tick();

    bool m_locked = false;
    int m_timeoutMin = 5;
    qint64 m_lastActivityMs = 0;
    int m_failures = 0;
    qint64 m_blockedUntilMs = 0;
    QTimer m_timer;
};

} // namespace pkm
