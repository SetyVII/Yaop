#include "audiorecorder.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>

namespace pkm {

AudioRecorder::AudioRecorder(QObject *parent)
    : QObject(parent)
{
    m_tick.setInterval(500);
    connect(&m_tick, &QTimer::timeout, this, &AudioRecorder::secondsChanged);
}

AudioRecorder::~AudioRecorder()
{
    if (m_proc) {
        m_proc->kill();
        m_proc->waitForFinished(1000);
    }
    if (!m_path.isEmpty())
        QFile::remove(m_path);
}

void AudioRecorder::start()
{
    if (m_proc)
        return;
    m_path = QDir::tempPath() + QStringLiteral("/yaop-rec-")
        + QString::number(QDateTime::currentMSecsSinceEpoch()) + QStringLiteral(".ogg");
    m_keep = false;
    auto *proc = new QProcess(this);
    m_proc = proc;
    connect(proc, &QProcess::errorOccurred, this, [this, proc](QProcess::ProcessError e) {
        if (e != QProcess::FailedToStart || proc != m_proc)
            return;
        m_proc = nullptr;
        proc->deleteLater();
        m_tick.stop();
        m_clock.invalidate();
        emit recordingChanged();
        emit failed(QStringLiteral("ffmpeg"));
    });
    connect(proc, &QProcess::finished, this, [this, proc](int code) {
        if (proc != m_proc)
            return;
        m_proc = nullptr;
        proc->deleteLater();
        const int secs = seconds();
        m_tick.stop();
        m_clock.invalidate();
        emit recordingChanged();
        const QString path = m_path;
        m_path.clear();
        const bool ok = QFileInfo(path).size() > 0;
        if (m_keep && ok)
            emit recorded(path, secs);
        else if (m_keep || code != 0)
            emit failed(QStringLiteral("input"));
        QFile::remove(path);
    });
    // "q" on stdin makes ffmpeg close the file properly (a kill would leave it truncated).
    proc->start(QStringLiteral("ffmpeg"),
                { QStringLiteral("-hide_banner"), QStringLiteral("-loglevel"), QStringLiteral("error"),
                  QStringLiteral("-f"), QStringLiteral("pulse"), QStringLiteral("-i"), QStringLiteral("default"),
                  QStringLiteral("-ac"), QStringLiteral("1"), QStringLiteral("-c:a"), QStringLiteral("libopus"),
                  QStringLiteral("-b:a"), QStringLiteral("48k"), QStringLiteral("-y"), m_path });
    m_clock.start();
    m_tick.start();
    emit recordingChanged();
    emit secondsChanged();
}

void AudioRecorder::stop() { finish(true); }
void AudioRecorder::cancel() { finish(false); }

void AudioRecorder::finish(bool keep)
{
    if (!m_proc)
        return;
    m_keep = keep;
    if (keep) {
        m_proc->write("q");
        m_proc->closeWriteChannel();
        QPointer<QProcess> p = m_proc;
        QTimer::singleShot(3000, this, [p]() { if (p) p->kill(); });
    } else {
        m_proc->kill();
    }
}

} // namespace pkm
