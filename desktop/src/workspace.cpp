// Workspace: startup, vault keys, config and open editors.
#include "workspace.h"
#include <functional>
#include <memory>
#include "backup.h"
#include "syncpolicy.h"
#include "query.h"
#include "remoteops.h"
#include "bootstrapsnapshot.h"
#include "nodeops.h"
#include "standardtypes.h"
#include <QSaveFile>
#include <QRegularExpression>
#include <QImage>
#include <QMimeData>
#include <QGuiApplication>
#include <QClipboard>
#include "bip39.h"
#include "outbox.h"
#include "merge.h"
#include <QSysInfo>

#include <QCoreApplication>
#include <QDate>
#include <QLocale>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QNetworkInformation>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSet>
#include <QSettings>
#include <QStandardPaths>
#include <QUrl>
#ifdef Q_OS_LINUX
#include <malloc.h>
#endif

#include "workspace_internal.h"

namespace pkm {

using namespace detail;

QString Workspace::vaultAccount()
{
    const QString dataRoot = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    return QStringLiteral("vault:")
        + QString::fromLatin1(
              QCryptographicHash::hash(dataRoot.toUtf8(), QCryptographicHash::Sha256).toHex().left(16));
}

QString Workspace::vaultWrappedFile()
{
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
        + QStringLiteral("/cache/vault.key");
}

void Workspace::refreshDevices()
{
    if (m_client.baseUrl().isEmpty() || m_client.token().isEmpty()) {
        m_devices.clear();
        m_devicesNote = tr("No server configured.");
        emit devicesChanged();
        return;
    }
    m_client.listDevices([this](int status, const QJsonObject &body) {
        m_devices.clear();
        m_devicesNote.clear();
        if (status != 200) {
            m_devicesNote = tr("Could not load the device list.");
        } else {
            const QString me = m_deviceId;
            for (const QJsonValue &v : body.value(QStringLiteral("devices")).toArray()) {
                const QJsonObject d = v.toObject();
                const QString id = d.value(QStringLiteral("device_id")).toString();
                m_devices << QVariantMap{ { QStringLiteral("id"), id },
                                          { QStringLiteral("revoked"), d.value(QStringLiteral("revoked")).toBool() },
                                          { QStringLiteral("mine"), id == me } };
            }
        }
        emit devicesChanged();
    });
}

void Workspace::revokeDevice(const QString &deviceId)
{
    m_client.revokeDevice(deviceId, [this](int status) {
        if (status != 200) {
            m_devicesNote = tr("Only the master token can revoke devices.");
            emit devicesChanged();
            return;
        }
        refreshDevices();
    });
}

QString Workspace::recoveryPhrase() const
{
    if (m_vault.vk.size() != 32)
        return {};
    const auto codec = bip39::Codec::make(bip39::embeddedWordlist());
    return codec ? codec->entropyToMnemonic(m_vault.vk) : QString();
}

Workspace::Workspace(QObject *parent)
    : QObject(parent)
{
    const QString dataRoot = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    m_cacheDir = dataRoot + QStringLiteral("/cache");
    m_assetsDir = m_cacheDir + QStringLiteral("/assets");
    QDir().mkpath(m_assetsDir);

    const QString scope = QString::fromLatin1(
        QCryptographicHash::hash(dataRoot.toUtf8(), QCryptographicHash::Sha256).toHex().left(16));
    m_secretScope = scope;
    bootstrapVault();

    const QString runtimeRoot = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
    m_plainAssetsDir = (runtimeRoot.isEmpty() ? QDir::tempPath() : runtimeRoot)
        + QStringLiteral("/yaop-") + scope + QStringLiteral("/assets");
    QDir().mkpath(m_plainAssetsDir);
    QFile::setPermissions(QFileInfo(m_plainAssetsDir).absolutePath(),
                          QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner);

    QSettings settings;
    // Local-first (spec §15.1): NO default server. The desktop starts and
    // works without one; a URL the user set (or --server) still syncs.
    const QString url = settings.value(QStringLiteral("server/url"), QString()).toString().trimmed();
    m_client.setBaseUrl(url);

    m_deviceId = m_store ? m_store->kv(QStringLiteral("device_id")) : QString();
    if (m_deviceId.isEmpty()) {
        // A pairing enrolled a token under this id (onboarding): adopt it once.
        m_deviceId = settings.value(QStringLiteral("server/device_id")).toString().trimmed();
        settings.remove(QStringLiteral("server/device_id"));
    }
    if (m_deviceId.isEmpty()) {
        m_deviceId = QStringLiteral("desktop-") + newUuid().left(8);
        if (m_store)
            m_store->setKv(QStringLiteral("device_id"), m_deviceId);
    }
    // The stored secret travels as "<device_id>\n<token>": a token belongs to
    // ONE device (the server pins op.device_id to the token). A fresh database
    // (new device_id) never reuses the previous device's token — it would mean
    // silent 403 forbidden_device pushes. Without credentials the user
    // re-pastes the master token (--token / onboarding).
    // A token handed over in settings (--token, onboarding) wins and moves to the keychain.
    QString token = readStoredDeviceToken(scope);
    const QString handedToken = settings.value(QStringLiteral("server/token")).toString();
    if (!handedToken.isEmpty()) {
        token = handedToken;
        storeToken(handedToken);
    }
    m_client.setToken(token);
    setHlcNode(m_deviceId);
    m_lastSeq = m_store ? m_store->kvLong(QStringLiteral("last_seq"), 0) : 0;
    m_pushDebounce.setSingleShot(true);
    m_pullDebounce.setSingleShot(true);
    connect(&m_pushDebounce, &QTimer::timeout, this, &Workspace::syncNow);
    connect(&m_pullDebounce, &QTimer::timeout, this, &Workspace::syncNow);
    connect(&m_client, &SyncClient::liveEvent, this, &Workspace::onLiveEvent);
    connect(&m_client, &SyncClient::liveConnectedChanged, this, &Workspace::onLiveConnected);
    // Network back (Wi-Fi switch, resume from suspend): reconnect and sync now
    // instead of waiting out the backoff (parity with Android).
    if (QNetworkInformation::loadDefaultBackend() && QNetworkInformation::instance()) {
        connect(QNetworkInformation::instance(), &QNetworkInformation::reachabilityChanged, this,
                [this](QNetworkInformation::Reachability r) {
                    if (r != QNetworkInformation::Reachability::Online)
                        return;
                    m_client.reconnectLiveNow();
                    schedulePull(500);
                });
    }

    connect(QGuiApplication::clipboard(), &QClipboard::dataChanged, this, &Workspace::clipboardChanged);
    loadFromStore();
    purgeExpiredTrash(); // trash retention also runs offline (spec §3.4)
}

void Workspace::bootstrapVault()
{
    // VaultKey (spec/crypto.md §VK): keychain (service "YAOP"); wrapped
    // passphrase file when there is no keychain; ephemeral in memory when
    // neither exists — nothing persistent may sit on disk unprotected.
    m_vault = vault::loadOrCreateVaultKey(keychainSecrets(), QStringLiteral("vault:") + m_secretScope,
                                          m_cacheDir + QStringLiteral("/vault.key"));
    deriveSubkeys();
    m_fileCipher.setKey(vault::deriveFileKey(m_vault.vk)); // media files at rest

    const QString dbPath = m_cacheDir + QStringLiteral("/yaop.db");
    auto *store = new Store(m_vault.persistent() ? dbPath : QStringLiteral(":memory:"));
    if (!m_vault.vk.isEmpty() && store->setKey(vault::deriveDbKey(m_vault.vk)) && store->open()
        && store->isOpen()) {
        m_store = store;
    } else {
        qCritical() << "vault: local encrypted store unavailable; running in memory this session";
        delete store;
        store = new Store(QStringLiteral(":memory:"));
        store->open();
        m_store = store;
    }
    m_store->setKvLong(QStringLiteral("key_epoch"), qlonglong(m_vault.epoch));
    m_cacheEncrypted = m_store->encryptedAtRest();

    if (m_vault.source == vault::VaultKeys::Source::Ephemeral)
        qCritical().noquote() << "vault: ephemeral key: local data of this session will not persist";
}

void Workspace::deriveSubkeys()
{
    if (m_vault.vk.isEmpty())
        return;
    m_keyObjects = vault::subkey(m_vault.vk, QByteArrayLiteral("objects"));
    m_keyOps = vault::subkey(m_vault.vk, QByteArrayLiteral("ops"));
    m_keySnapshots = vault::subkey(m_vault.vk, QByteArrayLiteral("snapshots"));
    m_keyAssets = vault::subkey(m_vault.vk, QByteArrayLiteral("assets"));
    m_keyThumbs = vault::subkey(m_vault.vk, QByteArrayLiteral("thumbs"));
    m_keyDerived = vault::subkey(m_vault.vk, QByteArrayLiteral("derived"));
    m_keyIds = vault::subkey(m_vault.vk, QByteArrayLiteral("ids"));
}

QString Workspace::newSyncOpId() const
{
    return QString::fromLatin1(vault::randomBytes(16).toHex()); // 32 hex chars (spec/sync.md)
}

QString Workspace::blindId(const QString &realId) const
{
    return vault::wireId(m_keyIds, realId);
}

void Workspace::syncError(const QString &what)
{
    if (m_lastError == what)
        return;
    m_lastError = what;
    emit syncStateChanged();
}

Workspace::~Workspace()
{
    // Children (EditorModel) die after the members below; they must not flush
    // into a half-destructed workspace. Mark shutdown, then drop them by hand.
    m_shuttingDown = true;
    // Decrypted media copies only live for the session.
    if (!m_plainAssetsDir.isEmpty())
        QDir(m_plainAssetsDir).removeRecursively();
    const QList<EditorModel *> editors = m_editors.values();
    m_editors.clear();
    for (EditorModel *m : editors)
        delete m;
}

void Workspace::initialize()
{
    if (!serverConfigured()) {
        // Fully local session (spec §15.7): no handshake, no timers, no errors.
        qInfo().noquote() << "workspace: no server configured — running local-first";
        return;
    }
    if (m_protocolRejected)
        return;
    m_client.status([this](const SyncClient::Response &r) {
        const QJsonObject err = r.body.value(QLatin1String("error")).toObject();
        const QString server = r.body.value(QLatin1String("protocol_version")).toString();
        if (r.ok && server == QLatin1String("1")) {
            m_connected = true;
            m_protocolRejected = false;
            // The server's log is behind our cursor: it was reset or regenerated. Restart the
            // pull from 0 and resend every object, or this device never converges with it.
            const qlonglong head = qlonglong(r.body.value(QLatin1String("head_seq")).toDouble());
            const QString serverVault = r.body.value(QLatin1String("vault")).toString();
            if (syncpolicy::needsFullResend(m_store->kv(QStringLiteral("server_vault")), serverVault,
                                            m_lastSeq, head)) {
                qWarning().noquote() << "sync: server" << serverVault << "head" << head << "cursor" << m_lastSeq
                                     << "- new or reset server: resetting and resending everything";
                m_store->setKv(QStringLiteral("server_vault"), serverVault);
                m_lastSeq = 0;
                m_store->setKvLong(QStringLiteral("last_seq"), 0);
                for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
                    m_store->dequeueOpsFor(it.key());
                    m_store->queueOp(newSyncOpId(), it.key(), it.value().toJson(true));
                }
                requeueAssetUploads(); // the media bytes live on the old server, not on this one
                m_pendingCount = m_store->pendingOpCount();
            }
            requeueAssetUploads(); // self-heal: any local media the server still lacks goes up
            m_hasBootstrap = !r.body.value(QLatin1String("bootstrap_seq")).isNull();
            m_bootstrapSeq = qlonglong(r.body.value(QLatin1String("bootstrap_seq")).toDouble());
            m_compactFloor = qlonglong(r.body.value(QLatin1String("compact_floor")).toDouble());
            m_client.startLive(m_deviceId);
            if (syncpolicy::shouldStartFromBootstrap(m_lastSeq, m_hasBootstrap, m_compactFloor)) {
                startFromBootstrap([this](bool) { syncNow(); }); // falls back to the plain pull on failure
                return;
            }
            syncNow();
            return;
        }
        if (r.httpStatus == 426
            || err.value(QLatin1String("code")).toString() == QLatin1String("protocol_mismatch")) {
            m_protocolRejected = true;
            syncError(QStringLiteral("protocol_mismatch: server speaks '%1', the app speaks '1' "
                                     "— update server or app")
                          .arg(err.value(QLatin1String("server_protocol_version")).toString()));
            return;
        }
        if (!r.ok)
            m_connected = false;
        syncError(QStringLiteral("server handshake failed (HTTP %1)").arg(r.httpStatus));
    });
}

