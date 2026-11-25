#pragma once

// Workspace: central store + sync orchestrator for the desktop shell.
// E2EE makes the desktop a fully local, E2EE client (spec §15): the VaultKey
// never leaves the device un-wrapped, everything on the wire travels under
// spec/crypto.md envelopes over /v1 (blind server), and search/backlinks/
// journal/task-completion run on the local encrypted database — the desktop
// starts and works with no server configured at all.

#include "cachecrypto.h"
#include "editormodel.h"
#include "outbox.h"
#include "store.h"
#include "syncclient.h"
#include "vaultcrypto.h"

#include <QDateTime>
#include <QHash>
#include <QSet>
#include <QSize>
#include <QObject>
#include <functional>
#include <memory>
#include <QTimer>

namespace pkm {

namespace linkpreview {
class LinkPreviewFetcher;
struct LinkPreviewData;
} // namespace linkpreview

class Workspace : public QObject {
    Q_OBJECT

    // Sync state surface (kept subtle in UI)
    Q_PROPERTY(bool connected READ connected NOTIFY syncStateChanged)
    Q_PROPERTY(bool syncing READ syncing NOTIFY syncStateChanged)
    Q_PROPERTY(bool live READ live NOTIFY syncStateChanged)
    Q_PROPERTY(int pendingCount READ pendingCount NOTIFY syncStateChanged)
    Q_PROPERTY(QString lastSyncText READ lastSyncText NOTIFY syncStateChanged)
    Q_PROPERTY(QString lastError READ lastError NOTIFY syncStateChanged)
    Q_PROPERTY(QString serverUrl READ serverUrl WRITE setServerUrl NOTIFY configChanged)
    Q_PROPERTY(QString bearerToken READ bearerToken WRITE setBearerToken NOTIFY configChanged)
    Q_PROPERTY(QString deviceId READ deviceId CONSTANT)
    Q_PROPERTY(bool clipboardHasMedia READ clipboardHasMedia NOTIFY clipboardChanged)
    Q_PROPERTY(QVariantList devices READ devices NOTIFY devicesChanged)
    Q_PROPERTY(QString devicesNote READ devicesNote NOTIFY devicesChanged)
    // Bumped with every objectListChanged: QML function-bindings depend on it
    // to re-evaluate list queries after async pulls complete.
    Q_PROPERTY(int listRevision READ listRevision NOTIFY objectListChanged)

public:
    explicit Workspace(QObject *parent = nullptr);
    // Where this profile's VK lives (shared with the onboarding that creates it).
    static QString vaultAccount();
    static QString vaultWrappedFile();
    // Pairing needs the VK to seal it for the new device; never exposed to QML.
    const QByteArray &vaultKeyBytes() const { return m_vault.vk; }
    quint32 vaultEpoch() const { return m_vault.epoch; }
    // The 24-word phrase of this vault ("show it from Settings"). Empty without a vault.
    Q_INVOKABLE QString recoveryPhrase() const;
    // Encrypted .yaop backup (spec §15.6): whole vault + media in one file that the phrase
    // opens. Both return "ok: ..." or "error: ..." for the UI to show. Synchronous.
    Q_INVOKABLE QString exportBackup(const QString &path);
    Q_INVOKABLE QString restoreBackup(const QString &path);
    // One-off import of a readable export of the pre-E2EE server (objects.json, types.json, assets/<id>):
    // everything lands in this vault and is queued to travel to whatever server this device syncs with.
    ~Workspace() override;

    void initialize();

    // config
    QString serverUrl() const { return m_client.baseUrl(); }
    void setServerUrl(const QString &url);
    QString bearerToken() const { return m_client.token(); }
    void setBearerToken(const QString &token);
    QString deviceId() const { return m_deviceId; }
    int listRevision() const { return m_listRevision; }

    // sync state
    bool connected() const { return m_connected; }
    bool syncing() const { return m_syncing; }
    bool live() const { return m_live; }
    int pendingCount() const { return m_store ? int(m_pendingCount) : 0; }
    QString lastSyncText() const { return m_lastSyncText; }
    QString lastError() const { return m_lastError; }

