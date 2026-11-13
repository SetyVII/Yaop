// pkmcore_tests: runs the core against the shared contract fixtures in spec/fixtures.
#include "testing.h"
#include <QGuiApplication>

namespace pkm::testing {
int runEditorTests();
#ifdef PKMCORE_WITH_VAULT
int runVaultTests();
#endif
}

int main(int argc, char **argv)
{
    qputenv("QT_QPA_PLATFORM", qgetenv("QT_QPA_PLATFORM").isEmpty() ? "offscreen" : qgetenv("QT_QPA_PLATFORM"));
    QGuiApplication app(argc, argv); // QTextDocument (spancodec) needs a GUI app
    pkm::testing::runEditorTests();
#ifdef PKMCORE_WITH_VAULT
    pkm::testing::runVaultTests();
#endif
    const int f = pkm::testing::failures;
    std::printf(f ? "PKMCORE TESTS FAILED (%d failures)\n" : "PKMCORE TESTS OK\n", f);
    return f ? 1 : 0;
}