void Workspace::loadFromStore()
{
    m_objects.clear();
    m_types.clear();
    if (m_store) {
        for (const Object &o : m_store->allObjects())
            m_objects.insert(o.id, o);
        for (const ObjectType &t : m_store->types())
            m_types.insert(t.id, t);
        m_pendingCount = m_store->pendingOpCount();
    }
    pkm::detail::seedStandardTypes(m_types, pkm::detail::uiLanguage());
    purgeOldSnapshots();
    refreshStoredLinks();
    refreshViews();
    emit syncStateChanged();
}

namespace detail {

void seedStandardTypes(QHash<QString, ObjectType> &types, const QString &lang)
{
    // Local-first (spec §15.7): the standard types exist without any server. They are seeded in
    // memory only — never queued as ops — and a synced/edited type with the same id wins.
    struct Std { const char *id; const char *name; const char *icon; const char *color; };
    static const Std kStandard[] = {
        { "note", "Note", "file-text", "purple" },
        { "task", "Task", "circle-check", "orange" },
        { "project", "Project", "folder", "blue" },
        { "person", "Person", "user", "pink" },
        { "journal", "Daily", "calendar", "teal" },
        { "whiteboard", "Whiteboard", "layout-board-split", "yellow" },
        { "collection", "Collection", "book", "purple" },
        { "set", "Set", "filter", "green" },
        { "book", "Book", "book", "purple" },
        { "web", "Web", "external-link", "blue" },
        { "image", "Image", "photo", "orange" },
        { "pdf", "PDF", "file-type-pdf", "orange" },
        { "audio", "Audio", "music", "orange" },
        { "tag", "Tag", "tag", "green" },
        { "habit", "Habit", "heart", "green" },
    };
    // Their properties come from spec/types.md; a synced copy only adds its own keys (rule 5).
    for (const Std &t : kStandard) {
        const QString id = QString::fromLatin1(t.id);
        const QJsonArray stdDefs = standardPropertyDefs(id, lang);
        if (types.contains(id)) {
            ObjectType &synced = types[id];
            synced.propertyDefs = mergeStandardPropertyDefs(stdDefs, synced.propertyDefs);
            continue;
        }
        ObjectType ft;
        ft.id = id;
        const QString label = standardTypeLabel(id, lang);
        ft.name = label.isEmpty() ? QString::fromLatin1(t.name) : label;
        ft.icon = QString::fromLatin1(t.icon);
        ft.color = QString::fromLatin1(t.color);
        ft.standard = true;
        ft.propertyDefs = stdDefs;
        types.insert(id, ft);
    }
}

} // namespace detail

