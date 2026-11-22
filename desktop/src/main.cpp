#include "driver.h"
#include "audioplayback.h"
#include "editormodel.h"
#include "model.h"
#include "providers.h"
#include "selftest.h"
#include "workspace.h"
#include "onboarding.h"
#include "applock.h"
#include "capturebridge.h"
#include "keyinput.h"
#include "keymap.h"

#include <QCommandLineParser>
#include <QCoreApplication>
#include <QDir>
#include <QGuiApplication>
#include <QIcon>
#include <QLibrary>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QElapsedTimer>
#include <QFile>
#include <QSettings>
#include <QStandardPaths>
#include <QElapsedTimer>
#include <QTimer>
#include <QUrl>

namespace {

QString argvValue(int argc, char *argv[], const QByteArray &option, const QByteArray &withEqual)
{
    for (int i = 1; i < argc; ++i) {
        const QByteArray raw = argv[i];
        if (raw == option && i + 1 < argc)
            return QString::fromLocal8Bit(argv[i + 1]);
        if (raw.startsWith(withEqual))
            return QString::fromLocal8Bit(raw.constData() + withEqual.size());
    }
    return {};
}

// --profile <name>: isolate QSettings, the local cache and the keychain entries BEFORE any
// Qt path/lookup happens. Data/config go under a "yaop-profiles/<name>" subtree of the
// current XDG_DATA_HOME/XDG_CONFIG_HOME (so `XDG_*=mktemp` from the harness nests cleanly),
// and the keychain SERVICE becomes "YAOP-<profile>" so the real user entries are never
// read or overwritten.
void applyProfile(int argc, char *argv[], const QString &name)
{
    if (name.isEmpty())
        return;
    const QString sub = QStringLiteral("/yaop-profiles/") + name;
    QString dataHome = qEnvironmentVariable("XDG_DATA_HOME");
    if (dataHome.isEmpty())
        dataHome = QDir::homePath() + QStringLiteral("/.local/share");
    QString configHome = qEnvironmentVariable("XDG_CONFIG_HOME");
    if (configHome.isEmpty())
        configHome = QDir::homePath() + QStringLiteral("/.config");
    QDir().mkpath(dataHome + sub);
    QDir().mkpath(configHome + sub);
    qputenv("XDG_DATA_HOME", (dataHome + sub).toUtf8());
    qputenv("XDG_CONFIG_HOME", (configHome + sub).toUtf8());
    qputenv("YAOP_KEYCHAIN_SERVICE", QByteArrayLiteral("YAOP-") + name.toUtf8());
}

} // namespace

