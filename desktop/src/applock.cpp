#include "applock.h"

#include "vaultcrypto.h"

#include <QDateTime>
#include <QEvent>
#include <QSettings>

#include <algorithm>

namespace pkm {

namespace {
constexpr auto kPinKey = "lock/pin";
constexpr auto kTimeoutKey = "lock/timeout_min";
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
} // namespace

AppLock::AppLock(QObject *parent)
    : QObject(parent)
{
    QSettings s;
    m_timeoutMin = s.value(QLatin1String(kTimeoutKey), 5).toInt();
    m_lastActivityMs = nowMs();
    m_locked = hasPin(); // with a PIN set the app always starts locked
    m_timer.setInterval(5000);
    connect(&m_timer, &QTimer::timeout, this, &AppLock::tick);
    m_timer.start();
}

bool AppLock::hasPin() const
{
    return !QSettings().value(QLatin1String(kPinKey)).toString().isEmpty();
}

void AppLock::setTimeoutMinutes(int minutes)
{
    minutes = std::clamp(minutes, 0, 240); // 0 = only on start / manual
    if (minutes == m_timeoutMin)
        return;
    m_timeoutMin = minutes;
    QSettings().setValue(QLatin1String(kTimeoutKey), minutes);
    emit configChanged();
}

int AppLock::backoffSeconds(int failures)
{
    if (failures < 3)
        return 0;
    return std::min(60, 1 << std::min(failures - 3, 6));
}

int AppLock::retryInSeconds() const
{
    return int(std::max<qint64>(0, (m_blockedUntilMs - nowMs() + 999) / 1000));
}

bool AppLock::setPin(const QString &newPin, const QString &current)
{
    if (newPin.trimmed().size() < 4)
        return false;
    if (hasPin() && !vault::verifyPin(current, QSettings().value(QLatin1String(kPinKey)).toString()))
        return false;
    const QString h = vault::hashPin(newPin);
    if (h.isEmpty())
        return false;
    QSettings().setValue(QLatin1String(kPinKey), h);
    emit configChanged();
    return true;
}

bool AppLock::clearPin(const QString &current)
{
    if (!hasPin())
        return true;
    if (!vault::verifyPin(current, QSettings().value(QLatin1String(kPinKey)).toString()))
        return false;
    QSettings().remove(QLatin1String(kPinKey));
    m_locked = false;
    emit lockedChanged();
    emit configChanged();
    return true;
}

bool AppLock::unlock(const QString &pin)
{
    if (!m_locked)
        return true;
    if (nowMs() < m_blockedUntilMs)
        return false;
    if (vault::verifyPin(pin, QSettings().value(QLatin1String(kPinKey)).toString())) {
        m_failures = 0;
        m_blockedUntilMs = 0;
        m_locked = false;
        m_lastActivityMs = nowMs();
        emit lockedChanged();
        return true;
    }
    ++m_failures;
    m_blockedUntilMs = nowMs() + qint64(backoffSeconds(m_failures)) * 1000;
    emit lockedChanged(); // refreshes retryInSeconds
    return false;
}

void AppLock::lockNow()
{
    if (!hasPin() || m_locked)
        return;
    m_locked = true;
    emit lockedChanged();
}

void AppLock::activity()
{
    if (!m_locked)
        m_lastActivityMs = nowMs();
}

void AppLock::tick()
{
    if (!m_locked && hasPin() && idleExpired(nowMs() - m_lastActivityMs, m_timeoutMin))
        lockNow();
}

bool AppLock::eventFilter(QObject *watched, QEvent *event)
{
    switch (event->type()) {
    case QEvent::KeyPress:
    case QEvent::MouseButtonPress:
    case QEvent::MouseMove:
    case QEvent::Wheel:
    case QEvent::TouchBegin:
        activity();
        break;
    default:
        break;
    }
    return QObject::eventFilter(watched, event);
}

} // namespace pkm