Object *Workspace::objectPtr(const QString &id)
{
    auto it = m_objects.find(id);
    return it != m_objects.end() ? &it.value() : nullptr;
}

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------
void Workspace::setServerUrl(const QString &url)
{
    QString trimmed = url.trimmed();
    while (trimmed.endsWith(QLatin1Char('/')))
        trimmed.chop(1);
    if (m_client.baseUrl() == trimmed)
        return;
    QSettings settings;
    settings.setValue(QStringLiteral("server/url"), trimmed);
    m_client.setBaseUrl(trimmed);
    m_store->setKvLong(QStringLiteral("last_seq"), 0);
    m_lastSeq = 0;
    m_protocolRejected = false;
    emit configChanged();
    syncNow();
    m_client.stopLive();
    if (serverConfigured())
        m_client.startLive(m_deviceId);
}

void Workspace::setBearerToken(const QString &token)
{
    if (m_client.token() == token.trimmed())
        return;
    storeToken(token.trimmed());
    m_client.setToken(token.trimmed());
    m_store->setKv(QStringLiteral("device_token_for"), QString()); // may be a master: enroll again
    m_protocolRejected = false;
    emit configChanged();
    syncNow();
}

// ---------------------------------------------------------------------------
// Editors
// ---------------------------------------------------------------------------
EditorModel *Workspace::openObject(const QString &id)
{
    auto it = m_editors.find(id);
    if (it != m_editors.end())
        return it.value();
    auto objIt = m_objects.find(id);
    if (objIt == m_objects.end()) {
        qWarning() << "openObject: unknown id" << id;
        return nullptr;
    }
    auto *model = new EditorModel(this, this);
    model->load(objIt.value());
    m_editors.insert(id, model);
    return model;
}