    // editors
    Q_INVOKABLE EditorModel *openObject(const QString &id);
    Q_INVOKABLE void closeObject(const QString &id);
    // Version history (product §16.3): [{id, createdAt, title, blockCount, preview}], newest first.
    Q_INVOKABLE QVariantList objectHistory(const QString &id) const;
    // Restores the whole object, or only `blockId`, from a snapshot. The current state is kept as
    // a snapshot first, so a restore can be undone by restoring again.
    Q_INVOKABLE bool restoreVersion(const QString &id, qint64 snapshotId, const QString &blockId = QString());
    // The blocks of one snapshot, for its preview: [{id, type, level, text, state}], state being
    // "same", "changed" or "removed" against the object as it is now.
    Q_INVOKABLE QVariantList versionBlocks(const QString &id, qint64 snapshotId) const;
    // Text statistics and dates (pkmcore/editor/textstats): {words, sentences, paragraphs,
    // characters, createdAt, updatedAt}.
    Q_INVOKABLE QVariantMap objectStats(const QString &id) const;
    // Objects this one mentions or embeds, in reading order: [{id, title, typeId}].
    Q_INVOKABLE QVariantList containedObjects(const QString &id) const;
    Q_INVOKABLE bool hasEditorFor(const QString &id) { return m_editors.contains(id); }
    // Keyboard modifiers of the current input event (Qt.ControlModifier, Qt.ShiftModifier…):
    // Ctrl+click opens the place in a new tab, Shift+click in the side pane.
    Q_INVOKABLE int keyModifiers() const;
    bool shuttingDown() const { return m_shuttingDown; }
    void setActiveEditing(const QString &id) { m_activeEditingId = id; }
    void clearActiveEditing(const QString &id);
    void saveObject(const Object &object, EditorModel *editor = nullptr);

    // e2e: read-only snapshot of the in-memory objects for the --e2e driver.
    QList<Object> e2eAllObjects() const { return m_objects.values(); }
    QString e2eBlindId(const QString &id) const { return blindId(id); }

