#pragma once

// AudioPlayback: ffplay-backed playback for audio blocks and audio objects (no QtMultimedia).
// Keeps its own position so pause resumes where it stopped; only one plays at a time.
// `peaks` holds a 0..1 waveform read once from the file with ffmpeg, in the background.

#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QProcess>
#include <QTimer>
#include <QVariantList>

namespace pkm {

class AudioPlayback : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool playing READ playing NOTIFY playingChanged)
    Q_PROPERTY(QString assetId READ assetId WRITE setAssetId NOTIFY assetIdChanged)
    Q_PROPERTY(QString path READ path WRITE setPath NOTIFY pathChanged)
    Q_PROPERTY(int durationSeconds READ durationSeconds WRITE setDuration NOTIFY durationChanged)
    Q_PROPERTY(double position READ position NOTIFY positionChanged)
    Q_PROPERTY(QVariantList peaks READ peaks NOTIFY peaksChanged)
    Q_PROPERTY(int peakCount READ peakCount WRITE setPeakCount NOTIFY peaksChanged)
public:
    explicit AudioPlayback(const QString &assetId = QString(), int durationSeconds = 0, QObject *parent = nullptr);
    ~AudioPlayback() override;

    bool playing() const { return m_playing; }
    int durationSeconds() const { return m_durationSeconds; }
    double position() const;
    QVariantList peaks() const { return m_peaks; }
    int peakCount() const { return m_peakCount; }
    QString assetId() const { return m_assetId; }
    QString path() const { return m_path; }
    void setAssetId(const QString &id);
    void setDuration(int seconds);
    void setPath(const QString &path);
    void setPeakCount(int n);

    Q_INVOKABLE void play();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void toggle();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void seek(double seconds);
    Q_INVOKABLE void seekFraction(double f);

    // Pure: 0..1 peaks of signed 16-bit mono samples split into `count` buckets.
    static QVariantList peaksFromPcm(const QByteArray &pcm, int count);

signals:
    void playingChanged();
    void finished();
    void assetIdChanged();
    void pathChanged();
    void durationChanged();
    void positionChanged();
    void peaksChanged();
    void failed(const QString &reason);

private:
    void startProcess(double fromSeconds);
    void killProcess();
    void setPlaying(bool on);
    void probeDuration();
    void loadPeaks();

    QString m_assetId;
    QString m_path;
    int m_durationSeconds = 0;
    int m_peakCount = 48;
    bool m_playing = false;
    double m_basePos = 0;      // position when the current run started (or the paused position)
    QElapsedTimer m_clock;     // runs while playing
    QTimer m_tick;
    QProcess *m_proc = nullptr;
    QPointer<QProcess> m_peakProc;
    QVariantList m_peaks;
};

} // namespace pkm