int Workspace::keyModifiers() const
{
    return int(QGuiApplication::keyboardModifiers());
}

void Workspace::closeObject(const QString &id)
{
    auto it = m_editors.find(id);
    if (it == m_editors.end())
        return;
    it.value()->flushNow();
    captureSnapshot(id); // end of an editing session (product §16.3)
    it.value()->deleteLater();
    m_editors.erase(it);
    if (m_activeEditingId == id)
        m_activeEditingId.clear();
#ifdef Q_OS_LINUX
    malloc_trim(0);
#endif
}

void Workspace::notifyObjectListChanged()
{
    if (m_listBatchDepth > 0) {
        m_listDirty = true;
        return;
    }
    ++m_listRevision;
    emit objectListChanged();
}

void Workspace::clearActiveEditing(const QString &id)
{
    if (m_activeEditingId == id)
        m_activeEditingId.clear();
}

void Workspace::saveObject(const Object &object, EditorModel *editor)
{
    Q_UNUSED(editor);
    Object local = object;
    local.typeId = normalizeTypeId(local.typeId.isEmpty()
                                       ? QString(TypeId::Note) : local.typeId);
    local.syncStatus = QStringLiteral("pending");

    auto existing = m_objects.find(local.id);
    const bool isNew = existing == m_objects.end();
    if (!isNew) {
        // Preserve server-side revision lineage for optimistic concurrency.
        if (existing.value().revision > local.revision)
            local.revision = existing.value().revision;
    } else {
        local.revision = 0;
    }
    // Field/block stamps + anchors (spec/merge.md). Nothing changed → nothing to sync.
    const Object prev = isNew ? Object() : existing.value();
    if (!local.stamp(isNew ? nullptr : &prev, newHlc()) && !isNew)
        return;
    // keep the derived links current (spec/object.md) so graph and queries read them, instead
    // of re-deriving every object's links from its wire on each graph build
    local.links = deriveLinks(local.toJson(true));
    m_objects.insert(local.id, local);
    m_store->putObject(local);

    m_store->queueOp(newSyncOpId(), local.id, local.toJson(true));
    m_pendingCount = m_store->pendingOpCount();
    emit syncStateChanged();
    emit objectUpdated(local.id);
    refreshViews();
    ++m_listRevision;
    emit objectListChanged();

    schedulePush(2000);
}