    // object CRUD + queries (all local; the server only syncs)
    Q_INVOKABLE QString createObject(const QString &typeId, const QString &title);
    // New local copy with fresh ids (pkmcore objectops); "" for media, journal and tag objects.
    Q_INVOKABLE QString duplicateObject(const QString &id);
    // spec/schema.md: changes type_id, keeps every value; keys the new type lacks become loose.
    Q_INVOKABLE bool changeObjectType(const QString &id, const QString &toTypeId);
    // {error: ""|"fixed_type"|…, loose: [{key}], properties: target type's properties}
    Q_INVOKABLE QVariantMap previewTypeChange(const QString &id, const QString &toTypeId) const;
    QStringList typePropertyKeys(const QString &typeId) const;
    // Text in other objects that names targetId without linking it: [{objectId, blockId, runIndex,
    // runOffset, length, snippet, title, typeId}]. linkUnlinkedMention turns one into a mention.
    // Block references (spec/text.md): blockId -> how many runs point at that block of objectId.
    Q_INVOKABLE QVariantMap blockReferenceCounts(const QString &objectId) const;
    Q_INVOKABLE QVariantList unlinkedMentions(const QString &targetId) const;
    // Objects of `typeId` whose `propKey` (select, multi_select or "tags") holds `value`, and the
    // other values they share: {center, objects:[{id,title,typeId}], related:[{prop, propName,
    // id, label, color, objectIds}]} (pkmcore/editor/optionview).
    Q_INVOKABLE QVariantMap optionNeighborhood(const QString &typeId, const QString &propKey, const QString &value) const;
    Q_INVOKABLE bool linkUnlinkedMention(const QString &targetId, const QVariantMap &match);
    QHash<QString, Object> objectsWithEditors() const; // stored objects with unsaved editor edits
    // Moves blocks (with their children) to the end of another object; returns how many moved.
    Q_INVOKABLE int moveBlocksToObject(const QString &fromId, const QStringList &blockIds, const QString &toId);
    // {nodes: [{id, typeId, title, degree}], edges: [{from, to}]}. Empty focusId = whole graph;
    // otherwise the local graph within `depth` hops. `types` empty = every type.
    Q_INVOKABLE QVariantMap graphData(const QString &focusId = QString(), int depth = 1,
                                      const QStringList &types = {}) const;
    Q_INVOKABLE void deleteObject(const QString &id);
    Q_INVOKABLE void restoreObject(const QString &id);
    // Drops objects 30+ days in the trash whose tombstone already reached the server; returns how many.
    int purgeExpiredTrash();
    Q_INVOKABLE void togglePinned(const QString &id);
    Q_INVOKABLE QVariantMap objectSummary(const QString &id) const;
    Q_INVOKABLE QVariantList recentObjects(int limit = 8) const;
    Q_INVOKABLE QVariantList pinnedObjects(int limit = 6) const;
    Q_INVOKABLE QVariantList trashedObjects() const;
    // Local query (spec/query.md). Returns {error?, ids} or {error?, groups:[{key|null, ids}]};
    // `today` defaults to the local day.
    Q_INVOKABLE QVariantMap runQuery(const QVariantMap &query, const QString &today = QString()) const;
    // Saved sets (spec/query.md): objects of type "set" whose properties hold
    // `query`, `views` and `active_view`. runSet returns {error?, items:[summary]} for the UI.
    Q_INVOKABLE QString createSet(const QString &name, const QVariantMap &query);
    Q_INVOKABLE void renameObject(const QString &objectId, const QString &title);
    Q_INVOKABLE QVariantMap setInfo(const QString &setId) const;
    Q_INVOKABLE void setSetQuery(const QString &setId, const QVariantMap &query);
    Q_INVOKABLE void setSetActiveView(const QString &setId, const QString &kind);
    // `limit` > 0 builds only that many summaries; `total` always says how many matched.
    Q_INVOKABLE QVariantMap runSet(const QString &setId, int limit = 0) const;
    // Saved views of a type (spec/query.md: sets with `properties.type_view_of`), oldest first:
    // [{id, title, kind}]. createTypeView saves `query` (types = [typeId] is added) as a new one.
    Q_INVOKABLE QVariantList typeViews(const QString &typeId) const;
    Q_INVOKABLE QString createTypeView(const QString &typeId, const QString &name, const QVariantMap &query,
                                       const QString &kind);
    // Display text of one field of an object (table cells): lists joined, object refs as titles.
    Q_INVOKABLE QString fieldText(const QString &objectId, const QString &prop) const;
    Q_INVOKABLE QVariantList objectsByType(const QString &typeId, int limit = 0) const;
    // {typeId: count} of alive, non-template objects: what the sidebar shows, without building
    // a summary per object (objectsByType(...).length cost ~250 ms per save at 5k objects).
    Q_INVOKABLE QVariantMap typeCounts() const;
    Q_INVOKABLE QVariantList searchObjects(const QString &query, int limit = 50);
    Q_INVOKABLE QVariantList mentionSearch(const QString &rawQuery, int limit = 14);
    Q_INVOKABLE QVariantList tagSuggestions(const QString &query, int limit = 16) const;
    Q_INVOKABLE QString ensureTagObject(const QString &tagName);
    // An object's tags (spec/object.md: properties.tags = [name]), in their stored order.
    Q_INVOKABLE QStringList objectTags(const QString &objectId) const;
    Q_INVOKABLE QString tagColor(const QString &tagName) const;
    Q_INVOKABLE void setTagColor(const QString &tagName, const QString &colorHex);
    Q_INVOKABLE QVariantList allTypes() const;
    Q_INVOKABLE QVariantMap typeInfo(const QString &typeId) const;
    Q_INVOKABLE QString createCustomType(const QString &name, const QString &colorNameOrHex = QString(),
                                         const QVariantList &propDefs = {}, const QString &icon = QString());
    Q_INVOKABLE bool addPropertyToType(const QString &typeId, const QString &propName, const QString &kind);
    Q_INVOKABLE bool addOptionToProperty(const QString &typeId, const QString &propKey,
                                         const QString &label, const QString &color = QStringLiteral("blue"));
    Q_INVOKABLE bool removeOptionFromProperty(const QString &typeId, const QString &propKey, const QString &optionId);
    Q_INVOKABLE QVariantMap resolveOption(const QString &typeId, const QString &propKey, const QString &optionId) const;
    Q_INVOKABLE QVariantMap resolvePersonByValue(const QString &raw) const;
    Q_INVOKABLE QVariantList collectionsForObject(const QString &objectId) const;
    // Collections (product §19, workspace_collections.cpp): [{id, title, count}] by title; items in
    // their manual order as summaries; add / remove / move (indexes of collectionItems).
    Q_INVOKABLE QVariantList collections() const;
    Q_INVOKABLE QVariantList collectionItems(const QString &collectionId) const;
    Q_INVOKABLE bool addToCollection(const QString &collectionId, const QString &objectId);
    Q_INVOKABLE bool removeFromCollection(const QString &collectionId, const QString &objectId);
    Q_INVOKABLE bool moveInCollection(const QString &collectionId, int from, int to);
    Q_INVOKABLE QString createCollection(const QString &title);
    // Type templates (product §4.3): objects of the type with `is_template`, oldest first.
    Q_INVOKABLE QVariantList templatesFor(const QString &typeId) const;
    Q_INVOKABLE void setIsTemplate(const QString &objectId, bool on);
    // Fills the object from the template (pkmcore applyTemplate) and reloads its open editor.
    Q_INVOKABLE bool applyTemplate(const QString &objectId, const QString &templateId);
    Q_INVOKABLE QVariantMap projectProgress(const QString &projectId) const;
    Q_INVOKABLE QString clipboardText() const;
    Q_INVOKABLE QVariantList typeProperties(const QString &typeId) const;
    Q_INVOKABLE QVariantList objectPropertyEntries(const QString &objectId) const;
    // true when a checkbox property is ticked (bool true, or the text "true"); missing = false
    Q_INVOKABLE bool propertyChecked(const QString &objectId, const QString &key) const;
    // select: picks optionId (picking the current one clears it); multi: adds or removes it.
    Q_INVOKABLE void toggleOption(const QString &objectId, const QString &key, const QString &optionId, bool multi);
    Q_INVOKABLE void removeObjectProperty(const QString &objectId, const QString &key);
    Q_INVOKABLE void setObjectProperty(const QString &objectId, const QString &key, const QVariant &value,
                                       const QString &kind = QString(), const QString &label = QString(),
                                       bool addToType = false);
    // Habits (spec §24): day records are properties "d:AAAA-MM-DD" (true | number | "skip").
    Q_INVOKABLE QVariantList habits() const;
    Q_INVOKABLE QVariantList inbox() const;   // objects with reviewed == false
    Q_INVOKABLE QVariantList reviewedItems(int limit = 30) const; // objects with reviewed == true
    Q_INVOKABLE QVariantList allTagsWithCounts() const;
    Q_INVOKABLE QVariantList objectsForTag(const QString &tagName) const;
    Q_INVOKABLE void copyTextToClipboard(const QString &text) const;
    Q_INVOKABLE bool copyBlockReference(const QString &objectId, const QString &blockId) const;
    Q_INVOKABLE void markReviewed(const QString &id);
    Q_INVOKABLE void setHabitDay(const QString &habitId, const QString &isoDate, const QVariant &value);
    Q_INVOKABLE QVariantList tasksFor(const QString &scope) const; // "today"|"upcoming"|"done"
    Q_INVOKABLE void completeTask(const QString &id);
    Q_INVOKABLE QVariantList backlinksFor(const QString &id);
    Q_INVOKABLE QVariantList objectsCreatedOn(const QString &isoDate) const;
    Q_INVOKABLE QString journalFor(const QString &isoDate);
    Q_INVOKABLE QString journalLocalFor(const QString &isoDate);
    // Calendar marks for [fromIso, toIso]: {"yyyy-MM-dd": {note, tasks, created, refs}} for the days
    // that hold anything. note = the daily note has text; tasks = open tasks due; created = objects
    // created that day; refs = objects referencing the day's note. One pass over the objects.
    Q_INVOKABLE QVariantMap dayMarks(const QString &fromIso, const QString &toIso) const;
    // What the calendar grids draw for [fromIso, toIso] (at most 62 days), one pass:
    // [{day, kind: "note"|"task"|"created", id, title, typeId, iconEmoji, done, minutes}];
    // minutes = local time of creation for "created", -1 otherwise.
    Q_INVOKABLE QVariantList calendarItems(const QString &fromIso, const QString &toIso) const;
    Q_INVOKABLE QString ensureMediaObject(const QString &assetId);
    // Pixel size of a local image file, read from its header (EXIF orientation applied); invalid
    // when unreadable. Lets the editor size an image block before the picture decodes.
    Q_INVOKABLE QSize imageFileSize(const QString &localPath) const;
    Q_INVOKABLE QString createWebLinkFromUrl(const QString &url, const QString &sourceObjectId = QString(),
                                             const QString &sourceBlockId = QString());
    Q_INVOKABLE void setWebCoverUrl(const QString &objectId, const QString &imageUrl);
    // Inject a custom LinkPreviewFetcher (e.g., offline transport in --selftest); Workspace takes ownership.
    void setLinkPreviewFetcher(linkpreview::LinkPreviewFetcher *fetcher);

