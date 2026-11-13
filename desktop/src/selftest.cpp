// Desktop app self-test (`yaop --selftest`): storage, onboarding, lock, clipboard.
// The core (model, merge, query, crypto) is tested by pkmcore_tests.
#include "selftest.h"
#include "testing.h"
#include "blockops.h"
#include "model.h"
#include "cachecrypto.h"
#include "onboarding.h"
#include "editormodel.h"
#include "applock.h"
#include "outbox.h"
#include "searchindex.h"
#include "store.h"
#include "syncclient.h"
#include "spancodec.h"
#include "texrender_internal.h"
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
    if (failures == 0)
        std::printf("SELFTEST OK (0 failures)\n");
    else
        std::printf("SELFTEST FAILED (%d failures)\n", failures);
    return failures;
}

} // namespace pkm::selftest
