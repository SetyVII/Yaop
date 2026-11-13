#pragma once

// AudioPlayback: ffplay-backed playback for audio blocks (no QtMultimedia).
// One AudioPlayback instance per audio block delegate.

#include <QObject>
#include <QProcess>

namespace pkm {

class AudioPlayback : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool playing READ playing NOTIFY playingChanged)
    Q_PROPERTY(QString assetId READ assetId WRITE setAssetId NOTIFY assetIdChanged)
    Q_PROPERTY(QString path READ path WRITE setPath NOTIFY pathChanged)
        Q_PROPERTY(int durationSeconds READ durationSeconds WRITE setDuration NOTIFY durationChanged)
public:
    explicit AudioPlayback(const QString &assetId = QString(), int durationSeconds = 0, QObject *parent = nullptr);
    ~AudioPlayback() override;

    bool playing() const;
    int durationSeconds() const;
    QString assetId() const { return m_assetId; }
    QString path() const { return m_path; }
    void setAssetId(const QString &id);
    void setDuration(int seconds);
    void setPath(const QString &path);

    Q_INVOKABLE void play();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void seek(int seconds);

signals:
    void playingChanged();
    void finished();
    void assetIdChanged();
    void pathChanged();
    void durationChanged();

private:
    void startProcess(int fromSeconds);

    QString m_assetId;
    QString m_path;
    int m_durationSeconds = 0;
    QProcess *m_proc = nullptr;
};

} // namespace pkm