    // Local capture API (used by the browser extension bridge over 127.0.0.1, product §22.5).
    QJsonObject captureMeta() const;
    QJsonObject lookupWebLink(const QString &url) const;
    QJsonObject captureWebLink(const QJsonObject &req);
    QJsonObject captureImage(const QJsonObject &req);
    QJsonObject captureNote(const QJsonObject &req);
    QString secretScope() const { return m_secretScope; }

    // media
    Q_INVOKABLE QString ensureAssetBlocking(const QString &assetId);
    Q_INVOKABLE QString assetLocalPath(const QString &assetId) const;
    Q_INVOKABLE void ensureAsset(const QString &assetId);
    Q_INVOKABLE QString importFileAsAsset(const QString &localPath); // returns assetId ("" on error)
    // Paste / drop of media (images, audio, pdf): copied files, file URLs and raw clipboard images.
    // Each result is {assetId, name, kind: image|audio|pdf}; unsupported items are skipped.
    bool clipboardHasMedia() const;
    Q_INVOKABLE QVariantList importClipboardMedia();
    Q_INVOKABLE QVariantList importMediaUrls(const QVariantList &urls);
    Q_INVOKABLE QVariantMap assetInfo(const QString &assetId) const;
    Q_INVOKABLE bool hasAssetFile(const QString &assetId) const;
    Q_INVOKABLE int assetDurationSeconds(const QString &assetId) const;