// Objects saved before links were derived on save may carry stale `links`. Fix them once, in
// small batches on the event loop so startup stays responsive; a kv flag marks it done.
void Workspace::refreshStoredLinks()
{
    if (!m_store || m_store->kv(QStringLiteral("derived_links_v1")) == QLatin1String("1"))
        return;
    auto ids = std::make_shared<QStringList>(m_objects.keys());
    auto step = std::make_shared<std::function<void()>>();
    *step = [this, ids, step]() {
        if (m_shuttingDown || !m_store)
            return;
        for (int n = 0; n < 25 && !ids->isEmpty(); ++n) { // ~15 ms per batch at most
            auto it = m_objects.find(ids->takeLast());
            if (it == m_objects.end())
                continue;
            const QStringList links = deriveLinks(it->toJson(true));
            if (links != it->links) {
                it->links = links;
                m_store->putObject(it.value()); // local cache only: nothing to sync
            }
        }
        if (ids->isEmpty()) {
            m_store->setKv(QStringLiteral("derived_links_v1"), QStringLiteral("1"));
            ++m_listRevision;
            emit objectListChanged();
            return;
        }
        QTimer::singleShot(5, this, *step); // input and paint run between batches
    };
    QTimer::singleShot(1500, this, *step);
}

void Workspace::refreshViews()
{
    // In-memory ordering hints are recomputed on demand; nothing to precompute.
}

} // namespace pkm
