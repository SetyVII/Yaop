#include "providers.h"

#include "texrender_internal.h"

#include <QFileInfo>
#include <QUrl>

namespace pkm {

// id form: <percent-encoded latex>~<px>~<0|1 display>
QString TeXImageProvider::urlFor(const QString &latex, qreal px, bool display)
{
    QString id = QString::fromUtf8(QUrl::toPercentEncoding(latex));
    id += QStringLiteral("~%1~%2").arg(int(px), display ? 1 : 0);
    return QStringLiteral("image://tex/") + id;
}

QString TeXImageProvider::decodeId(const QString &id, qreal *px, bool *display)
{
    const QStringList parts = id.split(QLatin1Char('~'));
    bool ok = false;
    if (px)
        *px = parts.size() > 1 ? parts.at(1).toDouble(&ok) : 0.0;
    if (!ok && px)
        *px = 22.0;
    if (display)
        *display = parts.size() > 2 && parts.at(2).toInt() == 1;
    return QUrl::fromPercentEncoding(parts.isEmpty() ? QString().toUtf8() : parts.at(0).toUtf8());
}

TeXImageProvider::TeXImageProvider()
    : QQuickImageProvider(QQuickImageProvider::Image)
{
}

QImage TeXImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    QMutexLocker lock(&m_mutex);
    const auto it = m_cache.constFind(id);
    if (it != m_cache.constEnd()) {
        QImage img = it.value();
        if (size)
            *size = img.size();
        return img;
    }
    qreal px = 22.0;
    bool display = false;
    const QString latex = decodeId(id, &px, &display);
    QByteArray colorHex = "#FFFFFF";
    tex::Result result = tex::render(latex, px, QColor(colorHex.constData()), display, 2.0);
    QImage img = result.ok ? result.image : QImage();
    if (!img.isNull() && !requestedSize.isNull() && requestedSize.width() > 0) {
        // keep aspect when QML constrains
        const qreal aspect = qreal(img.width()) / qreal(img.height() * 2);
        QImage scaled = img.scaled(requestedSize.width(), qMax(1, int(requestedSize.width() / aspect)),
                                   Qt::KeepAspectRatio, Qt::SmoothTransformation);
        if (!scaled.isNull())
            img = scaled;
    }
    if (m_cache.size() > 512)
        m_cache.clear();
    m_cache.insert(id, img);
    if (size)
        *size = img.size();
    return img;
}

// ---------------------------------------------------------------------------
// PDF pages
// ---------------------------------------------------------------------------
PdfPageProvider::PdfPageProvider(const QString &cacheDir)
    : QQuickImageProvider(QQuickImageProvider::Image)
    , m_cacheDir(cacheDir)
    , m_lookup([](const QString &id) { return id; })
{
}

QPdfDocument *PdfPageProvider::documentFor(const QString &assetId)
{
    QMutexLocker lock(&m_mutex);
    const auto it = m_docs.constFind(assetId);
    if (it != m_docs.constEnd())
        return it.value();
    const QString path = m_lookup(assetId);
    if (path.isEmpty() || !QFileInfo::exists(path))
        return nullptr;
    auto *doc = new QPdfDocument();
    if (doc->load(path) != QPdfDocument::Error::None) {
        delete doc;
        return nullptr;
    }
    m_docs.insert(assetId, doc);
    return doc;
}

int PdfPageProvider::pageCount(const QString &assetId)
{
    QPdfDocument *doc = documentFor(assetId);
    return doc ? doc->pageCount() : 0;
}

void PdfPageProvider::invalidate(const QString &assetId)
{
    QMutexLocker lock(&m_mutex);
    const auto it = m_docs.find(assetId);
    if (it != m_docs.end()) {
        delete it.value();
        m_docs.erase(it);
    }
}

QImage PdfPageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    const QStringList parts = id.split(QLatin1Char('/'));
    const QString assetId = parts.value(0);
    int page = parts.value(1, "1").toInt();        // 1-based
    const qreal zoom = qBound(0.25, parts.value(2, "1.5").toDouble(), 4.0);
    if (page < 1)
        page = 1;

    QPdfDocument *doc = documentFor(assetId);
    if (!doc) {
        if (size)
            *size = QSize(0, 0);
        return {};
    }
    const QSizeF base = doc->pagePointSize(page - 1);
    int w = int(base.width() * zoom);
    int h = int(base.height() * zoom);
    if (!requestedSize.isNull() && requestedSize.width() > 0 && requestedSize.height() > 0) {
        w = requestedSize.width();
        h = requestedSize.height();
    }
    QImage img = doc->render(page - 1, QSize(w, h));
    if (size)
        *size = img.size();
    return img;
}

} // namespace pkm