    // sync control
    // Device panel (spec §15.3): list from the server, revoke (master token only).
    Q_INVOKABLE void refreshDevices();
    Q_INVOKABLE void revokeDevice(const QString &deviceId);
    QVariantList devices() const { return m_devices; }
    QString devicesNote() const { return m_devicesNote; }
    Q_INVOKABLE void syncNow();
    Q_INVOKABLE void triggerFullSync() { m_store->setKvLong(QStringLiteral("last_seq"), 0); m_lastSeq = 0; syncNow(); }
    Q_INVOKABLE int debugPullCheck();
    int pendingUploadCount() const { return m_store->pendingUploads().size(); }
    QString assetCacheDir() const { return m_plainAssetsDir; }
    bool cacheEncrypted() const { return m_cacheEncrypted; }

signals:
    void clipboardChanged();
    void devicesChanged();
    void syncStateChanged();
    void configChanged();
    void objectListChanged();
    void objectUpdated(const QString &id);
    void assetReady(const QString &assetId, const QString &localPath);
    void journalReady(const QString &objectId, const QString &isoDate);
    void searchResultsReady(const QVariantList &results);
    void backlinksReady(const QString &objectId, const QVariantList &objects);
    void taskCompleted(const QString &taskId);
    void editorReloadRequested(const QString &objectId);
    void typesChanged();
    void objectPropertiesChanged(const QString &objectId);
    void browserCaptureReceived(const QString &kind, const QString &title, const QString &objectId);

private slots:
    void onLiveEvent(const QJsonObject &event);
    void onLiveConnected(bool connected);

public:
    struct ObjectView {
        QString id;
        QString typeId;
        QString title;
        QString updatedAt;
        bool pinned = false;
        bool trashed = false;
        bool isTask = false;
        bool taskDone = false;
        QString dueDate;
    };

private:
    void loadFromStore();
    void captureSnapshot(const QString &id); // one per editing session, when content changed
    bool writeCollection(const QString &collectionId, const QStringList &items);
    std::optional<Object> currentObject(const QString &id) const;
    void refreshStoredLinks();
    void purgeOldSnapshots();                // older than 30 days
    // ---- /v1 transport pieces (spec/sync.md) ----
    void bootstrapVault();          // VaultKey + subkeys + SQLCipher open
    bool serverConfigured() const { return !m_client.baseUrl().isEmpty() && !m_client.token().isEmpty(); }
    // Wire fields whose equality means "the merge changes nothing that converges".
    static bool wireConverged(const QJsonObject &merged, const QJsonObject &remote);
    void applyRemoteOp(const QJsonObject &op);
    void buildOpRegistry();
    // bootstrap: start from the server's bootstrap snapshot / upload one when the log has grown.
    void startFromBootstrap(std::function<void(bool)> done);
    void produceBootstrapIfDue(qlonglong head);
    qlonglong m_bootstrapSeq = 0;
    qlonglong m_compactFloor = 0;
    bool m_hasBootstrap = false;
    bool m_producingBootstrap = false;
    QByteArray keyForDomain(const QByteArray &domain) const;
    std::unique_ptr<class DerivedSink> m_derivedSink;
    std::unique_ptr<class RemoteOpRegistry> m_opRegistry;
    std::unique_ptr<class RemoteOpPipeline> m_opPipeline;
    void applyRemoteObject(const QJsonObject &remote, const QString &kind);
    void startPull(qlonglong since);
    void pushBatches(const QList<QList<PushOp>> &batches, int index);
    void handlePushFailure(const SyncClient::Response &r);
    SyncOp buildSyncOp(const QString &opId, const QString &objectId, const QJsonObject &payload);
    QSet<QString> pendingObjectIds() const;
    void uploadPendingAssets();
    void requeueAssetUploads();
    void startAssetUpload(const Store::PendingUpload &u);
    void storeToken(const QString &token);
    linkpreview::LinkPreviewFetcher *ensureLinkPreviewFetcher();
    void fetchWebLinkMetadata(const QString &url, const QString &weblinkId);
    void applyWebLinkPreview(const QString &weblinkId, const QString &originalUrl,
                             const linkpreview::LinkPreviewData &preview);
    void downloadWebCoverAsset(const QString &targetObjectId, const QString &imageUrl);
    bool storeWebCoverBytes(const QString &targetObjectId, const QByteArray &rawBytes,
                            const QString &sourceUrl, const QString &thumbnailUrl);
    QString readStoredDeviceToken(const QString &scope);
    QString storeAssetFile(const QString &assetId, const QString &ext, const QByteArray &bytes);
    void refreshViews();
    void schedulePush(int ms = 2000);
    void schedulePull(int ms = 1500);
    void handleAssetMeta(const QJsonObject &assetJson);
    void markSyncIdle();
    void notifyObjectListChanged();
    struct ListBatchScope {
        Workspace *ws;
        explicit ListBatchScope(Workspace *w) : ws(w) { ++ws->m_listBatchDepth; }
        ~ListBatchScope() {
            if (--ws->m_listBatchDepth == 0 && ws->m_listDirty) {
                ws->m_listDirty = false;
                ws->notifyObjectListChanged();
            }
        }
    };
    ObjectView viewFor(const Object &o) const;
    static bool taskDoneFor(const Object &o);
    static QString dueDateFor(const Object &o);
    static QString todayIso() { return QDate::currentDate().toString(QStringLiteral("yyyy-MM-dd")); }
    Object *objectPtr(const QString &id);
    QString newSyncOpId() const;     // 32 hex chars of CSPRNG (spec/sync.md op_id)
    QString blindId(const QString &realId) const;
    void syncError(const QString &what);

