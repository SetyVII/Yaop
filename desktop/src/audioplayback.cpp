#include "audioplayback.h"

#include <QFileInfo>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace pkm {

namespace {
QPointer<AudioPlayback> g_current; // the one playing now
}

AudioPlayback::AudioPlayback(const QString &assetId, int durationSeconds, QObject *parent)
    : QObject(parent)
    , m_assetId(assetId)
    , m_durationSeconds(durationSeconds)
{
    m_tick.setInterval(100);
    connect(&m_tick, &QTimer::timeout, this, [this]() {
        emit positionChanged();
        // ffplay -autoexit ends the process; this keeps the bar honest if it lingers.
        if (m_durationSeconds > 0 && position() >= m_durationSeconds + 1)
            stop();
    });
}

AudioPlayback::~AudioPlayback()
{
    killProcess();
    if (m_peakProc)
        m_peakProc->kill();
}

double AudioPlayback::position() const
{
    double p = m_basePos + (m_playing && m_clock.isValid() ? m_clock.elapsed() / 1000.0 : 0.0);
    if (m_durationSeconds > 0)
        p = std::min(p, double(m_durationSeconds));
    return p;
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
    if (m_durationSeconds == seconds || seconds <= 0)
        return;
    m_durationSeconds = seconds;
    emit durationChanged();
}

void AudioPlayback::setPeakCount(int n)
{
    n = std::clamp(n, 8, 400);
    if (n == m_peakCount)
        return;
    m_peakCount = n;
    m_peaks.clear();
    emit peaksChanged();
    loadPeaks();
}

void AudioPlayback::setPath(const QString &path)
{
    if (m_path == path)
        return;
    stop();
    m_path = path;
    m_peaks.clear();
    emit pathChanged();
    emit peaksChanged();
    if (m_path.isEmpty() || !QFileInfo::exists(m_path))
        return;
    probeDuration();
    loadPeaks();
}

void AudioPlayback::probeDuration()
{
    if (m_durationSeconds > 0)
        return;
    auto *probe = new QProcess(this);
    connect(probe, &QProcess::finished, this, [this, probe]() {
        const double secs = QString::fromUtf8(probe->readAllStandardOutput()).trimmed().toDouble();
        probe->deleteLater();
        if (secs > 0)
            setDuration(int(std::lround(secs)));
    });
    connect(probe, &QProcess::errorOccurred, probe, &QObject::deleteLater);
    probe->start(QStringLiteral("ffprobe"),
                 { QStringLiteral("-v"), QStringLiteral("error"),
                   QStringLiteral("-show_entries"), QStringLiteral("format=duration"),
                   QStringLiteral("-of"), QStringLiteral("default=noprint_wrappers=1:nokey=1"), m_path });
}

QVariantList AudioPlayback::peaksFromPcm(const QByteArray &pcm, int count)
{
    QVariantList out;
    const qsizetype samples = pcm.size() / 2;
    if (count <= 0 || samples <= 0)
        return out;
    const auto *data = reinterpret_cast<const unsigned char *>(pcm.constData());
    QList<double> raw;
    raw.reserve(count);
    double top = 0;
    for (int b = 0; b < count; ++b) {
        const qsizetype from = samples * b / count;
        const qsizetype to = std::max(from + 1, samples * (b + 1) / count);
        double sumSq = 0;
        for (qsizetype i = from; i < to && i < samples; ++i) {
            int16_t v;
            std::memcpy(&v, data + i * 2, 2);
            sumSq += double(v) * double(v);
        }
        const double rms = std::sqrt(sumSq / double(to - from));
        raw << rms;
        top = std::max(top, rms);
    }
    for (double r : raw)
        out << (top > 0 ? std::clamp(r / top, 0.0, 1.0) : 0.0);
    return out;
}

