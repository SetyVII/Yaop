// Desktop app self-test (`yaop --selftest`): storage, onboarding, lock, clipboard.
// The core (model, merge, query, crypto) is tested by pkmcore_tests.
#include "selftest.h"
#include "testing.h"
#include "blockops.h"
#include "model.h"
#include "cachecrypto.h"
#include "capturebridge.h"
#include "linkpreview.h"
#include "onboarding.h"
#include "editormodel.h"
#include "applock.h"
#include "keymap.h"
#include "outbox.h"
#include "searchindex.h"
#include "store.h"
#include "syncclient.h"
#include "spancodec.h"
#include "texrender_internal.h"
#include "workspace_internal.h"
#include "history.h"
#include <QElapsedTimer>
#include <QClipboard>
#include <QGuiApplication>
#include <QMimeData>
#include <QBuffer>
#include <QtEndian>
#include <algorithm>
#include <QCryptographicHash>
#include <QJsonArray>
#include <QDir>
#include <QRegularExpression>
#include <QTemporaryDir>
#include <QFile>
#include <QJsonDocument>
#include <QImage>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QEventLoop>
#include <QTextCharFormat>
#include <QTextCursor>
#include <QTextDocument>
#include <QtGlobal>
#include <cstdio>

namespace pkm::selftest {
using namespace pkm::testing;


static void testMathRender()
{
    tex::Result r = tex::render(QStringLiteral("\\frac{a+b}{c} = \\sqrt{d^2 + e^2}"),
                                24.0, QColor("#F2F2EC"), true, 2.0);
    CHECK(r.ok, "math render ok");
    CHECK(r.width > 40 && r.height > 20, "math sized");
    tex::Result euler = tex::render(QStringLiteral("e^{i\\pi} + 1 = 0"), 22.0, QColor("#FFFFFF"), false, 2.0);
    CHECK(euler.ok, "euler ok");
}

// R2: the outbox collapses superseded puts, keeps first-queued order and chunks under the limit.
static void testOutboxBatches()
{
    auto put = [](qlonglong row, const QString &id, const QString &title) {
        PendingOp op;
        op.rowid = row;
        op.opId = QStringLiteral("op-%1").arg(row);
        op.objectId = id;
        op.payload = QJsonObject{ { "id", id }, { "title", title } };
        return op;
    };
    auto del = [](qlonglong row, const QString &id) {
        PendingOp op;
        op.rowid = row;
        op.opId = QStringLiteral("del-%1").arg(row);
        op.objectId = id;
        op.payload = QJsonObject{ { "object_id", id } };
        return op;
    };
    // A created, B mentions A, A edited twice more, B deleted, B re-created.
    const QList<PendingOp> queue = { put(1, "A", "a1"), put(2, "B", "b1"), put(3, "A", "a2"),
                                     put(4, "A", "a3"), del(5, "B"), put(6, "B", "b2") };
    const auto batches = buildPushBatches(queue, 200);
    CHECK(batches.size() == 1, "outbox: one batch");
    const QList<PushOp> ops = batches.value(0);
    CHECK(ops.size() == 4, "outbox: puts of A collapse; B keeps put/delete/put");
    if (ops.size() == 4) {
        CHECK(ops[0].objectId == "A" && ops[0].wire["object"].toObject()["title"].toString() == "a3",
              "outbox: A first, with its last state");
        CHECK(ops[0].opId == "op-4" && ops[0].rowids == QList<qlonglong>({ 1, 3, 4 }),
              "outbox: collapsed op covers every row");
        CHECK(ops[1].objectId == "B" && ops[1].wire["action"].toString() == "put", "outbox: B put");
        CHECK(ops[2].wire["action"].toString() == "delete", "outbox: B delete stays after its put");
        CHECK(ops[3].wire["object"].toObject()["title"].toString() == "b2", "outbox: B re-created last");
    }
    QList<PendingOp> many;
    for (int i = 0; i < 1201; ++i)
        many.append(put(i + 1, QStringLiteral("o%1").arg(i), QStringLiteral("t")));
    const auto chunks = buildPushBatches(many, 500);
    CHECK(chunks.size() == 3 && chunks.last().size() == 201, "outbox: chunks under the server limit");
}

// R4: live stream reconnect backoff.
static void testLiveBackoff()
{
    CHECK(liveBackoffMs(0, 0.5) == 1000, "backoff: first retry after 1 s");
    CHECK(liveBackoffMs(3, 0.5) == 8000, "backoff: doubles");
    CHECK(liveBackoffMs(30, 0.5) == 60000, "backoff: capped at 60 s");
    CHECK(liveBackoffMs(0, 0.0) == 800 && liveBackoffMs(0, 0.999) <= 1200, "backoff: ±20 % jitter");
}

static void testCacheCipher()
{
    CacheCipher c;
    CHECK(!c.ready(), "cipher: unusable before a key");
    const QByteArray key = CacheCipher::randomKey();
    CHECK(key.size() == CacheCipher::kKeyBytes, "cipher: random key size");
    CHECK(!c.setKey(QByteArray(key.size() - 1, 'x')), "cipher: rejects short key");
    CHECK(c.setKey(key), "cipher: accepts key");
    CHECK(c.ready(), "cipher: ready");

    const QByteArray plain = QByteArrayLiteral("secreto de cache con tildes: \xc3\xa1\xc3\xa9");
    const QByteArray aad = QByteArrayLiteral("media/abc123");
    const QByteArray sealed = c.seal(plain, aad);
    CHECK(!sealed.isEmpty() && sealed != plain, "cipher: seal produces ciphertext");
    CHECK(CacheCipher::isSealed(sealed), "cipher: sealed marker present");
    CHECK(!CacheCipher::isSealed(plain), "cipher: plain is not marked sealed");

    const std::optional<QByteArray> back = c.open(sealed, aad);
    CHECK(back.has_value() && *back == plain, "cipher: open roundtrip");
    CHECK(!c.open(sealed, QByteArrayLiteral("media/other")).has_value(),
          "cipher: wrong AAD rejected");
    QByteArray tampered = sealed;
    tampered[tampered.size() / 2] = char(tampered[tampered.size() / 2] ^ 0x40);
    CHECK(!c.open(tampered, aad).has_value(), "cipher: tampered ciphertext rejected");
    CacheCipher other;
    other.setKey(CacheCipher::randomKey());
    CHECK(!other.open(sealed, aad).has_value(), "cipher: wrong key rejected");
    CHECK(other.seal(plain, aad) != sealed, "cipher: nonce makes seals unique");
}

// spec §15.5: the on-disk database is SQLCipher-sealed, reopens with
// the same key, and the bytes on disk never contain the cleartext. A wrong key
// is not a crash: the file moves aside (nothing deleted) and a fresh encrypted
// database takes its place.
static void testStoreEncryptedAtRest()
{
    QTemporaryDir dir;
    CHECK(dir.isValid(), "store: temp dir");
    const QString path = dir.filePath(QStringLiteral("yaop.db"));
    const QString canaryTitle = QStringLiteral("SecretoDiario-\u00e1\u00e9\u00ed Marco #CANARY-MARKER#");
    const QString canaryId = QStringLiteral("obj-secreto-1");

    const QByteArray keyA = vault::randomBytes(32);
    {
        Store s(path);
        CHECK(s.setKey(keyA), "store: set key");
        CHECK(s.open(), "store: open encrypted");
        CHECK(s.encryptedAtRest(), "store: reports encryption at rest");
        Object o;
        o.id = canaryId;
        o.title = canaryTitle;
        Block b = Block::makeParagraph(QStringLiteral("p1"));
        b.text = QStringLiteral("texto privado con MARKER tambi\u00e9n");
        o.blocks = { b };
        s.putObject(o);
        s.setKv(QStringLiteral("cursor"), QStringLiteral("last-seq-value"));
        CHECK(s.hasObject(canaryId), "store: object written");
    }
    {
        // closed: the file on disk (and its WAL sibling if any) must not leak
        const QByteArray dbBytes = readFile(path);
        CHECK(!dbBytes.isEmpty(), "store: db file exists");
        CHECK(!dbBytes.contains("SQLite format 3"), "store: no sqlite magic header in clear");
        CHECK(!dbBytes.contains(canaryTitle.toUtf8()), "store: title not in clear");
        CHECK(!dbBytes.contains("texto privado"), "store: block text not in clear");
        CHECK(!dbBytes.contains("CANARY-MARKER"), "store: canary marker not in clear");
        for (const QString &sibling : { QStringLiteral("-wal"), QStringLiteral("-shm") }) {
            const QString p = path + sibling;
            if (!QFile::exists(p))
                continue;
            CHECK(!readFile(p).contains("CANARY-MARKER"), "store: no canary in WAL sibling");
        }
    }
    {
        Store s(path);
        CHECK(s.setKey(keyA), "store: same key accepted");
        CHECK(s.open(), "store: reopen with the key");
        const std::optional<Object> o = s.object(canaryId);
        CHECK(o.has_value() && o->title == canaryTitle, "store: object readable after reopen");
        CHECK(s.kv(QStringLiteral("cursor")) == QLatin1String("last-seq-value"),
              "store: kv readable after reopen");
        CHECK(!QFile::exists(path + QStringLiteral(".legacy-v1.bak")),
              "store: no backup when the key matches");
    }
    {
        // wrong key: old file moves aside (kept!), fresh empty database in place
        Store s(path);
        CHECK(s.setKey(vault::randomBytes(32)), "store: other key accepted");
        CHECK(s.open(), "store: opens fresh after key mismatch");
        CHECK(!s.hasObject(canaryId), "store: wrong key cannot read the data");
        const QString bak = path + QStringLiteral(".legacy-v1.bak");
        CHECK(QFile::exists(bak), "store: mismatched database moved aside, not deleted");
        const QByteArray bakBytes = readFile(bak);
        CHECK(!bakBytes.contains(canaryTitle.toUtf8()), "store: backup is sealed too");
    }
    {
        // cleartext file: moved aside on open, fresh database created
        const QString legacyPath = dir.filePath(QStringLiteral("old-clear.db"));
        {
            QFile f(legacyPath);
            CHECK(f.open(QIODevice::WriteOnly), "store: legacy file created");
            f.write(QByteArrayLiteral("some old plaintext database bytes"));
        }
        Store s(legacyPath);
        CHECK(s.setKey(keyA), "store: key for legacy probe");
        CHECK(s.open(), "store: legacy moved aside, fresh open");
        CHECK(!s.hasObject(canaryId), "store: fresh database is empty");
        CHECK(QFile::exists(legacyPath + QStringLiteral(".legacy-v1.bak")), "store: legacy file kept as .bak");
    }
    {
        Store mem(QStringLiteral(":memory:"));
        CHECK(mem.setKey(vault::randomBytes(32)) && mem.open(), "store: memory store opens");
        Object o;
        o.id = QStringLiteral("mem-1");
        o.title = QStringLiteral("temporal");
        mem.putObject(o);
        CHECK(mem.hasObject(QStringLiteral("mem-1")), "store: memory store roundtrip");
    }
}

// spec §4.5 / §17.4: search and the references view are local-only. The index
// lives in the encrypted store, is updated in the save transaction, folds
// diacritics, matches a prefix on the last term, and drives backlinks rows with
// the source block id (title + snippet of the origin come from that row).
static void testSearchAndBacklinks()
{
    QTemporaryDir dir;
    CHECK(dir.isValid(), "search: temp dir");
    Store store(dir.filePath(QStringLiteral("search.db")));
    CHECK(store.setKey(vault::randomBytes(32)) && store.open(), "search: store opens");
    const bool fts5 = store.usingFts5();
    std::printf("SEARCH-INDEX fts5=%d\n", fts5 ? 1 : 0);

    Object marcos;
    marcos.id = QStringLiteral("person-1");
    marcos.typeId = TypeId::Person;
    marcos.title = QStringLiteral("Marcos Vidal");

    Object acta;
    acta.id = QStringLiteral("note-acta");
    acta.title = QStringLiteral("Acta de reuni\u00f3n");
    Block a1 = Block::makeParagraph(QStringLiteral("a1"));
    a1.text = QStringLiteral("temas de hoy");

    Object proj;
    proj.id = QStringLiteral("note-proj");
    proj.title = QStringLiteral("Proyecto alfa");
    Run plain;
    plain.text = QStringLiteral("Ver con ");
    Run mention;
    mention.text = QStringLiteral("Marcos Vidal");
    mention.objectId = marcos.id;
    Block b1 = Block::makeParagraph(QStringLiteral("b1"), { plain, mention });
    Run tag;
    tag.text = QStringLiteral("#mi_tema");
    tag.tagId = QStringLiteral("tag_mi_tema");
    Block b2 = Block::makeParagraph(QStringLiteral("b2"), { tag });
    proj.blocks = { b1, b2 };

    store.putObject(marcos);
    store.putObject(acta);
    store.putObject(proj);

    const QStringList reunion = store.searchIds(QStringLiteral("reunion"), 50);
    CHECK(reunion.contains(acta.id), "search: diacritic fold (reuni\u00f3n -> reunion)");
    const QStringList actaDe = store.searchIds(QStringLiteral("acta de"), 50);
    CHECK(actaDe.contains(acta.id), "search: AND over terms");
    const QStringList proy = store.searchIds(QStringLiteral("proy"), 50);
    CHECK(proy.contains(proj.id), "search: prefix on the last term");
    const QStringList marcosHit = store.searchIds(QStringLiteral("Marcos"), 50);
    CHECK(marcosHit.contains(proj.id) && marcosHit.contains(marcos.id),
          "search: mention text and title both indexed");

    // backlinks rows: mention lands on b1, the tag run on b2
    const QList<pkm::search::SourceRef> toMarcos = store.backlinkRows(marcos.id);
    CHECK(toMarcos.size() == 1 && toMarcos[0].src == proj.id && toMarcos[0].blockId == QLatin1String("b1"),
          "search: mention backlink row with its block");
    const QList<pkm::search::SourceRef> toTag = store.backlinkRows(QStringLiteral("tag_mi_tema"));
    CHECK(toTag.size() == 1 && toTag[0].src == proj.id && toTag[0].blockId == QLatin1String("b2"),
          "search: tag backlink row with its block");
    const QList<pkm::search::SourceRef> none = store.backlinkRows(QStringLiteral("person-nobody"));
    CHECK(none.isEmpty(), "search: no rows for unknown target");

    // the snippet the view shows comes straight from the source block
    const std::optional<Object> src = store.object(proj.id);
    CHECK(src.has_value(), "search: source readable");
    bool snippetFound = false;
    for (const Block &b : src->blocks) {
        if (b.id == QLatin1String("b1")) {
            snippetFound = b.plainText().contains(QLatin1String("Ver con @Marcos Vidal"));
        }
    }
    CHECK(snippetFound, "search: backlink snippet reconstructs the block text");

    // trashing drops the object from the search index but keeps its ghost in
    // the store; references from other objects stay.
    Object trashed = marcos;
    trashed.trashedAt = QStringLiteral("2026-11-01T00:00:00Z");
    store.putObject(trashed);
    const QStringList afterTrash = store.searchIds(QStringLiteral("Marcos"), 50);
    CHECK(!afterTrash.contains(marcos.id), "search: trashed object leaves the index");
    CHECK(afterTrash.contains(proj.id), "search: other objects stay searchable");
    CHECK(store.backlinkRows(marcos.id).size() == 1, "search: backlink rows survive a trash elsewhere");
    const QList<Object> all = store.allObjects();
    CHECK(all.size() == 3, "search: trashed objects kept as ghosts");

    // deleting the source object removes its backlink rows too
    store.removeObject(proj.id);
    CHECK(store.backlinkRows(marcos.id).isEmpty(), "search: rows gone when the source is dropped");
    CHECK(!store.searchIds(QStringLiteral("proy"), 50).contains(proj.id),
          "search: removed object leaves the index");
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// onboarding: onboarding rules and VK persistence
// ---------------------------------------------------------------------------

static void testOnboarding()
{
    const auto codec = bip39::Codec::make(bip39::embeddedWordlist());
    CHECK(codec.has_value(), "onboarding: wordlist loads");
    if (!codec)
        return;

    // 3 distinct ascending indices inside the 24 words, every time.
    for (int i = 0; i < 50; ++i) {
        const QList<int> idx = VaultOnboarding::pickIndices(3, bip39::kPhraseWords);
        CHECK(idx.size() == 3 && idx[0] < idx[1] && idx[1] < idx[2] && idx[0] >= 0 && idx[2] < 24,
              "onboarding: confirmation indices are 3 distinct ascending words");
    }

    const QByteArray entropy = vault::randomBytes(32);
    const QString phrase = codec->entropyToMnemonic(entropy);
    QByteArray got;
    CHECK(VaultOnboarding::classify(phrase.toUpper() + QStringLiteral("  "), *codec, &got) == VaultOnboarding::RestoreOk
              && got == entropy,
          "onboarding: restore accepts case/space-insensitive valid phrases");
    CHECK(VaultOnboarding::classify(QStringLiteral("abandon abandon"), *codec, nullptr) == VaultOnboarding::WordCount,
          "onboarding: restore classifies a wrong word count");
    QStringList words = phrase.split(QLatin1Char(' '));
    QStringList unknown = words;
    unknown[5] = QStringLiteral("notaword");
    CHECK(VaultOnboarding::classify(unknown.join(QLatin1Char(' ')), *codec, nullptr) == VaultOnboarding::UnknownWord,
          "onboarding: restore classifies an unknown word");
    QStringList bad = words;
    bad[23] = (bad[23] == QLatin1String("abandon")) ? QStringLiteral("ability") : QStringLiteral("abandon");
    CHECK(VaultOnboarding::classify(bad.join(QLatin1Char(' ')), *codec, nullptr) == VaultOnboarding::Checksum,
          "onboarding: restore classifies a checksum mismatch");

    // Create flow: the confirmation passes only with the right words.
    QTemporaryDir tmp;
    VaultOnboarding onb(QStringLiteral("vault:selftest"), tmp.path() + QStringLiteral("/vault.key"));
    onb.startCreate();
    const QStringList created = onb.phrase().split(QLatin1Char(' '));
    CHECK(created.size() == 24, "onboarding: create shows 24 words");
    QStringList right;
    for (const QVariant &i : onb.confirmIndices())
        right << created.at(i.toInt());
    CHECK(onb.checkWords(right), "onboarding: confirmation accepts the right words");
    CHECK(!onb.checkWords({ QStringLiteral("x"), QStringLiteral("y"), QStringLiteral("z") }),
          "onboarding: confirmation rejects wrong words");

    // VK persistence through the passphrase-wrapped file (no keychain needed in CI).
    qputenv("YAOP_VAULT_PASSPHRASE", QByteArrayLiteral("selftest-passphrase"));
    const QString file = tmp.path() + QStringLiteral("/wrapped.key");
    CHECK(vault::loadVaultKey(keychainSecrets(), QStringLiteral("vault:none"), file).vk.isEmpty(),
          "onboarding: loadVaultKey never creates a key");
    const auto stored = vault::storeVaultKey(keychainSecrets(), QStringLiteral("vault:none"), file, entropy, 3);
    CHECK(stored.persistent() && stored.source == vault::VaultKeys::Source::WrappedFile,
          "onboarding: storeVaultKey persists through the wrapped file");
    const auto loaded = vault::loadVaultKey(keychainSecrets(), QStringLiteral("vault:none"), file);
    CHECK(loaded.vk == entropy && loaded.epoch == 3, "onboarding: stored VK and epoch load back");
    qunsetenv("YAOP_VAULT_PASSPHRASE");

    // Pairing fingerprint: sha256 hex of the SPKI DER.
    CHECK(PairingFlow::fingerprintOf(QByteArrayLiteral("abc"))
              == QStringLiteral("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
          "pairing: tls fingerprint is lowercase sha256 hex");
}

static void testAppLock()
{
    const QString h = vault::hashPin(QStringLiteral("2468"));
    CHECK(!h.isEmpty() && !h.contains(QStringLiteral("2468")), "lock: the stored PIN hash never contains the PIN");
    CHECK(vault::verifyPin(QStringLiteral("2468"), h), "lock: the right PIN verifies");
    CHECK(!vault::verifyPin(QStringLiteral("2469"), h), "lock: a wrong PIN is refused");
    CHECK(!vault::verifyPin(QString(), h) && !vault::verifyPin(QStringLiteral("2468"), QString()),
          "lock: empty PIN or empty hash never verifies");
    CHECK(vault::hashPin(QStringLiteral("2468")) != h, "lock: hashes are salted");
    CHECK(AppLock::backoffSeconds(0) == 0 && AppLock::backoffSeconds(2) == 0, "lock: first failures are free");
    CHECK(AppLock::backoffSeconds(3) == 1 && AppLock::backoffSeconds(5) == 4 && AppLock::backoffSeconds(40) == 60,
          "lock: back-off doubles and caps at 60 s");
    CHECK(AppLock::idleExpired(5 * 60000, 5) && !AppLock::idleExpired(5 * 60000 - 1, 5),
          "lock: idle limit is inclusive of the configured minutes");
    CHECK(!AppLock::idleExpired(1 << 30, 0), "lock: timeout 0 never auto-locks");
}

static void testMultiBlockClipboard()
{
    // 1. Inline markdown spans + blocksToHtml + htmlToBlocks
    const Blocks mdBlocks = markdownToBlocks(QStringLiteral(
        "# Encabezado\n\nTexto con **negrita**, *cursiva*, `codigo` y [enlace](https://example.com).\n\n- item uno\n- item dos\n\n1. primero\n2. segundo\n\n> cita\n\n```python\nprint(1)\n```\n\n---"));
    CHECK(mdBlocks.size() >= 8, "md multi-block count");
    CHECK(mdBlocks[1].runs.size() >= 6, "md inline spans parsed");
    bool sawBold = false, sawLink = false;
    for (const Run &r : mdBlocks[1].runs) {
        if (r.bold && r.text == QLatin1String("negrita"))
            sawBold = true;
        if (r.href == QLatin1String("https://example.com") && r.text == QLatin1String("enlace"))
            sawLink = true;
    }
    CHECK(sawBold && sawLink, "md inline bold & link");

    const QString html = blocksToHtml(mdBlocks);
    CHECK(html.contains(QLatin1String("<h1>Encabezado</h1>")), "html heading");
    CHECK(html.contains(QLatin1String("<strong>negrita</strong>")), "html bold");
    CHECK(html.contains(QLatin1String("<em>cursiva</em>")), "html italic");
    CHECK(html.contains(QLatin1String("<code>codigo</code>")), "html code");
    CHECK(html.contains(QLatin1String("<a href=\"https://example.com\">enlace</a>")), "html link");
    CHECK(html.contains(QLatin1String("<ul>")) && html.contains(QLatin1String("<ol>")), "html lists");
    CHECK(html.contains(QLatin1String("<blockquote>")), "html quote");
    CHECK(html.contains(QLatin1String("<pre><code class=\"language-python\">")), "html code fence");
    CHECK(html.contains(QLatin1String("<hr>")), "html divider");

    const Blocks fromHtml = htmlToBlocks(QStringLiteral(
        "<h2>Subtitulo</h2><p>Hola <b>mundo</b> <a href=\"https://a.test\">link</a></p><ul><li>A</li><li>B</li></ul>"));
    CHECK(fromHtml.size() == 4 && fromHtml[0].type == BlockType::Heading && fromHtml[0].level == 2,
          "htmlToBlocks heading");
    CHECK(fromHtml[2].type == BlockType::BulletList && fromHtml[3].type == BlockType::BulletList,
          "htmlToBlocks bullet list");

    // 2. EditorModel multi-block selection + copy/cut/paste
    Object obj;
    obj.id = QStringLiteral("obj-clip");
    obj.typeId = TypeId::Note;
    obj.blocks = mdBlocks;
    EditorModel ed(nullptr);
    ed.load(obj);
    const int initialCount = ed.rowCount();
    ed.selectRange(0, 2);
    CHECK(ed.selectedCount() == 3, "selectRange selects 3 blocks");
    ed.toggleSelected(ed.selectedIds().first());
    CHECK(ed.selectedCount() == 2, "toggleSelected removes 1 block");
    ed.selectAllBlocks();
    CHECK(ed.selectedCount() == initialCount, "selectAllBlocks selects all");

    ed.selectRange(0, 1);
    CHECK(ed.copySelected(false), "copySelected succeeds");
    const QMimeData *clip = QGuiApplication::clipboard()->mimeData();
    CHECK(clip && clip->hasText() && clip->hasHtml()
              && clip->hasFormat(QStringLiteral("application/x-yaop-blocks")),
          "clipboard carries markdown, html and native yaop blocks");
    CHECK(clip->text().contains(QLatin1String("# Encabezado")), "clipboard text is markdown");
    CHECK(clip->html().contains(QLatin1String("<h1>Encabezado</h1>")), "clipboard html is semantic");

    // Paste copied blocks into an empty paragraph on another EditorModel
    Object targetObj;
    targetObj.id = QStringLiteral("obj-target");
    targetObj.typeId = TypeId::Note;
    targetObj.blocks = { Block::makeParagraph(QStringLiteral("empty-1")) };
    EditorModel target(nullptr);
    target.load(targetObj);
    CHECK(target.pasteFromClipboard(0, 0, nullptr), "paste native blocks into empty doc");
    CHECK(target.rowCount() == 2, "pasted 2 blocks");

    // Cut first block from target
    target.selectRange(0, 0);
    CHECK(target.copySelected(true), "cut 1 block");
    CHECK(target.rowCount() == 1 && target.selectedCount() == 0, "cut removed block and cleared selection");

    // Paste Markdown from clipboard into target
    auto *mdMime = new QMimeData();
    mdMime->setText(QStringLiteral("## Pegado\n\n- uno\n- dos"));
    QGuiApplication::clipboard()->setMimeData(mdMime);
    CHECK(target.pasteFromClipboard(0, 0, nullptr), "paste markdown multiline");
    CHECK(target.rowCount() >= 3, "markdown paste expanded into multiple blocks");
}

void testDesktopIncrementalAndSchemas()
{
    // 1. Custom type schema + properties round-trip (wire contract)
    ObjectType custom;
    custom.id = QStringLiteral("type_bug");
    custom.name = QStringLiteral("Bug");
    custom.color = QStringLiteral("red");
    custom.standard = false;
    custom.propertyDefs = QJsonArray{
        QJsonObject{ { "id", "prop_severity" }, { "key", "severity" }, { "name", "Severity" }, { "kind", "text" } }
    };
    const ObjectType rt = ObjectType::fromJson(custom.toJson());
    CHECK(rt.id == QLatin1String("type_bug") && rt.propertyDefs.size() == 1,
          "custom type propertyDefs survive wire roundtrip");

    // 2. Incremental EditorModel indent/split/backspace preserve row identity & focus
    Object obj;
    obj.id = QStringLiteral("inc-1");
    obj.typeId = TypeId::Note;
    Block b1 = blockops::convertBlockType(Block::makeParagraph(QStringLiteral("b1"), { Run{ QStringLiteral("first") } }), BlockKey::Bullet);
    Block b2 = blockops::convertBlockType(Block::makeParagraph(QStringLiteral("b2"), { Run{ QStringLiteral("second") } }), BlockKey::Bullet);
    obj.blocks = { b1, b2 };

    EditorModel ed(nullptr);
    ed.load(obj);
    int resetCount = 0;
    QObject::connect(&ed, &QAbstractItemModel::modelReset, [&]() { ++resetCount; });

    ed.indentBlocks(1, 1, 3);
    CHECK(ed.data(ed.index(1), EditorModel::IndentRole).toInt() == 1, "indentBlocks indents without losing list item");
    CHECK(resetCount == 0, "indentBlocks avoids destructive modelReset");
    CHECK(ed.focusBlockId() == QLatin1String("b2") && ed.focusPosition() == 3,
          "indentBlocks preserves block focus and caret");

    const int nextRow = ed.handleEnter(1, 3, nullptr);
    CHECK(nextRow == 2 && ed.rowCount() == 3 && resetCount == 0,
          "handleEnter inserts incrementally without modelReset");

    ed.handleBackspace(2, 0, nullptr); // indent 1 -> 0
    CHECK(ed.rowCount() == 3 && ed.data(ed.index(2), EditorModel::IndentRole).toInt() == 0 && resetCount == 0,
          "handleBackspace outdents incrementally without modelReset");

    ed.handleBackspace(2, 0, nullptr); // Bullet -> Paragraph
    CHECK(ed.rowCount() == 3 && ed.data(ed.index(2), EditorModel::TypeRole).toString() == QLatin1String("paragraph") && resetCount == 0,
          "handleBackspace demotes incrementally without modelReset");

    ed.handleBackspace(2, 0, nullptr); // Merge with previous block
    CHECK(ed.rowCount() == 2 && resetCount == 0,
          "handleBackspace merges incrementally without modelReset");

    ed.removeBlockAt(0);
    CHECK(ed.rowCount() == 1 && resetCount == 0, "removeBlockAt removes block incrementally");
}

static void testWebAndTags()
{
    QTemporaryDir dir;
    CHECK(dir.isValid(), "web/tags: tempdir");
    Store store(dir.filePath(QStringLiteral("cache.db")));
    CHECK(store.setKey(vault::randomBytes(32)) && store.open(), "web/tags: store opens");

    Object web;
    web.id = QStringLiteral("web-1");
    web.typeId = TypeId::Web;
    web.title = QStringLiteral("GLM Coding Plan");
    web.properties.insert(QStringLiteral("url"), QStringLiteral("https://www.z.ai/subscribe"));
    web.properties.insert(QStringLiteral("description"), QStringLiteral("Use GLM models for AI coding."));
    web.properties.insert(QStringLiteral("favicon_url"), QStringLiteral("https://z.ai/favicon.ico"));
    web.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("AI"), QStringLiteral("Coding") });
    store.putObject(web);

    CHECK(store.backlinkRows(QStringLiteral("tag_ai")).size() == 1
              && store.backlinkRows(QStringLiteral("tag_ai"))[0].src == QLatin1String("web-1"),
          "tags property creates backlink to tag_ai");
    CHECK(store.backlinkRows(QStringLiteral("tag_coding")).size() == 1,
          "tags property creates backlink to tag_coding");

    EditorModel ed(nullptr);
    ed.load(web);
    CHECK(ed.url() == QLatin1String("https://www.z.ai/subscribe"), "EditorModel::url reads property");
    CHECK(ed.domain() == QLatin1String("z.ai"), "EditorModel::domain strips www");
    CHECK(ed.description() == QLatin1String("Use GLM models for AI coding."), "EditorModel::description reads property");
    CHECK(ed.faviconUrl() == QLatin1String("https://z.ai/favicon.ico"), "EditorModel::faviconUrl reads property");

    Object webFromBlock;
    webFromBlock.id = QStringLiteral("web-2");
    webFromBlock.typeId = TypeId::Web;
    Block bm;
    bm.id = QStringLiteral("bm-1");
    bm.type = BlockType::Bookmark;
    bm.url = QStringLiteral("https://www.youtube.com/watch?v=1VaSXlQnHfA");
    bm.description = QStringLiteral("Video desc");
    bm.faviconUrl = QStringLiteral("https://youtube.com/favicon.ico");
    webFromBlock.blocks = { bm };
    ed.load(webFromBlock);
    CHECK(ed.url() == bm.url && ed.domain() == QLatin1String("youtube.com")
              && ed.description() == bm.description && ed.faviconUrl() == bm.faviconUrl,
          "EditorModel web metadata falls back to Bookmark block");
}


// spec/types.md on the desktop: standard types get their properties; a synced copy keeps its own.
static void testStandardTypeSeeding()
{
    QHash<QString, ObjectType> types;
    ObjectType syncedBook;
    syncedBook.id = QStringLiteral("book");
    syncedBook.name = QStringLiteral("My books");
    syncedBook.propertyDefs = QJsonArray{ QJsonObject{ { "key", "shelf" }, { "name", "Shelf" }, { "kind", "text" } } };
    types.insert(syncedBook.id, syncedBook);
    pkm::detail::seedStandardTypes(types, QStringLiteral("es"));

    CHECK(types.contains(QStringLiteral("web")) && types.value(QStringLiteral("web")).name == QStringLiteral("Enlace web"),
          "standard web type seeded with its Spanish name");
    QJsonArray webCatOptions;
    for (const QJsonValue &v : types.value(QStringLiteral("web")).propertyDefs)
        if (v.toObject().value("key").toString() == QLatin1String("category"))
            webCatOptions = v.toObject().value("options").toArray();
    CHECK(webCatOptions.size() == 30, "web category has its 30 options");

    const ObjectType book = types.value(QStringLiteral("book"));
    CHECK(book.name == QStringLiteral("My books"), "synced book type keeps its name");
    CHECK(book.propertyDefs.first().toObject().value("key").toString() == QLatin1String("author")
              && book.propertyDefs.last().toObject().value("key").toString() == QLatin1String("shelf"),
          "synced book type: standard properties first, its own after");

    const QVariantList sel = pkm::detail::selectedOptions(QJsonArray{ QStringLiteral("article"), QStringLiteral("future_kind") },
                                                          webCatOptions);
    CHECK(sel.size() == 2 && sel[0].toMap().value("label").toString() == QStringLiteral("Artículo")
              && sel[1].toMap().value("label").toString() == QLatin1String("future_kind"),
          "known option ids get their label, unknown ids stay as they came");
}


// Version history storage: compressed snapshots round-trip and can be purged (product §16.3).
static void testSnapshotStore()
{
    QTemporaryDir dir;
    Store store(dir.filePath(QStringLiteral("cache.db")));
    CHECK(store.setKey(vault::randomBytes(32)) && store.open(), "history: store opens");
    Object o;
    o.id = QStringLiteral("o1");
    o.title = QStringLiteral("Plan");
    o.blocks = { Block::makeParagraph(QStringLiteral("b1")) };
    const QByteArray content = pkm::history::snapshotContent(o);
    store.putSnapshot(o.id, QStringLiteral("2026-10-01T00:00:00.000Z"), vault::zstdCompress(content));
    store.putSnapshot(o.id, QStringLiteral("2026-11-21T00:00:00.000Z"), vault::zstdCompress(content));
    const QVector<Store::SnapshotRow> rows = store.snapshots(o.id);
    const std::optional<QByteArray> back = rows.isEmpty() ? std::nullopt : vault::zstdDecompress(rows.first().compressed);
    CHECK(rows.size() == 2 && back && *back == content && rows.first().createdAt.startsWith(QLatin1String("2026-11-21")),
          "history: snapshots come back decompressed, newest first");
    QVector<pkm::history::SnapshotMeta> metas;
    for (const Store::SnapshotRow &r : store.allSnapshotTimes())
        metas.append({ r.id, r.createdAt });
    store.deleteSnapshots(pkm::history::expiredSnapshots(metas, QStringLiteral("2026-11-22T00:00:00.000Z")));
    CHECK(store.snapshots(o.id).size() == 1, "history: snapshots older than 30 days are purged");
}

static void testKeymap()
{
    CHECK(findKeyConflict(keyBindings()).isEmpty(), "keymap: no key bound twice in one scope");
    if (const QString c = findKeyConflict(keyBindings()); !c.isEmpty())
        std::printf("     %s\n", qPrintable(c));
    std::vector<KeyBinding> dup = { { "a", { "Ctrl+K" }, "global", "A" }, { "b", { "ctrl+k" }, "global", "B" } };
    CHECK(!findKeyConflict(dup).isEmpty(), "keymap: duplicate keys in one scope are caught");
    dup[1].scope = QStringLiteral("editor");
    CHECK(findKeyConflict(dup).isEmpty(), "keymap: the same keys in another scope are fine");
    bool titled = true;
    for (const KeyBinding &b : keyBindings())
        titled = titled && !b.id.isEmpty() && !b.title.isEmpty() && !b.keys.isEmpty();
    CHECK(titled, "keymap: every binding has an id, a title and keys");

    Keymap km;
    CHECK(km.owns(QStringLiteral("editor"), Qt::Key_D, Qt::ControlModifier), "keymap: editor owns Ctrl+D");
    CHECK(!km.owns(QStringLiteral("editor"), Qt::Key_K, Qt::ControlModifier), "keymap: Ctrl+K stays global");
    CHECK(km.owns(QStringLiteral("blocks"), Qt::Key_Backtab, Qt::ShiftModifier), "keymap: Backtab matches Shift+Tab");
    CHECK(!km.owns(QStringLiteral("editor"), Qt::Key_H, Qt::ControlModifier | Qt::ShiftModifier),
          "keymap: Ctrl+Shift+H stays the privacy key");
}

static void testEditorNoirActions()
{
    Object o;
    o.id = QStringLiteral("obj-noir");
    o.typeId = TypeId::Note;
    o.title = QStringLiteral("Noir test");
    Block embed;
    embed.id = QStringLiteral("b-embed");
    embed.type = BlockType::Embed;
    embed.objectId = QStringLiteral("target-1");
    embed.viewMode = QStringLiteral("card");
    o.blocks = { embed };

    EditorModel ed(nullptr);
    ed.load(o);

    ed.setEmbedViewMode(0, QStringLiteral("wide"));
    CHECK(ed.data(ed.index(0), EditorModel::EmbedViewModeRole).toString() == QLatin1String("wide"),
          "setEmbedViewMode updates view mode to wide");
    ed.setEmbedViewMode(0, QStringLiteral("full"));
    CHECK(ed.data(ed.index(0), EditorModel::EmbedViewModeRole).toString() == QLatin1String("full"),
          "setEmbedViewMode updates view mode to full");
    ed.setEmbedViewMode(0, QStringLiteral("bogus"));
    CHECK(ed.data(ed.index(0), EditorModel::EmbedViewModeRole).toString() == QLatin1String("full"),
          "setEmbedViewMode ignores invalid modes");
}

static void testWebPEncoding()
{
    // 1. Large PNG -> downscaled WebP <= 480 px
    QImage big(800, 600, QImage::Format_RGBA8888);
    big.fill(QColor(0x28, 0x64, 0xCC));
    QByteArray pngBytes;
    {
        QBuffer buf(&pngBytes);
        buf.open(QIODevice::WriteOnly);
        big.save(&buf, "PNG");
    }
    const auto enc = pkm::detail::encodeImageAsWebP(pngBytes, 480, 75, /*rejectTiny=*/true);
    CHECK(enc.ok, "webp: encodes PNG to compressed cover");
    CHECK(enc.mime == QLatin1String("image/webp") && enc.ext == QLatin1String(".webp"),
          "webp: emits image/webp and .webp");
    CHECK(enc.width == 480 && enc.height == 360, "webp: preserves aspect ratio at 480px max side");
    CHECK(enc.bytes.startsWith("RIFF") && enc.bytes.mid(8, 4) == "WEBP", "webp: valid RIFF WEBP header");

    // 2. Tiny tracking pixel (<32px) rejected for web covers, allowed for non-cover
    QImage tiny(16, 16, QImage::Format_RGBA8888);
    tiny.fill(Qt::red);
    QByteArray tinyPng;
    {
        QBuffer buf(&tinyPng);
        buf.open(QIODevice::WriteOnly);
        tiny.save(&buf, "PNG");
    }
    CHECK(!pkm::detail::encodeImageAsWebP(tinyPng, 480, 75, /*rejectTiny=*/true).ok,
          "webp: rejects <32px tracking pixel for covers");
    CHECK(pkm::detail::encodeImageAsWebP(tinyPng, 1920, 82, /*rejectTiny=*/false).ok,
          "webp: allows small images when rejectTiny is false");

    // 3. Already-WebP image within maxSide passes through without re-encoding
    const auto passthrough = pkm::detail::encodeImageAsWebP(enc.bytes, 480, 75, /*rejectTiny=*/true);
    CHECK(passthrough.ok && passthrough.bytes == enc.bytes,
          "webp: preserves already-sized WebP bytes without double lossy pass");
}

static void testCaptureBridge()
{
    CHECK(CaptureBridge::isAllowedOrigin(QString()), "bridge: empty origin allowed (local client)");
    CHECK(CaptureBridge::isAllowedOrigin(QStringLiteral("chrome-extension://abcdefghijklmnop")),
          "bridge: chrome-extension origin allowed");
    CHECK(CaptureBridge::isAllowedOrigin(QStringLiteral("moz-extension://1234-5678")),
          "bridge: moz-extension origin allowed");
    CHECK(!CaptureBridge::isAllowedOrigin(QStringLiteral("https://evil.example")),
          "bridge: web origin blocked");
    CHECK(!CaptureBridge::isAllowedOrigin(QStringLiteral("http://localhost:3000")),
          "bridge: arbitrary localhost web page blocked");
    CHECK(CaptureBridge::constantTimeEquals("secret123", "secret123")
              && !CaptureBridge::constantTimeEquals("secret123", "secret124")
              && !CaptureBridge::constantTimeEquals("", ""),
          "bridge: constant-time token compare");

    // Isolated temporary profile so selftest never touches real user data or keychain.
    QTemporaryDir tmp;
    const QByteArray oldData = qgetenv("XDG_DATA_HOME");
    const QByteArray oldCfg = qgetenv("XDG_CONFIG_HOME");
    const QByteArray oldSvc = qgetenv("YAOP_KEYCHAIN_SERVICE");
    const QByteArray oldPass = qgetenv("YAOP_VAULT_PASSPHRASE");
    qputenv("XDG_DATA_HOME", tmp.filePath(QStringLiteral("data")).toUtf8());
    qputenv("XDG_CONFIG_HOME", tmp.filePath(QStringLiteral("cfg")).toUtf8());
    qputenv("YAOP_KEYCHAIN_SERVICE", "YAOP-selftest-bridge");
    qputenv("YAOP_VAULT_PASSPHRASE", "selftest-passphrase");
    auto restoreEnv = [&]() {
        if (oldData.isEmpty()) qunsetenv("XDG_DATA_HOME"); else qputenv("XDG_DATA_HOME", oldData);
        if (oldCfg.isEmpty()) qunsetenv("XDG_CONFIG_HOME"); else qputenv("XDG_CONFIG_HOME", oldCfg);
        if (oldSvc.isEmpty()) qunsetenv("YAOP_KEYCHAIN_SERVICE"); else qputenv("YAOP_KEYCHAIN_SERVICE", oldSvc);
        if (oldPass.isEmpty()) qunsetenv("YAOP_VAULT_PASSPHRASE"); else qputenv("YAOP_VAULT_PASSPHRASE", oldPass);
    };

    {
        Workspace ws;
        CaptureBridge bridge(&ws);
        CHECK(bridge.start(0) && bridge.listenPort() > 0, "bridge: binds to ephemeral 127.0.0.1 port");
        const QString base = QStringLiteral("http://127.0.0.1:%1").arg(bridge.listenPort());
        const QByteArray authHeader = QByteArrayLiteral("Bearer ") + bridge.token().toUtf8();
        QNetworkAccessManager nam;

        auto sendReq = [&](const QByteArray &method, const QString &path, const QByteArray &body,
                           const QByteArray &auth, const QByteArray &origin, int *statusOut) -> QJsonObject {
            QNetworkRequest req(QUrl(base + path));
            if (!auth.isEmpty())
                req.setRawHeader("Authorization", auth);
            if (!origin.isEmpty())
                req.setRawHeader("Origin", origin);
            if (!body.isEmpty())
                req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
            QNetworkReply *reply = nullptr;
            if (method == "GET")
                reply = nam.get(req);
            else if (method == "POST")
                reply = nam.post(req, body);
            else
                reply = nam.sendCustomRequest(req, method, body);
            QEventLoop loop;
            QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
            loop.exec();
            if (statusOut)
                *statusOut = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            const QByteArray raw = reply->readAll();
            reply->deleteLater();
            return QJsonDocument::fromJson(raw).object();
        };

        int st = 0;
        sendReq("GET", QStringLiteral("/v1/ping"), {}, {}, {}, &st);
        CHECK(st == 401, "bridge: rejects missing token with 401");

        sendReq("GET", QStringLiteral("/v1/ping"), {}, authHeader, "https://evil.example", &st);
        CHECK(st == 403, "bridge: rejects web Origin with 403");

        sendReq("OPTIONS", QStringLiteral("/v1/capture/weblink"), {}, {}, "chrome-extension://testext", &st);
        CHECK(st == 204, "bridge: OPTIONS preflight succeeds for chrome-extension origin");

        const QJsonObject ping = sendReq("GET", QStringLiteral("/v1/ping"), {}, authHeader,
                                         "chrome-extension://testext", &st);
        CHECK(st == 200 && ping.value("ok").toBool() && ping.value("protocol").toInt() == 1,
              "bridge: GET /v1/ping ok");

        // Prepare a 640x400 PNG sample for cover + standalone image capture
        QImage sample(640, 400, QImage::Format_RGBA8888);
        sample.fill(QColor(0x30, 0xA0, 0x70));
        QByteArray samplePng;
        {
            QBuffer buf(&samplePng);
            buf.open(QIODevice::WriteOnly);
            sample.save(&buf, "PNG");
        }
        const QString sampleB64 = QString::fromLatin1(samplePng.toBase64());

        // Capture a WebLink with cover (converted to WebP), highlight, note, explicit tag,
        // and a #hashtag inside description (must NOT become a tag per product §4.5).
        const QJsonObject wlReq{
            { "url", "https://example.org/post/1" },
            { "title", "Local-first notes" },
            { "description", "Great essay about #untagged-words and sync." },
            { "category", "article" },
            { "tags", QJsonArray{ "Research" } },
            { "highlight", "Local-first software keeps your data on your device." },
            { "note", "Read again on Sunday." },
            { "cover_base64", sampleB64 },
            { "fetch_metadata", false },
        };
        const QJsonObject wlRes = sendReq("POST", QStringLiteral("/v1/capture/weblink"),
                                          QJsonDocument(wlReq).toJson(QJsonDocument::Compact),
                                          authHeader, "chrome-extension://testext", &st);
        CHECK(st == 200 && wlRes.value("ok").toBool() && !wlRes.value("existing").toBool()
                  && wlRes.value("cover_saved").toBool(),
              "bridge: POST /v1/capture/weblink creates object with WebP cover");

        const QString wlId = wlRes.value("id").toString();
        const QVariantMap wlSummary = ws.objectSummary(wlId);
        const QString coverAssetId = wlSummary.value("coverAssetId").toString();
        CHECK(!coverAssetId.isEmpty() && ws.assetLocalPath(coverAssetId).endsWith(QLatin1String(".webp")),
              "bridge: weblink cover stored locally as .webp");
        CHECK(ws.objectTags(wlId) == QStringList({ QStringLiteral("Research") }),
              "bridge: explicit tags applied; #hashtag in description NOT auto-tagged");

        // Lookup finds the saved URL
        const QJsonObject lookup = sendReq("GET",
                                           QStringLiteral("/v1/lookup?url=https%3A%2F%2Fexample.org%2Fpost%2F1"),
                                           {}, authHeader, "chrome-extension://testext", &st);
        CHECK(st == 200 && lookup.value("found").toBool() && lookup.value("id").toString() == wlId,
              "bridge: GET /v1/lookup finds existing weblink");

        // Second capture of same URL reuses existing object and appends highlight
        const QJsonObject wlReq2{
            { "url", "https://example.org/post/1" },
            { "highlight", "Second highlight." },
            { "fetch_metadata", false },
        };
        const QJsonObject wlRes2 = sendReq("POST", QStringLiteral("/v1/capture/weblink"),
                                           QJsonDocument(wlReq2).toJson(QJsonDocument::Compact),
                                           authHeader, "chrome-extension://testext", &st);
        CHECK(st == 200 && wlRes2.value("existing").toBool() && wlRes2.value("id").toString() == wlId,
              "bridge: duplicate URL reuses existing weblink");

        // Standalone image capture -> WebP Image object
        const QJsonObject imgReq{
            { "image_base64", sampleB64 },
            { "title", "Architecture diagram" },
            { "source_url", "https://example.org/post/1" },
            { "tags", QJsonArray{ "Visual" } },
        };
        const QJsonObject imgRes = sendReq("POST", QStringLiteral("/v1/capture/image"),
                                           QJsonDocument(imgReq).toJson(QJsonDocument::Compact),
                                           authHeader, "chrome-extension://testext", &st);
        CHECK(st == 200 && imgRes.value("ok").toBool()
                  && imgRes.value("mime").toString() == QLatin1String("image/webp"),
              "bridge: POST /v1/capture/image saves standalone image as WebP");
        CHECK(ws.assetLocalPath(imgRes.value("asset_id").toString()).endsWith(QLatin1String(".webp")),
              "bridge: standalone image asset saved with .webp extension");

        // Note capture
        const QJsonObject noteReq{
            { "title", "Quick thought" },
            { "text", "Remember to test offline queue." },
            { "source_url", "https://example.org/post/1" },
            { "tags", QJsonArray{ "Research" } },
        };
        const QJsonObject noteRes = sendReq("POST", QStringLiteral("/v1/capture/note"),
                                            QJsonDocument(noteReq).toJson(QJsonDocument::Compact),
                                            authHeader, "chrome-extension://testext", &st);
        CHECK(st == 200 && noteRes.value("ok").toBool() && !noteRes.value("id").toString().isEmpty(),
              "bridge: POST /v1/capture/note creates note");

        // Meta endpoint lists tags and standard web categories
        const QJsonObject meta = sendReq("GET", QStringLiteral("/v1/meta"), {}, authHeader,
                                         "chrome-extension://testext", &st);
        CHECK(st == 200 && meta.value("tags").toArray().size() >= 2
                  && meta.value("categories").toArray().size() >= 20,
              "bridge: GET /v1/meta returns vault tags and web categories");
    }

    restoreEnv();
}

static void testLinkPreviewParsing()
{
    using namespace pkm::linkpreview;

    // 1. YouTube video ID extraction
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
              == QLatin1String("dQw4w9WgXcQ"),
          "linkpreview: youtube watch?v=");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://www.youtube.com/watch?si=abc&v=ZINu6a3kVnA&feature=youtu.be")))
              == QLatin1String("ZINu6a3kVnA"),
          "linkpreview: youtube watch with query params before v=");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://youtu.be/ZINu6a3kVnA?si=Jx_HGTGDS5hD46T9")))
              == QLatin1String("ZINu6a3kVnA"),
          "linkpreview: youtu.be short url");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://www.youtube.com/shorts/dQw4w9WgXcQ")))
              == QLatin1String("dQw4w9WgXcQ"),
          "linkpreview: youtube shorts");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://www.youtube.com/embed/dQw4w9WgXcQ")))
              == QLatin1String("dQw4w9WgXcQ"),
          "linkpreview: youtube embed");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://www.youtube.com/live/dQw4w9WgXcQ")))
              == QLatin1String("dQw4w9WgXcQ"),
          "linkpreview: youtube live");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://example.com/watch?v=dQw4w9WgXcQ"))).isEmpty(),
          "linkpreview: rejects non-youtube watch url");
    CHECK(extractYouTubeVideoId(QUrl(QStringLiteral("https://www.youtube.com/watch?av=dQw4w9WgXcQ"))).isEmpty(),
          "linkpreview: rejects av= false match");

    // 2. Platform detection (including domain boundary safety)
    CHECK(isReddit(QUrl(QStringLiteral("https://www.reddit.com/r/Kotlin/comments/abc/title/")))
              && isReddit(QUrl(QStringLiteral("https://redd.it/xyz123")))
              && !isReddit(QUrl(QStringLiteral("https://notreddit.com/r/test"))),
          "linkpreview: reddit detection");
    CHECK(isInstagram(QUrl(QStringLiteral("https://www.instagram.com/reel/C123456/")))
              && isInstagram(QUrl(QStringLiteral("https://instagr.am/p/C987654/")))
              && !isInstagram(QUrl(QStringLiteral("https://threads.net"))),
          "linkpreview: instagram detection");
    CHECK(isFacebook(QUrl(QStringLiteral("https://www.facebook.com/watch/?v=123456789")))
              && isFacebook(QUrl(QStringLiteral("https://fb.watch/abc123xyz/")))
              && isFacebook(QUrl(QStringLiteral("https://m.facebook.com/story.php?story_fbid=123")))
              && !isFacebook(QUrl(QStringLiteral("https://yaop.app"))),
          "linkpreview: facebook detection");
    CHECK(isTwitterOrX(QUrl(QStringLiteral("https://twitter.com/user/status/123456")))
              && isTwitterOrX(QUrl(QStringLiteral("https://x.com/user/status/123456")))
              && !isTwitterOrX(QUrl(QStringLiteral("https://myx.com/user"))),
          "linkpreview: twitter/x detection");
    CHECK(isTikTok(QUrl(QStringLiteral("https://www.tiktok.com/@creator/video/1234567890")))
              && !isTikTok(QUrl(QStringLiteral("https://youtube.com"))),
          "linkpreview: tiktok detection");

    // 3. URL & entity cleaning
    const QString fbCdn = cleanImageUrl(QStringLiteral(
        "https://scontent.fmad6-1.fna.fbcdn.net/v/image.jpg?stp=dst-jpg&amp;cstp=dst-jpg&amp;ccb=1-7"));
    CHECK(!fbCdn.contains(QLatin1String("&amp;")) && fbCdn.contains(QLatin1String("&cstp=")),
          "linkpreview: cleanImageUrl decodes &amp;");
    CHECK(cleanImageUrl(QStringLiteral("https:\\/\\/external-preview.redd.it\\/sample.jpg"))
              == QLatin1String("https://external-preview.redd.it/sample.jpg"),
          "linkpreview: cleanImageUrl unescapes slashes");
    CHECK(decodeHtmlEntities(QStringLiteral("Tom &amp; Jerry")) == QLatin1String("Tom & Jerry"),
          "linkpreview: decode &amp;");
    CHECK(decodeHtmlEntities(QStringLiteral("&quot;Quotes&quot; and &#39;Apostrophes&#39;"))
              == QLatin1String("\"Quotes\" and 'Apostrophes'"),
          "linkpreview: decode quotes and apostrophes");
    CHECK(decodeHtmlEntities(QStringLiteral("Price: 100&#8364; &#xa9; A &#x2022; B &#8230; &#064;u"))
              == QStringLiteral("Price: 100€ © A • B … @u"),
          "linkpreview: decode decimal and hex numeric entities");
    CHECK(decodeHtmlEntities(QStringLiteral("&amp;lt;tag&amp;gt;")) == QLatin1String("&lt;tag&gt;"),
          "linkpreview: single-pass entity decode avoids double decoding");

    // 4. Blocked title filter, cleaners, slug fallback
    CHECK(isBlockedTitle(QStringLiteral("Attention Required! | Cloudflare"))
              && isBlockedTitle(QStringLiteral("Just a moment..."))
              && isBlockedTitle(QStringLiteral("Login • Instagram"))
              && isBlockedTitle(QStringLiteral("404 Not Found"))
              && isBlockedTitle(QStringLiteral("Reddit - Dive into anything"))
              && isBlockedTitle(QStringLiteral(" - YouTube"))
              && !isBlockedTitle(QStringLiteral("AI Coding Agents Got Faster")),
          "linkpreview: blocked title filter");
    CHECK(cleanRedditTitle(QStringLiteral(
              "Check out this post on Reddit: Incredible space telescope photo : r/astrophotography - Reddit"))
              == QLatin1String("Incredible space telescope photo"),
          "linkpreview: cleanRedditTitle");
    CHECK(cleanInstagramTitle(QStringLiteral("Creator on Instagram: \"caption\" • Instagram photos and videos"))
              == QLatin1String("Creator"),
          "linkpreview: cleanInstagramTitle");
    CHECK(cleanFacebookTitle(QStringLiteral("Check out this post on Facebook: Meta Engineering | Facebook"))
              == QLatin1String("Meta Engineering"),
          "linkpreview: cleanFacebookTitle");
    CHECK(titleFromUrlSlug(QUrl(QStringLiteral("https://www.reddit.com/r/technology/comments/18z7x3y/breakthrough_in_quantum_computing/")))
              == QLatin1String("Breakthrough In Quantum Computing"),
          "linkpreview: titleFromUrlSlug reddit");

    // 5. OpenGraph HTML parsing: Facebook video, Reddit video, and large >850KB YouTube-style HTML
    const QByteArray fbHtml = R"(
        <html><head>
            <meta property="og:title" content="Meta &amp; Innovation" />
            <meta property="og:description" content="Connecting people &amp; technology" />
            <meta property="og:image" content="https://scontent.fna.fbcdn.net/v/photo.jpg?param=1&amp;param2=2" />
            <meta property="og:site_name" content="Facebook" />
            <meta property="og:type" content="video.other" />
        </head><body></body></html>
    )";
    const auto parsedFb = parseOpenGraphHtml(fbHtml, QUrl(QStringLiteral("https://www.facebook.com/reel/123456")));
    CHECK(parsedFb.has_value()
              && parsedFb->title == QLatin1String("Meta & Innovation")
              && parsedFb->description == QLatin1String("Connecting people & technology")
              && parsedFb->imageUrl == QLatin1String("https://scontent.fna.fbcdn.net/v/photo.jpg?param=1&param2=2")
              && parsedFb->mediaType == LinkMediaType::FacebookVideo,
          "linkpreview: parseOpenGraphHtml Facebook video");

    const QByteArray redditHtml = R"(
        <html><head>
            <meta property="og:title" content="Incredible drone shot : r/drones" />
            <meta property="og:description" content="Shot in 4K with DJI" />
            <meta property="og:image" content="https://preview.redd.it/drone.jpg?width=640&amp;auto=webp" />
            <meta property="og:video" content="https://v.redd.it/video123/HLSPlaylist.m3u8" />
            <meta property="og:site_name" content="Reddit" />
        </head><body></body></html>
    )";
    const auto parsedReddit = parseOpenGraphHtml(
        redditHtml, QUrl(QStringLiteral("https://www.reddit.com/r/drones/comments/abc123/shot/")));
    CHECK(parsedReddit.has_value()
              && parsedReddit->title == QLatin1String("Incredible drone shot")
              && parsedReddit->imageUrl == QLatin1String("https://preview.redd.it/drone.jpg?width=640&auto=webp")
              && parsedReddit->mediaType == LinkMediaType::RedditVideo,
          "linkpreview: parseOpenGraphHtml Reddit video");

    // Large HTML (>850 KB padding before OG tags, matching real YouTube HTML layout)
    QByteArray bigHtml("<html><head><link rel=\"icon\" href=\"/favicon.ico\"><script>");
    bigHtml.append(QByteArray(860 * 1024, 'x'));
    bigHtml.append("</script><meta property=\"og:title\" content=\"Late OG Title\" />"
                   "<meta property=\"og:description\" content=\"Past 850KB offset\" />"
                   "<meta property=\"og:image\" content=\"https://i.ytimg.com/vi/abc12345678/hqdefault.jpg\" />"
                   "</head></html>");
    const auto parsedBig = parseOpenGraphHtml(bigHtml, QUrl(QStringLiteral("https://www.youtube.com/watch?v=abc12345678")));
    CHECK(parsedBig.has_value()
              && parsedBig->title == QLatin1String("Late OG Title")
              && parsedBig->description == QLatin1String("Past 850KB offset")
              && parsedBig->imageUrl == QLatin1String("https://i.ytimg.com/vi/abc12345678/hqdefault.jpg"),
          "linkpreview: parseOpenGraphHtml handles >850KB head without 512KB truncation");

    // 6. Placeholder replacement policy (fixes redirected host & www.-stripped title/site_name bugs)
    const QUrl shortYt(QStringLiteral("https://youtu.be/ZINu6a3kVnA"));
    const QUrl finalYt(QStringLiteral("https://www.youtube.com/watch?v=ZINu6a3kVnA"));
    CHECK(shouldReplacePlaceholderTitle(QStringLiteral("youtu.be"), shortYt, finalYt),
          "linkpreview: replaces redirected short host title");
    CHECK(shouldReplacePlaceholderTitle(QStringLiteral("www.youtube.com"), shortYt, finalYt),
          "linkpreview: replaces final host title");
    CHECK(!shouldReplacePlaceholderTitle(QStringLiteral("My Custom Talk Notes"), shortYt, finalYt),
          "linkpreview: preserves user-edited custom title");
    CHECK(shouldReplacePlaceholderSiteName(QStringLiteral("youtu.be"), shortYt, finalYt),
          "linkpreview: replaces initial host site_name");
    CHECK(!shouldReplacePlaceholderSiteName(QStringLiteral("Custom Source"), shortYt, finalYt),
          "linkpreview: preserves user-edited custom site_name");
}

