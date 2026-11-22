#include "capturebridge.h"
#include "cachecrypto.h"
#include "vaultcrypto.h"
#include "workspace.h"

#include <QHash>
#include <QHostAddress>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <QTcpSocket>
#include <QTimer>
#include <QUrl>
#include <QUrlQuery>
#include <sodium.h>

namespace pkm {

namespace {

constexpr qsizetype kMaxHeaderBytes = 16 * 1024;
constexpr qsizetype kMaxDefaultBodyBytes = 512 * 1024;
constexpr qsizetype kMaxWebLinkBodyBytes = 4 * 1024 * 1024;
constexpr qsizetype kMaxImageBodyBytes = 16 * 1024 * 1024;
constexpr int kConnTimeoutMs = 10000;

qsizetype maxBodyForPath(const QByteArray &path)
{
    if (path == "/v1/capture/image")
        return kMaxImageBodyBytes;
    if (path == "/v1/capture/weblink")
        return kMaxWebLinkBodyBytes;
    return kMaxDefaultBodyBytes;
}

} // namespace

CaptureBridge::CaptureBridge(Workspace *workspace, QObject *parent)
    : QObject(parent)
    , m_workspace(workspace)
{
    QSettings s;
    m_enabled = s.value(QStringLiteral("capture/enabled"), true).toBool();
    const int savedPort = s.value(QStringLiteral("capture/port"), kDefaultPort).toInt();
    m_port = (savedPort >= 1024 && savedPort <= 65535) ? savedPort : kDefaultPort;
    loadOrCreateToken();
    connect(&m_server, &QTcpServer::newConnection, this, &CaptureBridge::onNewConnection);
}

CaptureBridge::~CaptureBridge()
{
    stop();
}

void CaptureBridge::loadOrCreateToken()
{
    const QString scope = m_workspace ? m_workspace->secretScope() : QStringLiteral("default");
    QString tok = readSecret(QStringLiteral("capture-token:") + scope).trimmed();
    if (tok.isEmpty()) {
        QSettings s;
        tok = s.value(QStringLiteral("capture/token")).toString().trimmed();
    }
    if (tok.size() < 32) {
        tok = QString::fromLatin1(vault::randomBytes(32).toHex());
        saveToken(tok);
    }
    m_token = tok;
}

void CaptureBridge::saveToken(const QString &tok)
{
    const QString scope = m_workspace ? m_workspace->secretScope() : QStringLiteral("default");
    QSettings s;
    if (writeSecret(QStringLiteral("capture-token:") + scope, tok)) {
        s.remove(QStringLiteral("capture/token"));
    } else {
        s.setValue(QStringLiteral("capture/token"), tok);
    }
}

void CaptureBridge::regenerateToken()
{
    m_token = QString::fromLatin1(vault::randomBytes(32).toHex());
    saveToken(m_token);
    emit stateChanged();
}

bool CaptureBridge::start(int overridePort)
{
    stop();
    m_lastError.clear();
    if (!m_enabled && overridePort < 0) {
        emit stateChanged();
        return false;
    }
    const quint16 bindPort = overridePort >= 0 ? quint16(overridePort) : quint16(m_port);
    if (!m_server.listen(QHostAddress::LocalHost, bindPort)) {
        m_lastError = m_server.errorString();
        qWarning().noquote() << "capture-bridge: failed to listen on 127.0.0.1:" << bindPort
                             << "-" << m_lastError;
        emit stateChanged();
        return false;
    }
    qInfo().noquote() << "capture-bridge: listening on http://127.0.0.1:" << m_server.serverPort();
    emit stateChanged();
    return true;
}

void CaptureBridge::stop()
{
    if (m_server.isListening())
        m_server.close();
    emit stateChanged();
}

void CaptureBridge::setEnabled(bool on)
{
    if (m_enabled == on)
        return;
    m_enabled = on;
    QSettings().setValue(QStringLiteral("capture/enabled"), m_enabled);
    if (m_enabled)
        start();
    else
        stop();
}

void CaptureBridge::setPort(int port)
{
    if (port < 1024 || port > 65535 || port == m_port)
        return;
    m_port = port;
    QSettings().setValue(QStringLiteral("capture/port"), m_port);
    if (m_enabled)
        start();
    else
        emit stateChanged();
}

bool CaptureBridge::isAllowedOrigin(const QString &origin)
{
    const QString clean = origin.trimmed();
    if (clean.isEmpty())
        return true;
    return clean.startsWith(QLatin1String("chrome-extension://"), Qt::CaseInsensitive)
        || clean.startsWith(QLatin1String("moz-extension://"), Qt::CaseInsensitive)
        || clean.startsWith(QLatin1String("safari-web-extension://"), Qt::CaseInsensitive);
}

bool CaptureBridge::constantTimeEquals(const QByteArray &a, const QByteArray &b)
{
    if (a.isEmpty() || a.size() != b.size())
        return false;
    return sodium_memcmp(a.constData(), b.constData(), size_t(a.size())) == 0;
}

void CaptureBridge::onNewConnection()
{
    while (QTcpSocket *socket = m_server.nextPendingConnection()) {
        struct ConnState {
            QByteArray buffer;
            bool headersParsed = false;
            QByteArray method;
            QByteArray target;
            QHash<QByteArray, QByteArray> headers;
            qsizetype bodyStart = 0;
            qsizetype contentLength = 0;
            bool handled = false;
        };
        auto state = std::make_shared<ConnState>();

        auto *timer = new QTimer(socket);
        timer->setSingleShot(true);
        connect(timer, &QTimer::timeout, socket, [socket]() {
            socket->abort();
        });
        timer->start(kConnTimeoutMs);

        connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
        connect(socket, &QTcpSocket::readyRead, this, [this, socket, state, timer]() {
            if (state->handled)
                return;
            state->buffer.append(socket->readAll());

            if (!state->headersParsed) {
                const qsizetype sep = state->buffer.indexOf("\r\n\r\n");
                if (sep < 0) {
                    if (state->buffer.size() > kMaxHeaderBytes) {
                        state->handled = true;
                        timer->stop();
                        sendJson(socket, 431, "Request Header Fields Too Large",
                                 { { QStringLiteral("ok"), false },
                                   { QStringLiteral("error"), QStringLiteral("headers_too_large") } });
                    }
                    return;
                }
                const QByteArray head = state->buffer.left(sep);
                const QList<QByteArray> lines = head.split('\n');
                if (lines.isEmpty()) {
                    state->handled = true;
                    timer->stop();
                    sendJson(socket, 400, "Bad Request",
                             { { QStringLiteral("ok"), false },
                               { QStringLiteral("error"), QStringLiteral("malformed_request") } });
                    return;
                }
                const QList<QByteArray> reqParts = lines.first().trimmed().split(' ');
                if (reqParts.size() < 2) {
                    state->handled = true;
                    timer->stop();
                    sendJson(socket, 400, "Bad Request",
                             { { QStringLiteral("ok"), false },
                               { QStringLiteral("error"), QStringLiteral("malformed_request_line") } });
                    return;
                }
                state->method = reqParts[0].toUpper();
                state->target = reqParts[1];
                for (qsizetype i = 1; i < lines.size(); ++i) {
                    const QByteArray line = lines[i].trimmed();
                    const qsizetype colon = line.indexOf(':');
                    if (colon <= 0)
                        continue;
                    state->headers.insert(line.left(colon).trimmed().toLower(),
                                          line.mid(colon + 1).trimmed());
                }
                bool okLen = true;
                if (state->headers.contains("content-length")) {
                    const qlonglong len = state->headers.value("content-length").toLongLong(&okLen);
                    if (!okLen || len < 0) {
                        state->handled = true;
                        timer->stop();
                        sendJson(socket, 400, "Bad Request",
                                 { { QStringLiteral("ok"), false },
                                   { QStringLiteral("error"), QStringLiteral("invalid_content_length") } });
                        return;
                    }
                    state->contentLength = qsizetype(len);
                }
                const QByteArray pathOnly = state->target.split('?').first();
                if (state->contentLength > maxBodyForPath(pathOnly)) {
                    state->handled = true;
                    timer->stop();
                    sendJson(socket, 413, "Payload Too Large",
                             { { QStringLiteral("ok"), false },
                               { QStringLiteral("error"), QStringLiteral("payload_too_large") } });
                    return;
                }
                state->bodyStart = sep + 4;
                state->headersParsed = true;
            }

            if (state->buffer.size() - state->bodyStart < state->contentLength)
                return;

            state->handled = true;
            timer->stop();
            const QByteArray body = state->buffer.mid(state->bodyStart, state->contentLength);
            handleRequest(socket, state->method, state->target, state->headers, body);
        });
    }
}

void CaptureBridge::handleRequest(QTcpSocket *socket, const QByteArray &method,
                                  const QByteArray &target,
                                  const QHash<QByteArray, QByteArray> &headers,
                                  const QByteArray &body)
{
    const QByteArray rawOrigin = headers.value("origin");
    const QString origin = QString::fromUtf8(rawOrigin);
    if (!isAllowedOrigin(origin)) {
        sendJson(socket, 403, "Forbidden",
                 { { QStringLiteral("ok"), false },
                   { QStringLiteral("error"), QStringLiteral("forbidden_origin") } });
        return;
    }

    const QByteArray corsOrigin = rawOrigin;
    if (method == "OPTIONS") {
        sendEmpty(socket, 204, "No Content", corsOrigin);
        return;
    }

    // Constant-time Bearer token check on every endpoint.
    const QByteArray auth = headers.value("authorization");
    const QByteArray expected = QByteArrayLiteral("Bearer ") + m_token.toUtf8();
    if (!constantTimeEquals(auth, expected)) {
        sendJson(socket, 401, "Unauthorized",
                 { { QStringLiteral("ok"), false },
                   { QStringLiteral("error"), QStringLiteral("unauthorized") } },
                 corsOrigin);
        return;
    }

    if (!m_workspace) {
        sendJson(socket, 503, "Service Unavailable",
                 { { QStringLiteral("ok"), false },
                   { QStringLiteral("error"), QStringLiteral("workspace_unavailable") } },
                 corsOrigin);
        return;
    }

    const QUrl parsedUrl(QStringLiteral("http://127.0.0.1") + QString::fromUtf8(target));
    const QString path = parsedUrl.path();

    if (method == "GET" && path == QLatin1String("/v1/ping")) {
        sendJson(socket, 200, "OK",
                 { { QStringLiteral("ok"), true },
                   { QStringLiteral("app"), QStringLiteral("yaop-desktop") },
                   { QStringLiteral("version"), QStringLiteral(PROJECT_VERSION_STR) },
                   { QStringLiteral("protocol"), 1 } },
                 corsOrigin);
        return;
    }

    if (method == "GET" && path == QLatin1String("/v1/meta")) {
        sendJson(socket, 200, "OK", m_workspace->captureMeta(), corsOrigin);
        return;
    }

    if (method == "GET" && path == QLatin1String("/v1/lookup")) {
        const QUrlQuery q(parsedUrl);
        const QString url = q.queryItemValue(QStringLiteral("url"), QUrl::FullyDecoded);
        sendJson(socket, 200, "OK", m_workspace->lookupWebLink(url), corsOrigin);
        return;
    }

    if (method == "POST"
        && (path == QLatin1String("/v1/capture/weblink")
            || path == QLatin1String("/v1/capture/image")
            || path == QLatin1String("/v1/capture/note"))) {
        QJsonParseError err;
        const QJsonDocument doc = QJsonDocument::fromJson(body, &err);
        if (err.error != QJsonParseError::NoError || !doc.isObject()) {
            sendJson(socket, 400, "Bad Request",
                     { { QStringLiteral("ok"), false },
                       { QStringLiteral("error"), QStringLiteral("invalid_json") } },
                     corsOrigin);
            return;
        }
        QJsonObject result;
        if (path == QLatin1String("/v1/capture/weblink"))
            result = m_workspace->captureWebLink(doc.object());
        else if (path == QLatin1String("/v1/capture/image"))
            result = m_workspace->captureImage(doc.object());
        else
            result = m_workspace->captureNote(doc.object());

        const bool ok = result.value(QStringLiteral("ok")).toBool();
        sendJson(socket, ok ? 200 : 400, ok ? "OK" : "Bad Request", result, corsOrigin);
        return;
    }

    sendJson(socket, 404, "Not Found",
             { { QStringLiteral("ok"), false },
               { QStringLiteral("error"), QStringLiteral("not_found") } },
             corsOrigin);
}

void CaptureBridge::sendJson(QTcpSocket *socket, int status, const QByteArray &reason,
                             const QJsonObject &body, const QByteArray &corsOrigin)
{
    const QByteArray payload = QJsonDocument(body).toJson(QJsonDocument::Compact);
    QByteArray resp = "HTTP/1.1 " + QByteArray::number(status) + " " + reason + "\r\n"
        + "Content-Type: application/json; charset=utf-8\r\n"
        + "Content-Length: " + QByteArray::number(payload.size()) + "\r\n"
        + "Cache-Control: no-store\r\n"
        + "X-Content-Type-Options: nosniff\r\n"
        + "Connection: close\r\n";
    if (!corsOrigin.isEmpty()) {
        resp += "Access-Control-Allow-Origin: " + corsOrigin + "\r\n"
            + "Vary: Origin\r\n";
    }
    resp += "\r\n";
    resp += payload;
    socket->write(resp);
    socket->disconnectFromHost();
}

void CaptureBridge::sendEmpty(QTcpSocket *socket, int status, const QByteArray &reason,
                              const QByteArray &corsOrigin)
{
    QByteArray resp = "HTTP/1.1 " + QByteArray::number(status) + " " + reason + "\r\n"
        + "Content-Length: 0\r\n"
        + "Connection: close\r\n";
    if (!corsOrigin.isEmpty()) {
        resp += "Access-Control-Allow-Origin: " + corsOrigin + "\r\n"
            + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Authorization, Content-Type\r\n"
            + "Access-Control-Max-Age: 600\r\n"
            + "Vary: Origin\r\n";
    }
    resp += "\r\n";
    socket->write(resp);
    socket->disconnectFromHost();
}

} // namespace pkm