void AudioPlayback::loadPeaks()
{
    if (m_path.isEmpty() || !QFileInfo::exists(m_path))
        return;
    if (m_peakProc)
        m_peakProc->kill();
    // 2 kHz mono PCM is plenty for a 48-bar waveform and stays small for long recordings.
    auto *proc = new QProcess(this);
    m_peakProc = proc;
    connect(proc, &QProcess::finished, this, [this, proc]() {
        const QByteArray pcm = proc->readAllStandardOutput();
        proc->deleteLater();
        if (proc != m_peakProc)
            return;
        m_peaks = peaksFromPcm(pcm, m_peakCount);
        emit peaksChanged();
    });
    connect(proc, &QProcess::errorOccurred, proc, &QObject::deleteLater);
    proc->start(QStringLiteral("ffmpeg"),
                { QStringLiteral("-v"), QStringLiteral("quiet"), QStringLiteral("-i"), m_path,
                  QStringLiteral("-ac"), QStringLiteral("1"), QStringLiteral("-ar"), QStringLiteral("2000"),
                  QStringLiteral("-f"), QStringLiteral("s16le"), QStringLiteral("-") });
}

void AudioPlayback::setPlaying(bool on)
{
    if (m_playing == on)
        return;
    m_playing = on;
    if (on) {
        m_clock.start();
        m_tick.start();
    } else {
        m_tick.stop();
    }
    emit playingChanged();
    emit positionChanged();
}

void AudioPlayback::killProcess()
{
    if (!m_proc)
        return;
    QProcess *p = m_proc;
    m_proc = nullptr;
    disconnect(p, nullptr, this, nullptr);
    p->terminate();
    if (!p->waitForFinished(500))
        p->kill();
    p->deleteLater();
}

void AudioPlayback::startProcess(double fromSeconds)
{
    killProcess();
    if (m_path.isEmpty() || !QFileInfo::exists(m_path)) {
        emit failed(QStringLiteral("missing"));
        return;
    }
    if (g_current && g_current != this)
        g_current->pause();
    g_current = this;

    auto *proc = new QProcess(this);
    QStringList args{ QStringLiteral("-nodisp"), QStringLiteral("-autoexit"),
                      QStringLiteral("-loglevel"), QStringLiteral("quiet") };
    if (fromSeconds > 0.05)
        args += { QStringLiteral("-ss"), QString::number(fromSeconds, 'f', 2) };
    args += { m_path };
    connect(proc, &QProcess::finished, this, [this, proc]() {
        if (proc != m_proc)
            return;
        m_proc = nullptr;
        proc->deleteLater();
        m_basePos = 0;
        setPlaying(false);
        emit finished();
    });
    connect(proc, &QProcess::errorOccurred, this, [this, proc, fromSeconds](QProcess::ProcessError e) {
        if (e != QProcess::FailedToStart || proc != m_proc)
            return;
        m_proc = nullptr;
        proc->deleteLater();
        m_basePos = fromSeconds;
        setPlaying(false);
        emit failed(QStringLiteral("ffplay"));
    });
    m_proc = proc;
    m_basePos = fromSeconds;
    proc->start(QStringLiteral("ffplay"), args);
    m_playing = false; // restart the clock even when already playing (seek)
    setPlaying(true);
}

void AudioPlayback::play()
{
    if (m_playing)
        return;
    double from = m_basePos;
    if (m_durationSeconds > 0 && from >= m_durationSeconds - 0.25)
        from = 0;
    startProcess(from);
}

void AudioPlayback::pause()
{
    if (!m_playing)
        return;
    m_basePos = position();
    killProcess();
    setPlaying(false);
}

void AudioPlayback::toggle()
{
    if (m_playing)
        pause();
    else
        play();
}

void AudioPlayback::stop()
{
    killProcess();
    m_basePos = 0;
    setPlaying(false);
    emit positionChanged();
}

void AudioPlayback::seek(double seconds)
{
    seconds = std::max(0.0, seconds);
    if (m_durationSeconds > 0)
        seconds = std::min(seconds, double(m_durationSeconds));
    if (m_playing) {
        startProcess(seconds);
    } else {
        m_basePos = seconds;
        emit positionChanged();
    }
}

void AudioPlayback::seekFraction(double f)
{
    if (m_durationSeconds > 0)
        seek(std::clamp(f, 0.0, 1.0) * m_durationSeconds);
}

} // namespace pkm