static void testLinkPreviewPipelines()
{
    using namespace pkm::linkpreview;

    QStringList requestedUrls;
    QHash<QString, HttpResponse> responses;

    auto makeOk = [](const QUrl &url, const QByteArray &body, const QString &ct = QStringLiteral("text/html; charset=utf-8")) {
        HttpResponse r;
        r.ok = true;
        r.statusCode = 200;
        r.finalUrl = url;
        r.contentType = ct;
        r.body = body;
        return r;
    };
    auto makeRedirect = [](const QUrl &url, const QString &location) {
        HttpResponse r;
        r.ok = true;
        r.statusCode = 302;
        r.finalUrl = url;
        r.redirectLocation = location;
        return r;
    };

    HttpGetFn mockTransport = [&](const HttpRequest &req, std::function<void(HttpResponse)> done) {
        const QString u = req.url.toString();
        requestedUrls.append(u);
        auto it = responses.constFind(u);
        if (it != responses.constEnd())
            done(it.value());
        else
            done(HttpResponse{});
    };

    LinkPreviewFetcher fetcher(mockTransport);

    // 1. YouTube oEmbed happy path + LRU cache check
    const QString ytUrl = QStringLiteral("https://youtu.be/ZINu6a3kVnA?si=Jx_HGTGDS5hD46T9");
    const QString ytOembed = QStringLiteral("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=ZINu6a3kVnA&format=json");
    responses.insert(ytOembed, makeOk(QUrl(ytOembed),
        R"({"title":"AI Coding Agents Got Faster","author_name":"Turing Post TV","thumbnail_url":"https://i.ytimg.com/vi/ZINu6a3kVnA/hqdefault.jpg"})",
        QStringLiteral("application/json")));

    std::optional<LinkPreviewData> got;
    fetcher.fetch(ytUrl, LinkPreviewOptions{ true }, [&](const auto &p) { got = p; });
    CHECK(got.has_value()
              && got->title == QLatin1String("AI Coding Agents Got Faster")
              && got->description == QLatin1String("Video by Turing Post TV")
              && got->siteName == QLatin1String("YouTube")
              && got->imageUrl == QLatin1String("https://i.ytimg.com/vi/ZINu6a3kVnA/hqdefault.jpg")
              && got->mediaType == LinkMediaType::YouTube,
          "pipeline: YouTube oEmbed extracts title, author, thumbnail and siteName");

    const qsizetype countAfterFirst = requestedUrls.size();
    fetcher.fetch(ytUrl, LinkPreviewOptions{ true }, [&](const auto &p) { got = p; });
    CHECK(requestedUrls.size() == countAfterFirst && got.has_value(),
          "pipeline: LRU cache serves repeat fetch without extra HTTP call");

    // 2. Reddit with proxies: short link redirect -> blocked direct -> vxreddit title -> Arctic Shift image
    requestedUrls.clear();
    const QString redditShort = QStringLiteral("https://redd.it/abc123");
    const QString redditCanon = QStringLiteral("https://www.reddit.com/r/drones/comments/abc123/alpine_flight/");
    const QString vxReddit = QStringLiteral("https://www.vxreddit.com/r/drones/comments/abc123/alpine_flight/");
    const QString arcticUrl = QStringLiteral("https://arctic-shift.photon-reddit.com/api/posts/ids?ids=abc123");
    responses.insert(redditShort, makeRedirect(QUrl(redditShort), redditCanon));
    responses.insert(redditCanon, makeOk(QUrl(redditCanon), "<title>Just a moment...</title>"));
    responses.insert(vxReddit, makeOk(QUrl(vxReddit),
        R"(<meta property="og:title" content="Alpine flight over glaciers : r/drones"><meta property="og:description" content="Filmed at sunrise"><meta property="og:video" content="https://v.redd.it/x/HLS.m3u8">)"));
    responses.insert(arcticUrl, makeOk(QUrl(arcticUrl),
        R"({"data":[{"title":"Alpine flight","url":"https://i.redd.it/glacier.jpg","is_video":true}]})",
        QStringLiteral("application/json")));

    got.reset();
    fetcher.fetch(redditShort, LinkPreviewOptions{ true }, [&](const auto &p) { got = p; });
    CHECK(got.has_value()
              && got->title == QLatin1String("Alpine flight over glaciers")
              && got->description == QLatin1String("Filmed at sunrise")
              && got->imageUrl == QLatin1String("https://i.redd.it/glacier.jpg")
              && got->siteName == QLatin1String("Reddit")
              && got->mediaType == LinkMediaType::RedditVideo,
          "pipeline: Reddit short link + vxreddit + Arctic Shift fallback");

    // 3. Reddit direct-only mode (allowThirdPartyProxies = false) must NOT query proxies
    requestedUrls.clear();
    got.reset();
    fetcher.fetch(redditShort, LinkPreviewOptions{ false }, [&](const auto &p) { got = p; });
    bool hitProxy = false;
    for (const QString &u : requestedUrls) {
        if (u.contains(QLatin1String("vxreddit")) || u.contains(QLatin1String("arctic-shift")))
            hitProxy = true;
    }
    CHECK(!hitProxy && got.has_value() && got->title == QLatin1String("Alpine Flight")
              && got->siteName == QLatin1String("Reddit"),
          "pipeline: Reddit direct-only mode skips third-party proxies and uses slug fallback");

    // 4. Instagram Reel: crawler returns title/desc without image -> /embed/captioned/ supplies image
    requestedUrls.clear();
    const QString igUrl = QStringLiteral("https://www.instagram.com/reel/Cx12345/");
    const QString igEmbed = QStringLiteral("https://www.instagram.com/p/Cx12345/embed/captioned/");
    responses.insert(igUrl, makeOk(QUrl(igUrl),
        R"(<meta property="og:title" content="Studio Desk • Instagram photos and videos"><meta property="og:description" content="Minimal setup tour">)"));
    responses.insert(igEmbed, makeOk(QUrl(igEmbed),
        R"(<script>{"display_url":"https:\/\/scontent.cdninstagram.com\/v\/desk.jpg?a=1&amp;b=2"}</script>)"));
    got.reset();
    fetcher.fetch(igUrl, LinkPreviewOptions{ false }, [&](const auto &p) { got = p; });
    CHECK(got.has_value()
              && got->title == QLatin1String("Studio Desk")
              && got->description == QLatin1String("Minimal setup tour")
              && got->imageUrl == QLatin1String("https://scontent.cdninstagram.com/v/desk.jpg?a=1&b=2")
              && got->siteName == QLatin1String("Instagram")
              && got->mediaType == LinkMediaType::InstagramReel,
          "pipeline: Instagram Reel falls back to official /embed/captioned/ for image");

    // 5. Facebook watch redirect -> video metadata
    const QString fbShort = QStringLiteral("https://fb.watch/xyz987/");
    const QString fbCanon = QStringLiteral("https://www.facebook.com/watch/?v=998877");
    responses.insert(fbShort, makeRedirect(QUrl(fbShort), fbCanon));
    responses.insert(fbCanon, makeOk(QUrl(fbCanon),
        R"(<meta property="og:title" content="Check out this post on Facebook: Launch Keynote | Facebook"><meta property="og:description" content="Full demo"><meta property="og:image" content="https://scontent.fbcdn.net/k.jpg?x=1&amp;y=2">)"));
    got.reset();
    fetcher.fetch(fbShort, LinkPreviewOptions{ true }, [&](const auto &p) { got = p; });
    CHECK(got.has_value()
              && got->title == QLatin1String("Launch Keynote")
              && got->description == QLatin1String("Full demo")
              && got->imageUrl == QLatin1String("https://scontent.fbcdn.net/k.jpg?x=1&y=2")
              && got->siteName == QLatin1String("Facebook")
              && got->mediaType == LinkMediaType::FacebookVideo,
          "pipeline: Facebook fb.watch redirect + cleaned title + video type");

    // 6. X/Twitter: vxtwitter JSON with proxies; direct-only without proxies
    const QString xUrl = QStringLiteral("https://x.com/alice/status/123456789");
    const QString vxXUrl = QStringLiteral("https://api.vxtwitter.com/alice/status/123456789");
    responses.insert(vxXUrl, makeOk(QUrl(vxXUrl),
        R"({"text":"Local-first FTW!","user_name":"Alice","user_screen_name":"alice","media_urls":["https://pbs.twimg.com/media/pic.jpg"]})",
        QStringLiteral("application/json")));
    got.reset();
    fetcher.fetch(xUrl, LinkPreviewOptions{ true }, [&](const auto &p) { got = p; });
    CHECK(got.has_value()
              && got->title == QLatin1String("Alice (@alice)")
              && got->description == QLatin1String("Local-first FTW!")
              && got->imageUrl == QLatin1String("https://pbs.twimg.com/media/pic.jpg")
              && got->siteName == QLatin1String("X (Twitter)"),
          "pipeline: X/Twitter vxtwitter JSON API");

    requestedUrls.clear();
    got.reset();
    fetcher.fetch(xUrl, LinkPreviewOptions{ false }, [&](const auto &p) { got = p; });
    bool hitXProxy = false;
    for (const QString &u : requestedUrls) {
        if (u.contains(QLatin1String("vxtwitter")) || u.contains(QLatin1String("fixupx")))
            hitXProxy = true;
    }
    CHECK(!hitXProxy && got.has_value() && got->title == QLatin1String("Post on X by @alice"),
          "pipeline: X/Twitter direct-only mode skips proxies");

    // 7. TikTok oEmbed
    const QString ttUrl = QStringLiteral("https://www.tiktok.com/@chef/video/777777");
    const QString ttOembed = QStringLiteral("https://www.tiktok.com/oembed?url=%1")
                                 .arg(QString::fromLatin1(QUrl::toPercentEncoding(ttUrl)));
    responses.insert(ttOembed, makeOk(QUrl(ttOembed),
        R"({"title":"30s espresso recipe","author_name":"chef","thumbnail_url":"https://p16.tiktokcdn.com/thumb.jpg"})",
        QStringLiteral("application/json")));
    got.reset();
    fetcher.fetch(ttUrl, LinkPreviewOptions{ true }, [&](const auto &p) { got = p; });
    CHECK(got.has_value()
              && got->title == QLatin1String("30s espresso recipe")
              && got->description == QLatin1String("@chef on TikTok")
              && got->imageUrl == QLatin1String("https://p16.tiktokcdn.com/thumb.jpg")
              && got->siteName == QLatin1String("TikTok")
              && got->mediaType == LinkMediaType::TikTok,
          "pipeline: TikTok oEmbed extraction");
}

static void testWorkspaceWebLinkEnrichment()
{
    using namespace pkm::linkpreview;

    QTemporaryDir tmp;
    const QByteArray oldData = qgetenv("XDG_DATA_HOME");
    const QByteArray oldCfg = qgetenv("XDG_CONFIG_HOME");
    const QByteArray oldSvc = qgetenv("YAOP_KEYCHAIN_SERVICE");
    const QByteArray oldPass = qgetenv("YAOP_VAULT_PASSPHRASE");
    qputenv("XDG_DATA_HOME", tmp.filePath(QStringLiteral("data")).toUtf8());
    qputenv("XDG_CONFIG_HOME", tmp.filePath(QStringLiteral("cfg")).toUtf8());
    qputenv("YAOP_KEYCHAIN_SERVICE", "YAOP-selftest-weblinks");
    qputenv("YAOP_VAULT_PASSPHRASE", "selftest-passphrase");
    auto restoreEnv = [&]() {
        if (oldData.isEmpty()) qunsetenv("XDG_DATA_HOME"); else qputenv("XDG_DATA_HOME", oldData);
        if (oldCfg.isEmpty()) qunsetenv("XDG_CONFIG_HOME"); else qputenv("XDG_CONFIG_HOME", oldCfg);
        if (oldSvc.isEmpty()) qunsetenv("YAOP_KEYCHAIN_SERVICE"); else qputenv("YAOP_KEYCHAIN_SERVICE", oldSvc);
        if (oldPass.isEmpty()) qunsetenv("YAOP_VAULT_PASSPHRASE"); else qputenv("YAOP_VAULT_PASSPHRASE", oldPass);
    };

    // Valid 1280x720 HD PNG thumbnail for original-resolution WebP cover conversion
    QImage thumbImg(1280, 720, QImage::Format_RGBA8888);
    thumbImg.fill(QColor(0xD0, 0x20, 0x30));
    QByteArray thumbPng;
    {
        QBuffer buf(&thumbPng);
        buf.open(QIODevice::WriteOnly);
        thumbImg.save(&buf, "PNG");
    }

    {
        Workspace ws;
        const QString ytUrl = QStringLiteral("https://youtu.be/ZINu6a3kVnA?si=Jx_HGTGDS5hD46T9");
        const QString ytOembed = QStringLiteral("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=ZINu6a3kVnA&format=json");
        const QString ytThumbHi = QStringLiteral("https://i.ytimg.com/vi/ZINu6a3kVnA/maxresdefault.jpg");

        // Defer replies by 1 event-loop tick to exercise real async behavior with an open EditorModel.
        HttpGetFn asyncMock = [=](const HttpRequest &req, std::function<void(HttpResponse)> done) {
            const QString u = req.url.toString();
            QTimer::singleShot(0, [=, done = std::move(done)]() mutable {
                HttpResponse res;
                if (u == ytOembed) {
                    res.ok = true;
                    res.statusCode = 200;
                    res.finalUrl = req.url;
                    res.contentType = QStringLiteral("application/json");
                    res.body = R"({"title":"Insights: AI Coding Agents Got Faster","author_name":"Turing Post TV","thumbnail_url":"https://i.ytimg.com/vi/ZINu6a3kVnA/hqdefault.jpg"})";
                } else if (u == ytThumbHi) {
                    res.ok = true;
                    res.statusCode = 200;
                    res.finalUrl = req.url;
                    res.contentType = QStringLiteral("image/png");
                    res.body = thumbPng;
                }
                done(std::move(res));
            });
        };

        ws.setLinkPreviewFetcher(new LinkPreviewFetcher(asyncMock, &ws));

        QStringList propChangedIds;
        QObject::connect(&ws, &Workspace::objectPropertiesChanged,
                         [&](const QString &id) { propChangedIds.append(id); });

        const QString wlId = ws.createWebLinkFromUrl(ytUrl);
        EditorModel *ed = ws.openObject(wlId);
        CHECK(ed && ed->title() == QLatin1String("youtu.be"),
              "weblink e2e: starts with placeholder host title");

        // Process queued async callbacks (oEmbed -> applyWebLinkPreview -> downloadImage -> WebP cover).
        QCoreApplication::processEvents();
        QCoreApplication::processEvents();
        QCoreApplication::processEvents();

        CHECK(ed->title() == QLatin1String("Insights: AI Coding Agents Got Faster"),
              "weblink e2e: open EditorModel title updated from placeholder host");
        CHECK(ed->description() == QLatin1String("Video by Turing Post TV"),
              "weblink e2e: open EditorModel description populated");
        CHECK(ws.fieldText(wlId, QStringLiteral("site_name")) == QLatin1String("YouTube"),
              "weblink e2e: site_name upgraded from youtu.be to YouTube");
        CHECK(ws.fieldText(wlId, QStringLiteral("category")) == QLatin1String("video"),
              "weblink e2e: category inferred as video");
        CHECK(ws.fieldText(wlId, QStringLiteral("topics")).contains(QLatin1String("ai_ml")),
              "weblink e2e: topics inferred from enriched title (ai_ml)");
        CHECK(propChangedIds.contains(wlId),
              "weblink e2e: objectPropertiesChanged emitted so PropertyTable refreshes live");
        const QString coverPath = ws.assetLocalPath(ed->coverAssetId());
        CHECK(!ed->coverAssetId().isEmpty() && coverPath.endsWith(QLatin1String(".webp")),
              "weblink e2e: remote thumbnail downloaded and stored locally as .webp cover");
        CHECK(ws.imageFileSize(coverPath) == QSize(1280, 720),
              "weblink e2e: local WebP cover preserves original 1280x720 HD resolution");

        // Cover Image object (media_<assetId>) must expose the WebLink as a backlink.
        const QVariantList coverBls = ws.backlinksFor(objectIdForMedia(ed->coverAssetId()));
        bool foundWebLinkBacklink = false;
        for (const QVariant &v : coverBls) {
            if (v.toMap().value(QStringLiteral("id")).toString() == wlId)
                foundWebLinkBacklink = true;
        }
        CHECK(foundWebLinkBacklink,
              "weblink e2e: cover media object lists owning WebLink in backlinksFor");

        // Verify bookmark block inside the open WebLink editor was updated too.
        CHECK(ed->rowCount() >= 1
                  && ed->data(ed->index(0), EditorModel::BookmarkTitleRole).toString()
                         == QLatin1String("Insights: AI Coding Agents Got Faster")
                  && ed->data(ed->index(0), EditorModel::BookmarkDescRole).toString()
                         == QLatin1String("Video by Turing Post TV"),
              "weblink e2e: bookmark block updated with title and description");

        // Custom title must NOT be overwritten when URL is re-submitted.
        ed->setTitle(QStringLiteral("My Saved Talk"));
        ed->flushNow();
        ws.createWebLinkFromUrl(ytUrl);
        QCoreApplication::processEvents();
        CHECK(ed->title() == QLatin1String("My Saved Talk"),
              "weblink e2e: custom user title preserved on re-fetch");

        ws.closeObject(wlId);
    }

    restoreEnv();
}

int runAll()
{
    std::printf("== desktop app ==\n");
    testMathRender();
    testOutboxBatches();
    testLiveBackoff();
    testCacheCipher();
    testStoreEncryptedAtRest();
    testSearchAndBacklinks();
    testOnboarding();
    testAppLock();
    testMultiBlockClipboard();
    testDesktopIncrementalAndSchemas();
    testWebAndTags();
    testStandardTypeSeeding();
    testSnapshotStore();
    testKeymap();
    testEditorNoirActions();
    testWebPEncoding();
    testCaptureBridge();
    testLinkPreviewParsing();
    testLinkPreviewPipelines();
    testWorkspaceWebLinkEnrichment();
    if (failures == 0)
        std::printf("SELFTEST OK (0 failures)\n");
    else
        std::printf("SELFTEST FAILED (%d failures)\n", failures);
    return failures;
}

} // namespace pkm::selftest
