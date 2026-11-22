#pragma once

// LinkPreview: decoupled OpenGraph + platform-aware metadata extractor for desktop.
//
// Architecture (ARCHITECTURE.md):
// - Pure parsing, URL classification, title cleaning, and WebLink update policies live in
//   `pkm::linkpreview` (linkpreview_parse.cpp) — zero I/O, deterministic, unit-testable.
// - Async HTTP orchestration and per-platform fallback pipelines (YouTube, Reddit, Instagram,
//   Facebook, X/Twitter, TikTok, generic) live in `LinkPreviewFetcher` (linkpreview_fetch.cpp)
//   with an injectable `HttpGetFn` transport so every pipeline is testable offline.
// - `Workspace` (workspace_weblinks.cpp) only wires results into objects, editors, and covers.

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QMap>
#include <QObject>
#include <QString>
#include <QStringList>
#include <QUrl>

#include <functional>
#include <optional>

class QNetworkAccessManager;

namespace pkm::linkpreview {

enum class LinkMediaType {
    Generic,
    YouTube,
    InstagramReel,
    InstagramPost,
    Reddit,
    RedditVideo,
    Facebook,
    FacebookVideo,
    Twitter,
    TikTok,
};

struct LinkPreviewData {
    QUrl url;
    QString title;
    QString description;
    QString imageUrl;
    QString siteName;
    QString faviconUrl;
    QString ogType;
    LinkMediaType mediaType = LinkMediaType::Generic;
    bool hasVideo = false;
};

struct LinkPreviewOptions {
    // Mirrors Android NetworkPreferences.KEY_ALLOW_THIRD_PARTY_LINK_PROXIES.
    // When false, only first-party origins (direct page, official oEmbed, official embeds) are queried.
    bool allowThirdPartyProxies = true;
};

struct HttpRequest {
    QUrl url;
    QMap<QString, QString> headers;
    int timeoutMs = 8000;
    qint64 maxBytes = 2 * 1024 * 1024; // 2 MB cap (covers large SPAs such as YouTube ~850 KB <head>)
    bool followRedirects = true;
    int maxRedirects = 5;
};

struct HttpResponse {
    bool ok = false;
    int statusCode = 0;
    QUrl finalUrl;
    QString redirectLocation;
    QString contentType;
    QByteArray body;
};

using HttpGetFn = std::function<void(const HttpRequest &req,
                                     std::function<void(HttpResponse)> done)>;

// ---- Pure URL & entity helpers ----
QUrl normalizeUrl(const QString &raw);
QString bareHost(const QUrl &url);
QString cleanImageUrl(const QString &url);
QString decodeHtmlEntities(const QString &text);

// ---- Pure platform detection & URL rewriting ----
QString extractYouTubeVideoId(const QUrl &url);
bool isReddit(const QUrl &url);
QUrl toVxRedditUrl(const QUrl &url);
QString extractRedditPostId(const QUrl &url);
bool isInstagram(const QUrl &url);
QString extractInstagramShortcode(const QUrl &url);
QString extractInstagramUsername(const QUrl &url);
bool isFacebook(const QUrl &url);
bool isTwitterOrX(const QUrl &url);

struct TwitterStatusRef {
    QString user;
    QString statusId;
    bool isValid() const { return !user.isEmpty() && !statusId.isEmpty(); }
};
TwitterStatusRef extractTwitterStatus(const QUrl &url);
QUrl toFxTwitterUrl(const QUrl &url);
bool isTikTok(const QUrl &url);

// ---- Pure title filtering, cleaning & fallback ----
bool isBlockedTitle(const QString &title);
QString cleanRedditTitle(const QString &raw);
QString cleanInstagramTitle(const QString &raw);
QString cleanFacebookTitle(const QString &raw);
QString titleFromUrlSlug(const QUrl &url);

// ---- Pure HTML & platform payload parsers ----
std::optional<LinkPreviewData> parseOpenGraphHtml(const QByteArray &rawBytes,
                                                  const QUrl &baseUrl,
                                                  const QString &contentType = {});
std::optional<LinkPreviewData> parseYouTubeOEmbed(const QByteArray &jsonBytes,
                                                  const QUrl &originalUrl,
                                                  const QString &videoId);

struct RedditArchivePost {
    QString title;
    QString description;
    QString imageUrl;
    bool isVideo = false;
    bool ok = false;
};
RedditArchivePost parseRedditArchiveJson(const QByteArray &jsonBytes);

std::optional<LinkPreviewData> parseVxTwitterJson(const QByteArray &jsonBytes,
                                                  const QUrl &originalUrl,
                                                  const QString &fallbackUser);
std::optional<LinkPreviewData> parseTikTokOEmbed(const QByteArray &jsonBytes,
                                                 const QUrl &originalUrl);
QString extractInstagramEmbedImage(const QByteArray &rawBytes, const QUrl &embedUrl);

// ---- Pure WebLink metadata classification & update policy ----
QString inferWebCategory(const QUrl &url,
                         const QString &ogType,
                         LinkMediaType mediaType = LinkMediaType::Generic);
QStringList inferWebTopics(const QUrl &url,
                           const QString &title,
                           const QString &description);
bool shouldReplacePlaceholderTitle(const QString &currentTitle,
                                   const QUrl &originalUrl,
                                   const QUrl &finalUrl);
bool shouldReplacePlaceholderSiteName(const QString &currentSiteName,
                                      const QUrl &originalUrl,
                                      const QUrl &finalUrl);

// ---- Standard request header profiles ----
QMap<QString, QString> ogScraperHeaders();
QMap<QString, QString> botHeaders();
QMap<QString, QString> jsonBotHeaders();
QMap<QString, QString> socialCrawlerHeaders();
QMap<QString, QString> imageDownloadHeaders(const QUrl &imageUrl);

// ---- Async link preview & cover image fetcher ----
class LinkPreviewFetcher : public QObject {
    Q_OBJECT
public:
    static constexpr int kMaxCacheEntries = 64;

