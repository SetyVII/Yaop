#pragma once

// CaptureBridge: local HTTP capture endpoint on 127.0.0.1 with bearer-token auth
// for the browser extension (product §22.5).
//
// Security boundaries:
// - Binds strictly to QHostAddress::LocalHost (127.0.0.1).
// - Rejects requests carrying an Origin header from regular web pages (only browser extension
//   origins chrome-extension://, moz-extension://, safari-web-extension:// or no Origin).
// - Requires Authorization: Bearer <token> checked in constant time on every endpoint.

#include <QObject>
#include <QString>
#include <QTcpServer>

namespace pkm {

class Workspace;

class CaptureBridge : public QObject {
    Q_OBJECT

    Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY stateChanged)
    Q_PROPERTY(bool running READ running NOTIFY stateChanged)
    Q_PROPERTY(int port READ port WRITE setPort NOTIFY stateChanged)
    Q_PROPERTY(int listenPort READ listenPort NOTIFY stateChanged)
    Q_PROPERTY(QString token READ token NOTIFY stateChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY stateChanged)

public:
    static constexpr int kDefaultPort = 47192;

    explicit CaptureBridge(Workspace *workspace, QObject *parent = nullptr);
    ~CaptureBridge() override;

    // Start listening on 127.0.0.1 (using configured port, or overridePort >= 0 for tests).
    bool start(int overridePort = -1);
    void stop();

    bool enabled() const { return m_enabled; }
    void setEnabled(bool on);
    bool running() const { return m_server.isListening(); }
    int port() const { return m_port; }
    void setPort(int port);
    int listenPort() const { return m_server.isListening() ? int(m_server.serverPort()) : 0; }
    QString token() const { return m_token; }
    QString lastError() const { return m_lastError; }

    Q_INVOKABLE void regenerateToken();

    // Pure security helpers (tested directly in --selftest).
    static bool isAllowedOrigin(const QString &origin);
    static bool constantTimeEquals(const QByteArray &a, const QByteArray &b);

signals:
    void stateChanged();

private:
    void loadOrCreateToken();
    void saveToken(const QString &tok);
    void onNewConnection();
    void handleRequest(class QTcpSocket *socket, const QByteArray &method, const QByteArray &target,
                       const QHash<QByteArray, QByteArray> &headers, const QByteArray &body);
    void sendJson(class QTcpSocket *socket, int status, const QByteArray &reason,
                  const QJsonObject &body, const QByteArray &corsOrigin = QByteArray());
    void sendEmpty(class QTcpSocket *socket, int status, const QByteArray &reason,
                   const QByteArray &corsOrigin = QByteArray());

    Workspace *m_workspace = nullptr;
    QTcpServer m_server;
    bool m_enabled = true;
    int m_port = kDefaultPort;
    QString m_token;
    QString m_lastError;
};

} // namespace pkm
