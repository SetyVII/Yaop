#include "audioplayback.h"

#include <QFileInfo>

#include <QStandardPaths>

namespace pkm {

AudioPlayback::AudioPlayback(const QString &assetId, int durationSeconds, QObject *parent)
    : QObject(parent)
    , m_assetId(assetId)
    , m_durationSeconds(durationSeconds)
{
}

AudioPlayback::~AudioPlayback()
{
    stop();
}

bool AudioPlayback::playing() const
{
    return m_proc && m_proc->state() == QProcess::Running;
}

int AudioPlayback::durationSeconds() const
{
    return m_durationSeconds;
}

void AudioPlayback::setAssetId(const QString &id)
{
    if (m_assetId == id)
        return;
    m_assetId = id;
    emit assetIdChanged();
}

void AudioPlayback::setDuration(int seconds)
{
    if (m_durationSeconds == seconds)
        return;
    m_durationSeconds = seconds;
    emit durationChanged();
}

void AudioPlayback::setPath(const QString &path)
{
    if (m_path == path)
        return;
    m_path = path;
    emit pathChanged();
    if (m_path.isEmpty())
        return;
    if (m_durationSeconds <= 0 && QFileInfo::exists(m_path)) {
        QProcess probe;
        probe.start(QStringLiteral("ffprobe"),
                    { QStringLiteral("-v"), QStringLiteral("error"),
                      QStringLiteral("-show_entries"), QStringLiteral("format=duration"),
                      QStringLiteral("-of"), QStringLiteral("default=noprint_wrappers=1:nokey=1"),
                      m_path });
        if (probe.waitForFinished(4000)) {
            const double secs = QString::fromUtf8(probe.readAllStandardOutput()).trimmed().toDouble();
            if (secs > 0) {
                m_durationSeconds = int(secs);
                emit durationChanged();
            }
        }
    }
}

static int activeCount = 0;

void AudioPlayback::startProcess(int fromSeconds)
{
    stop();
    if (m_path.isEmpty() || !QFileInfo::exists(m_path))
        return;
    auto *proc = new QProcess(this);
    QStringList args{ QStringLiteral("-nodisp"), QStringLiteral("-autoexit"),
                      QStringLiteral("-loglevel"), QStringLiteral("quiet") };
    if (fromSeconds > 0)
        args += { QStringLiteral("-ss"), QString::number(fromSeconds) };
    args += { m_path };
    proc->start(QStringLiteral("ffplay"), args);
    connect(proc, &QProcess::finished, this, [this]() {
        if (m_proc) {
            m_proc->deleteLater();
            m_proc = nullptr;
        }
        emit playingChanged();
        emit finished();
    });
    m_proc = proc;
    emit playingChanged();
}

void AudioPlayback::play()
{
    if (playing())
        return;
    ++activeCount;
    startProcess(0);
}

void AudioPlayback::pause()
{
    stop();
}

void AudioPlayback::stop()
{
    if (m_proc) {
        disconnect(m_proc, nullptr, this, nullptr);
        m_proc->terminate();
        if (!m_proc->waitForFinished(1000))
            m_proc->kill();
        m_proc->deleteLater();
        m_proc = nullptr;
        emit playingChanged();
    }
    if (activeCount > 0)
        --activeCount;
}

void AudioPlayback::seek(int seconds)
{
    if (m_path.isEmpty())
        return;
    startProcess(qMax(0, seconds));
}

} // namespace pkm
