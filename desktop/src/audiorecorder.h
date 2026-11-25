#pragma once

// AudioRecorder: ffmpeg-backed microphone recording (no QtMultimedia), like AudioPlayback.
// start() records the default input (PulseAudio/PipeWire) to a temporary Opus file; stop() ends it
// cleanly and emits recorded(path, seconds). The temp file is removed right after the signal:
// handlers import it synchronously.

#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QProcess>
#include <QTimer>

namespace pkm {

class AudioRecorder : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool recording READ recording NOTIFY recordingChanged)
    Q_PROPERTY(int seconds READ seconds NOTIFY secondsChanged)
public:
    explicit AudioRecorder(QObject *parent = nullptr);
    ~AudioRecorder() override;

    bool recording() const { return m_proc != nullptr; }
    int seconds() const { return m_clock.isValid() ? int(m_clock.elapsed() / 1000) : 0; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();   // keep the take
    Q_INVOKABLE void cancel(); // drop it

signals:
    void recordingChanged();
    void secondsChanged();
    void recorded(const QString &path, int seconds);
    void failed(const QString &reason); // "ffmpeg" (not installed) or "input" (no microphone)

private:
    void finish(bool keep);

    QPointer<QProcess> m_proc;
    QString m_path;
    QElapsedTimer m_clock;
    QTimer m_tick;
    bool m_keep = false;
    bool m_cancelled = false;
};

} // namespace pkm