    SyncClient m_client;
    linkpreview::LinkPreviewFetcher *m_linkPreview = nullptr;
    Store *m_store = nullptr;
    QHash<QString, Object> m_objects;      // authoritative in-memory copy
    QHash<QString, ObjectType> m_types;
    QHash<QString, QString> m_assetMime;   // assetId -> mime
    QHash<QString, EditorModel *> m_editors;
    QSet<QString> m_downloading;
    QSet<QString> m_uploading;

    // ---- vault crypto context (spec/crypto.md) ----
    vault::VaultKeys m_vault;
    QByteArray m_keyObjects, m_keyOps, m_keySnapshots, m_keyAssets, m_keyThumbs,
        m_keyDerived, m_keyIds;
    void deriveSubkeys();
    qint32 keyEpoch() const { return qint32(m_vault.epoch); }

    // ---- local storage context ----
    CacheCipher m_fileCipher;     // seals media cache files at rest
    QString m_deviceId;
    QVariantList m_devices;
    QString m_devicesNote;
    QString m_activeEditingId;
    bool m_shuttingDown = false;
    int m_listRevision = 0;
    int m_listBatchDepth = 0;
    bool m_listDirty = false;
    QString m_cacheDir;
    QString m_assetsDir;       // sealed media
    QString m_plainAssetsDir;  // decrypted session copies, $XDG_RUNTIME_DIR
    QString m_secretScope;     // keychain account suffix for this data directory
    bool m_cacheEncrypted = false;
    bool m_protocolRejected = false; // 426 protocol_mismatch: stop retrying this session
    int m_pendingCount = 0;
    qlonglong m_lastSeq = 0;
    bool m_connected = false;
    bool m_live = false;
    bool m_enrolling = false;
    bool m_syncing = false;
    bool m_resyncRequested = false;
    QString m_lastSyncText;
    QString m_lastError;
    QTimer m_pushDebounce;
    QTimer m_pullDebounce;
    friend class EditorModel;
};

} // namespace pkm