    explicit LinkPreviewFetcher(QObject *parent = nullptr);
    explicit LinkPreviewFetcher(HttpGetFn transport, QObject *parent = nullptr);
    ~LinkPreviewFetcher() override;

    using PreviewCallback = std::function<void(const std::optional<LinkPreviewData> &)>;
    using ImageCallback = std::function<void(const QByteArray &bytes)>;

    void fetch(const QString &rawUrl, const LinkPreviewOptions &opts, PreviewCallback cb);
    void downloadImage(const QUrl &imageUrl, ImageCallback cb);
    void clearCache();

private:
    void initDefaultTransport();
    void httpGet(const HttpRequest &req, std::function<void(HttpResponse)> done);
    void resolveRedirects(const QUrl &startUrl, int maxHops, std::function<void(QUrl)> done);

    void fetchYouTube(const QUrl &url, const QString &videoId, PreviewCallback done);
    void fetchReddit(const QUrl &url, bool allowThirdPartyProxies, PreviewCallback done);
    void fetchInstagram(const QUrl &url, PreviewCallback done);
    void fetchFacebook(const QUrl &url, PreviewCallback done);
    void fetchTwitter(const QUrl &url, bool allowThirdPartyProxies, PreviewCallback done);
    void fetchTikTok(const QUrl &url, PreviewCallback done);
    void fetchGeneric(const QUrl &url, PreviewCallback done);

    QString cacheKey(const QUrl &normalizedUrl, const LinkPreviewOptions &opts) const;
    std::optional<LinkPreviewData> cacheLookup(const QString &key);
    void cacheInsert(const QString &key, const LinkPreviewData &data);

    QNetworkAccessManager *m_nam = nullptr;
    HttpGetFn m_transport;
    QHash<QString, LinkPreviewData> m_cache;
    QList<QString> m_cacheOrder; // MRU at back, LRU at front
};

} // namespace pkm::linkpreview