int main(int argc, char *argv[])
{
    const QString profile = argvValue(argc, argv, QByteArrayLiteral("--profile"),
                                     QByteArrayLiteral("--profile="));
    applyProfile(argc, argv, profile);

    QQuickStyle::setStyle(QStringLiteral("Basic"));
    QCoreApplication::setAttribute(Qt::AA_ShareOpenGLContexts);

    // Initialize QtWebEngineQuick when running the GUI so embedded web links can load pages
    // without requiring qt6-qtwebengine-devel headers at build time.
    auto hasCliFlag = [argc, argv](const char *flag) {
        for (int i = 1; i < argc; ++i)
            if (qstrcmp(argv[i], flag) == 0 || QByteArray(argv[i]).startsWith(QByteArray(flag) + '='))
                return true;
        return false;
    };
    if (!hasCliFlag("--selftest") && !hasCliFlag("--e2e") && !hasCliFlag("--pushtest")
        && !hasCliFlag("--uploadtest") && !hasCliFlag("--pullcheck")) {
        QLibrary webEng(QStringLiteral("Qt6WebEngineQuick"), 6);
        if (auto initFn = reinterpret_cast<void (*)()>(webEng.resolve("_ZN16QtWebEngineQuick10initializeEv")))
            initFn();
    }

    QGuiApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("YAOP"));
    app.setApplicationDisplayName(QStringLiteral("YAOP"));
    app.setApplicationVersion(QStringLiteral(PROJECT_VERSION_STR));
    app.setOrganizationName(QStringLiteral("YAOP"));
    app.setDesktopFileName(QStringLiteral("yaop"));
    app.setWindowIcon(QIcon(QStringLiteral(":/assets/yaop.svg")));

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("YAOP desktop"));
    parser.addHelpOption();
    parser.addVersionOption();
    parser.addOption({ QStringLiteral("page"), QStringLiteral("Initial page"),
                       QStringLiteral("page"), QStringLiteral("home") });
    parser.addOption({ QStringLiteral("overlay"), QStringLiteral("Initial overlay"),
                       QStringLiteral("overlay"), QString() });
    parser.addOption({ QStringLiteral("open"), QStringLiteral("Open an object by id"),
                       QStringLiteral("open"), QString() });
    parser.addOption({ QStringLiteral("server"), QStringLiteral("Server base URL override"),
                       QStringLiteral("server"), QString() });
    parser.addOption({ QStringLiteral("token"), QStringLiteral("Bearer token override"),
                       QStringLiteral("token"), QString() });
    parser.addOption({ QStringLiteral("pushtest"), QStringLiteral("Create a rich test object, sync, print push/pull trace, exit") });
    parser.addOption({ QStringLiteral("screenshot"), QStringLiteral("Render to a PNG and exit"),
                       QStringLiteral("path"), QString() });
    parser.addOption({ QStringLiteral("drive"), QStringLiteral("Run a scripted diagnostics scenario"),
                       QStringLiteral("scenario"), QString() });
    parser.addOption({ QStringLiteral("selftest"), QStringLiteral("Run codec/ops self-tests and exit") });
    parser.addOption({ QStringLiteral("uploadtest"), QStringLiteral("Import a file as an asset, wait for its (resumable) upload, exit"),
                       QStringLiteral("path"), QString() });
    parser.addOption({ QStringLiteral("pullcheck"), QStringLiteral("Pull a full server snapshot, parse it, print stats, no UI") });
    parser.addOption({ QStringLiteral("onboarding"), QStringLiteral("Force the first-run onboarding screen (optionally starting at a step)"),
                       QStringLiteral("step"), QStringLiteral("welcome") });
    parser.addOption({ QStringLiteral("showcase"), QStringLiteral("Seed a note with every block type and open it (visual QA)") });
    parser.addOption({ QStringLiteral("size"), QStringLiteral("Window size WxH for --screenshot"),
                       QStringLiteral("WxH"), QString() });
    parser.addOption({ QStringLiteral("profile"), QStringLiteral("Isolate settings, data dir and keychain under this profile name"),
                       QStringLiteral("name"), QString() });
    parser.addOption({ QStringLiteral("e2e"), QStringLiteral("E2e step without UI : pull-assert | edit | digest"),
                       QStringLiteral("step"), QString() });
    parser.addOption({ QStringLiteral("e2e-args"), QStringLiteral("Arguments for --e2e as key=value;key=value"),
                       QStringLiteral("args"), QString() });
    parser.addOption({ QStringLiteral("e2e-out"), QStringLiteral("Output path for --e2e digest"),
                       QStringLiteral("path"), QString() });
    parser.addOption({ QStringLiteral("drive-out"), QStringLiteral("Directory for --drive logs and screenshots (default /tmp)"),
                       QStringLiteral("dir"), QString() });
    parser.addOption({ QStringLiteral("keylog"), QStringLiteral("Print every key press, where it went and which shortcut fired") });
    parser.process(app);

    if (parser.isSet(QStringLiteral("drive-out")))
        qputenv("YAOP_DRIVE_DIR", parser.value(QStringLiteral("drive-out")).toUtf8());

    if (parser.isSet(QStringLiteral("selftest"))) {
        const int failures = pkm::selftest::runAll();
        return failures == 0 ? 0 : 1;
    }

    if (parser.isSet(QStringLiteral("server")))
        QSettings().setValue(QStringLiteral("server/url"), parser.value(QStringLiteral("server")));
    if (parser.isSet(QStringLiteral("token")))
        QSettings().setValue(QStringLiteral("server/token"), parser.value(QStringLiteral("token")));

    if (parser.isSet(QStringLiteral("e2e"))) {
        pkm::Workspace workspace;
        workspace.initialize();
        return pkm::runE2eStep(&workspace,
                              parser.value(QStringLiteral("e2e")),
                              parser.value(QStringLiteral("e2e-args")),
                              parser.value(QStringLiteral("e2e-out")));
    }

    qmlRegisterType<pkm::PairingFlow>("YAOP.Core", 1, 0, "PairingFlow");
    qmlRegisterUncreatableType<pkm::VaultOnboarding>("YAOP.Core", 1, 0, "VaultOnboarding",
                                                    QStringLiteral("created by main"));

    // First run : no vault yet -> create/restore/join before the app shell exists.
    // Headless modes keep the old auto-created key (tests, e2e, screenshots).
    {
        pkm::VaultOnboarding onboarding(pkm::Workspace::vaultAccount(), pkm::Workspace::vaultWrappedFile());
        const bool headless = parser.isSet(QStringLiteral("pushtest")) || parser.isSet(QStringLiteral("uploadtest"))
            || parser.isSet(QStringLiteral("pullcheck")) || parser.isSet(QStringLiteral("drive"))
            || parser.isSet(QStringLiteral("screenshot")) || !qEnvironmentVariableIsEmpty("YAOP_VAULT_PASSPHRASE");
        const bool forced = parser.isSet(QStringLiteral("onboarding"));
        if (forced || (!headless && !onboarding.vaultExists())) {
            QQmlApplicationEngine onbEngine;
            onbEngine.rootContext()->setContextProperty(QStringLiteral("onb"), &onboarding);
            onbEngine.rootContext()->setContextProperty(QStringLiteral("initialOnboardingStep"),
                                                        forced ? parser.value(QStringLiteral("onboarding")) : QString());
            onbEngine.addImportPath(QStringLiteral("qrc:/qml"));
            onbEngine.addImportPath(QStringLiteral("qrc:/"));
            QObject::connect(&onboarding, &pkm::VaultOnboarding::finished, &app, &QGuiApplication::quit);
            onbEngine.load(QUrl(QStringLiteral("qrc:/qml/OnboardingWindow.qml")));
            if (onbEngine.rootObjects().isEmpty())
                return 1;
            const QString shot = parser.value(QStringLiteral("screenshot"));
            if (forced && !shot.isEmpty()) {
                auto *w = qobject_cast<QQuickWindow *>(onbEngine.rootObjects().constFirst());
                QTimer::singleShot(800, &app, [w, shot, &app] {
                    w->grabWindow().save(shot);
                    app.quit();
                });
            }
            app.exec();
            if (!onboarding.committed())
                return 0;
        }
    }

    pkm::Workspace workspace;
    if (parser.isSet(QStringLiteral("pushtest"))) {
        workspace.initialize();
        const QString id = workspace.createObject(QStringLiteral("note"),
                                                   QStringLiteral("Push test desde desktop"));
        qInfo().noquote() << "PUSHTEST created" << id;
        // Wait for the debounced push + pull, then verify the queue drained.
        QTimer::singleShot(7000, &app, [&workspace, &app]() {
            qInfo().noquote() << "PUSHTEST pendingOps" << workspace.pendingCount()
                             << "connected" << workspace.connected() << "live" << workspace.live();
            app.exit(workspace.pendingCount() == 0 ? 0 : 3);
        });
        return app.exec();
    }
    if (parser.isSet(QStringLiteral("uploadtest"))) {
        workspace.initialize();
        const QString id = workspace.importFileAsAsset(parser.value(QStringLiteral("uploadtest")));
        qInfo().noquote() << "UPLOADTEST asset" << id;
        auto *poll = new QTimer(&app);
        QElapsedTimer clock;
        clock.start();
        QObject::connect(poll, &QTimer::timeout, &app, [&workspace, &app, clock]() {
            if (workspace.pendingUploadCount() == 0) {
                qInfo().noquote() << "UPLOADTEST done in" << clock.elapsed() << "ms";
                app.exit(0);
            } else if (clock.elapsed() > 180000) {
                qInfo().noquote() << "UPLOADTEST timed out";
                app.exit(3);
            }
        });
        poll->start(500);
        return app.exec();
    }
    if (parser.isSet(QStringLiteral("pullcheck"))) {
        QTimer::singleShot(1500, &app, [&workspace, &app]() {
            workspace.triggerFullSync();
            QTimer::singleShot(4000, &app, [&workspace, &app]() {
                const int code = workspace.debugPullCheck();
                app.exit(code);
            });
        });
        return app.exec();
    }

    qmlRegisterType<pkm::AudioPlayback>("YAOP.Core", 1, 0, "AudioPlayback");

    // Local lock : PIN gate in front of the UI; headless modes never lock.
    pkm::AppLock appLock;
    app.installEventFilter(&appLock);
    pkm::KeyInput keyInput(parser.isSet(QStringLiteral("keylog")));
    app.installEventFilter(&keyInput);
    pkm::Keymap keymap;
    pkm::CaptureBridge captureBridge(&workspace);

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("appLock"), &appLock);
    engine.rootContext()->setContextProperty(QStringLiteral("ws"), &workspace);
    engine.rootContext()->setContextProperty(QStringLiteral("captureBridge"), &captureBridge);
    engine.rootContext()->setContextProperty(QStringLiteral("keymap"), &keymap);
    engine.rootContext()->setContextProperty(QStringLiteral("keyInput"), &keyInput);
    engine.rootContext()->setContextProperty(QStringLiteral("initialPage"), parser.value(QStringLiteral("page")));
    engine.rootContext()->setContextProperty(QStringLiteral("initialOverlay"), parser.value(QStringLiteral("overlay")));
    engine.rootContext()->setContextProperty(QStringLiteral("initialObjectId"), parser.value(QStringLiteral("open")));

    auto *texProvider = new pkm::TeXImageProvider();
    auto *pdfProvider = new pkm::PdfPageProvider(
        workspace.assetCacheDir());
    pdfProvider->setAssetLookup([&workspace](const QString &id) { return workspace.assetLocalPath(id); });
    engine.addImageProvider(QStringLiteral("tex"), texProvider);
    engine.addImageProvider(QStringLiteral("pdfpage"), pdfProvider);
    engine.addImageProvider(QStringLiteral("icon"), new pkm::IconProvider());

    engine.addImportPath(QStringLiteral("qrc:/qml"));
    engine.addImportPath(QStringLiteral("qrc:/"));

    workspace.initialize();
    if (!parser.isSet(QStringLiteral("screenshot")) && !parser.isSet(QStringLiteral("drive")))
        captureBridge.start();
    if (parser.isSet(QStringLiteral("showcase"))) {
        const QString id = pkm::seedShowcase(&workspace);
        // With --page or --open the fixture only seeds data; alone it opens the showcase note.
        if (!parser.isSet(QStringLiteral("page")) && parser.value(QStringLiteral("open")).isEmpty()) {
            engine.rootContext()->setContextProperty(QStringLiteral("initialPage"), QStringLiteral("editor"));
            engine.rootContext()->setContextProperty(QStringLiteral("initialObjectId"), id);
        }
    }

    QObject::connect(&engine, &QQmlApplicationEngine::warnings,
                     [](const QList<QQmlError> &warnings) {
                         QFile f(QStringLiteral("/tmp/qml.log"));
                         const bool existed = f.exists();
                         if (f.open(QIODevice::WriteOnly | QIODevice::Append)) {
                             if (!existed)
                                 f.write("--- session ---\n");
                             for (const QQmlError &w : warnings)
                                 f.write(w.toString().toUtf8() + QByteArrayLiteral("\n"));
                         }
                         for (const QQmlError &w : warnings) {
                             qWarning().noquote() << "QML:" << w.toString();
                         }
                     });
    engine.load(QUrl(QStringLiteral("qrc:/qml/Main.qml")));
    if (engine.rootObjects().isEmpty())
        return 2;

    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().isEmpty()
                          ? nullptr : engine.rootObjects().constFirst());
    if (!window)
        return 2;

    if (parser.isSet(QStringLiteral("drive"))) {
        const int code = pkm::runDrive(window, &workspace, parser.value(QStringLiteral("drive")));
        if (code != 0)
            return code;
        return app.exec();
    }

    const QString screenshotPath = parser.value(QStringLiteral("screenshot"));
    if (parser.isSet(QStringLiteral("size"))) {
        const QStringList wh = parser.value(QStringLiteral("size")).split(QLatin1Char('x'));
        if (wh.size() == 2) {
            window->setMinimumSize(QSize(0, 0));
            window->resize(wh[0].toInt(), wh[1].toInt());
        }
    }
    if (!screenshotPath.isEmpty()) {
        QTimer::singleShot(2500, &app, [&app, window, screenshotPath]() {
            const QImage image = window->grabWindow();
            const bool saved = image.save(screenshotPath);
            qInfo().noquote() << "saved" << screenshotPath << image.size() << saved;
            app.quit();
        });
    }

    return app.exec();
}
